package com.jongsun.runcal.data.notion

import android.content.Context
import android.util.Log
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.room.NotionDatabaseEntity
import com.jongsun.runcal.data.room.RunCalDatabase
import com.jongsun.runcal.data.source.EventSourceKind
import com.jongsun.runcal.work.NotionSyncJob
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG = "RunCal"

/** 되돌리기 기록 보관 기간. 변경 기록 화면에서 이 시간 안에는 다시 되돌릴 수 있다. */
const val NOTION_UNDO_KEEP_MILLIS = 24L * 60 * 60 * 1000

/** 바꿀 날짜. 종일은 날짜만(마지막 날 포함), 시간 지정은 로컬 시각. */
sealed interface NotionDateSpec {
    data class AllDay(val start: LocalDate, val endInclusive: LocalDate) : NotionDateSpec
    data class Timed(val start: LocalDateTime, val end: LocalDateTime?) : NotionDateSpec
}

/** Notion 항목 하나에 대한 변경 요청. null인 항목은 바꾸지 않는다. */
data class NotionChange(val date: NotionDateSpec? = null, val status: String? = null)

sealed interface NotionWriteResult {
    data class Ok(val undoId: String) : NotionWriteResult

    /** 그 사이 Notion에서 바뀌어 실행하지 않았다(최신 내용으로 다시 동기화함). */
    data class Conflict(val message: String) : NotionWriteResult
    data class Failed(val message: String) : NotionWriteResult
}

/** 되돌리기에 필요한 것: 바꾸기 전 속성 값(쓰기 형식)과, 우리가 바꾼 직후의 last_edited_time. */
@Serializable
data class NotionUndoRecord(
    val id: String,
    val registrationId: String,
    val pageId: String,
    val previousProperties: String,
    val appliedLastEdited: String,
    val createdAtMillis: Long,
    val used: Boolean = false,
)

/**
 * Notion 항목의 날짜·상태만 바꾸는 서비스(P8). 순서는 항상 같다:
 * DB가 쓰기 허용인지 확인 → 페이지를 다시 읽어 캐시와 last_edited_time이 같은지 확인(다르면 실행하지 않고 그 DB를 재동기화)
 * → 바꾸기 전 값을 되돌리기 기록으로 저장 → PATCH → 응답으로 캐시 행 갱신.
 * 제목·본문·다른 속성은 건드리지 않고, 페이지를 만들거나 지우지 않는다.
 */
class NotionWriteService(private val context: Context, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val db = RunCalDatabase.getInstance(context)
    private val writer = NotionPageWriter()

    suspend fun apply(event: EventItem, change: NotionChange): NotionWriteResult {
        if (event.sourceKind != EventSourceKind.NOTION) return NotionWriteResult.Failed("Notion 항목이 아닙니다")
        val pageId = event.notionPageId ?: return NotionWriteResult.Failed("Notion 페이지 정보가 없습니다")
        val registration = db.notionDatabaseDao().getById(event.notionDatabaseId.orEmpty())
            ?: return NotionWriteResult.Failed("등록된 Notion DB를 찾지 못했습니다")
        if (!registration.writeEnabled) {
            return NotionWriteResult.Failed("'${registration.displayName}'은(는) 앱에서 수정이 꺼져 있습니다. 설정 > Notion 연동에서 켜 주세요.")
        }
        if (change.status != null) {
            val statusProperty = registration.statusProperty ?: return NotionWriteResult.Failed("이 DB에는 상태 속성이 매핑되어 있지 않습니다")
            val options = parseSchemaSummary(registration.schemaJson).statusOptions
            if (options.isNotEmpty() && change.status !in options) {
                return NotionWriteResult.Failed("'${change.status}'은(는) '$statusProperty'에 없는 값입니다")
            }
        }
        val cached = db.notionEventDao().getByPage(registration.id, pageId)
            ?: return NotionWriteResult.Failed("캐시에 없는 항목입니다. 동기화 후 다시 시도하세요")

        return write(registration, pageId, expectedLastEdited = cached.lastEditedTimeIso) { page ->
            buildJsonObject {
                change.date?.let { put(registration.dateProperty, dateValue(it)) }
                change.status?.let { put(registration.statusProperty!!, statusValue(registration, page, it)) }
            }
        }
    }

    /** [NotionUndoRecord]로 바꾸기 전 값으로 되돌린다. 그 뒤 Notion에서 다시 바뀌었으면 되돌리지 않는다. */
    suspend fun undo(undoId: String): NotionWriteResult {
        val record = NotionUndoStore.get(context, undoId) ?: return NotionWriteResult.Failed("되돌리기 기록이 없거나 24시간이 지났습니다")
        if (record.used) return NotionWriteResult.Failed("이미 되돌린 변경입니다")
        val registration = db.notionDatabaseDao().getById(record.registrationId)
            ?: return NotionWriteResult.Failed("등록된 Notion DB를 찾지 못했습니다")
        val previous = Json.parseToJsonElement(record.previousProperties).jsonObject
        val result = write(registration, record.pageId, expectedLastEdited = record.appliedLastEdited, recordUndo = false) { previous }
        if (result is NotionWriteResult.Ok) NotionUndoStore.markUsed(context, undoId)
        return result
    }

    private suspend fun write(
        registration: NotionDatabaseEntity,
        pageId: String,
        expectedLastEdited: String,
        recordUndo: Boolean = true,
        buildPatch: (NotionPage) -> JsonObject,
    ): NotionWriteResult {
        val current = try {
            writer.retrievePage(pageId)
        } catch (e: NotionApiException) {
            return NotionWriteResult.Failed(httpMessage(e.statusCode, reading = true))
        } catch (e: Exception) {
            return NotionWriteResult.Failed("Notion에 연결하지 못했습니다")
        }
        if (current.lastEditedTime != expectedLastEdited) {
            Log.d(TAG, "NotionWriteService: conflict on ${registration.id} — resyncing")
            resync(registration)
            return NotionWriteResult.Conflict("Notion에서 그 사이 바뀐 항목이라 실행하지 않았습니다. 최신 내용으로 다시 불러왔으니 확인 후 다시 시도하세요.")
        }
        val patch = buildPatch(current)
        val previous = buildJsonObject {
            patch.keys.forEach { name -> put(name, writableValueOf(current.properties[name])) }
        }
        val updated = try {
            writer.updateProperties(pageId, patch)
        } catch (e: NotionApiException) {
            return NotionWriteResult.Failed(httpMessage(e.statusCode, reading = false))
        } catch (e: Exception) {
            return NotionWriteResult.Failed("Notion에 연결하지 못했습니다(변경 여부를 Notion에서 확인하세요)")
        }

        val now = System.currentTimeMillis()
        val row = NotionPropertyMapper.toEntity(registration, updated, zone, now)
        if (row != null) db.notionEventDao().insertAll(listOf(row)) else db.notionEventDao().deleteByPage(registration.id, pageId)

        val undoId = UUID.randomUUID().toString()
        if (recordUndo) {
            NotionUndoStore.add(
                context,
                NotionUndoRecord(undoId, registration.id, pageId, previous.toString(), updated.lastEditedTime, now),
            )
        }
        return NotionWriteResult.Ok(undoId)
    }

    private suspend fun resync(registration: NotionDatabaseEntity) {
        runCatching { NotionSyncJob(db.notionDatabaseDao(), db.notionEventDao(), NotionApiClient(), zone).syncOne(registration) }
    }

    private fun dateValue(spec: NotionDateSpec): JsonObject = buildJsonObject {
        put(
            "date",
            buildJsonObject {
                when (spec) {
                    is NotionDateSpec.AllDay -> {
                        put("start", spec.start.format(DateTimeFormatter.ISO_LOCAL_DATE))
                        if (spec.endInclusive > spec.start) put("end", spec.endInclusive.format(DateTimeFormatter.ISO_LOCAL_DATE)) else put("end", JsonNull)
                    }
                    is NotionDateSpec.Timed -> {
                        put("start", spec.start.atZone(zone).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                        val end = spec.end?.takeIf { it != spec.start }
                        if (end != null) put("end", end.atZone(zone).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)) else put("end", JsonNull)
                    }
                }
            },
        )
    }

    private fun statusValue(registration: NotionDatabaseEntity, page: NotionPage, name: String): JsonObject {
        // 매핑 당시 타입보다 지금 페이지의 실제 타입을 우선한다(상태/선택 둘 다 지원).
        val type = page.properties[registration.statusProperty]?.get("type")?.jsonPrimitive?.contentOrNull
            ?: parseSchemaSummary(registration.schemaJson).statusType ?: "status"
        return buildJsonObject { put(type, buildJsonObject { put("name", name) }) }
    }

    /** GET으로 받은 속성 값을 PATCH에 다시 넣을 수 있는 형식으로 바꾼다(날짜·상태·선택만). */
    private fun writableValueOf(property: JsonObject?): JsonObject {
        val type = property?.get("type")?.jsonPrimitive?.contentOrNull
        return when (type) {
            "date" -> {
                val date = property["date"] as? JsonObject
                buildJsonObject {
                    if (date == null) {
                        put("date", JsonNull)
                    } else {
                        put(
                            "date",
                            buildJsonObject {
                                put("start", date["start"] ?: JsonNull)
                                put("end", date["end"] ?: JsonNull)
                                date["time_zone"]?.takeIf { it !is JsonNull }?.let { put("time_zone", it) }
                            },
                        )
                    }
                }
            }
            "status", "select" -> {
                val name = (property[type] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                buildJsonObject { put(type, if (name == null) JsonNull else buildJsonObject { put("name", name) }) }
            }
            else -> buildJsonObject { }
        }
    }

    private fun httpMessage(code: Int?, reading: Boolean): String = when (code) {
        401 -> "Notion 토큰이 유효하지 않습니다"
        403 -> if (reading) "이 DB에 통합(integration) 연결이 없습니다" else "통합에 '콘텐츠 업데이트' 권한이 없습니다. Notion 통합 설정에서 켜 주세요"
        404 -> "Notion에서 항목을 찾지 못했습니다(삭제됐거나 공유가 해제됨)"
        409 -> "Notion에서 동시에 수정 중이라 실패했습니다. 잠시 후 다시 시도하세요"
        429 -> "Notion 요청 한도를 넘었습니다. 잠시 후 다시 시도하세요"
        400 -> "Notion이 요청을 거부했습니다(속성 형식 불일치)"
        else -> "Notion 요청이 실패했습니다(HTTP $code)"
    }

    companion object {
        /** 캐시의 종일(UTC 자정) / 시간 지정(로컬) 값을 [NotionDateSpec]으로 바꾼다. */
        fun specOf(allDay: Boolean, begin: Long, end: Long, zone: ZoneId = ZoneId.systemDefault()): NotionDateSpec =
            if (allDay) {
                val start = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
                val last = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                NotionDateSpec.AllDay(start, maxOf(start, last))
            } else {
                NotionDateSpec.Timed(
                    Instant.ofEpochMilli(begin).atZone(zone).toLocalDateTime(),
                    Instant.ofEpochMilli(end).atZone(zone).toLocalDateTime(),
                )
            }
    }
}

/** 되돌리기 기록(앱 전용 저장소, 24시간 보관). 백업·전송 대상이 아니다. */
object NotionUndoStore {
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(context: Context): File = File(context.filesDir, "assistant/notion_undo.jsonl").also { it.parentFile?.mkdirs() }

    @Synchronized
    private fun readAll(context: Context): List<NotionUndoRecord> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val cutoff = System.currentTimeMillis() - NOTION_UNDO_KEEP_MILLIS
        return f.readLines().mapNotNull { runCatching { json.decodeFromString(NotionUndoRecord.serializer(), it) }.getOrNull() }
            .filter { it.createdAtMillis >= cutoff }
    }

    @Synchronized
    private fun writeAll(context: Context, records: List<NotionUndoRecord>) {
        file(context).writeText(records.joinToString("") { json.encodeToString(NotionUndoRecord.serializer(), it) + "\n" })
    }

    @Synchronized
    fun add(context: Context, record: NotionUndoRecord) = writeAll(context, readAll(context) + record)

    @Synchronized
    fun get(context: Context, id: String): NotionUndoRecord? = readAll(context).firstOrNull { it.id == id }

    @Synchronized
    fun markUsed(context: Context, id: String) = writeAll(context, readAll(context).map { if (it.id == id) it.copy(used = true) else it })

    /** 변경 기록 화면에서 "되돌리기" 버튼을 보여줄지. */
    @Synchronized
    fun isUndoable(context: Context, id: String): Boolean = get(context, id)?.let { !it.used } ?: false
}
