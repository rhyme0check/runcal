package com.jongsun.runcal.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import com.jongsun.runcal.data.room.NotionCopyEntity
import com.jongsun.runcal.data.room.RunCalDatabase
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "RunCal"

/** 복사본 메모 첫 줄. 이 표시가 있는 일정만 RunCal이 고치거나 지운다. */
const val NOTION_COPY_MARKER = "[RunCal 복사]"

/** 복사 범위: Notion 동기화와 같다(과거 3개월 ~ 미래 12개월). */
private const val COPY_PAST_MONTHS = 3L
private const val COPY_FUTURE_MONTHS = 12L

data class NotionCopyResult(val created: Int, val updated: Int, val deleted: Int, val error: String? = null) {
    val summary: String get() = error ?: "새로 $created · 고침 $updated · 지움 $deleted"
}

/** 복사본 일정 id의 메모리 캐시. RunCal 화면에서는 복사본을 숨긴다(Notion 원본과 두 번 보이지 않게). */
object NotionCopies {
    @Volatile private var cached: Set<Long>? = null

    suspend fun eventIds(context: Context): Set<Long> =
        cached ?: RunCalDatabase.getInstance(context).usageDao().allCopies().map { it.eventId }.toSet().also { cached = it }

    fun invalidate() {
        cached = null
    }
}

/**
 * Notion 항목을 폰 캘린더(예: 삼성 My calendar)에 복사한다 — RunCal을 그만 써도 일정이 폰에 남게 하는 백업.
 * 같은 Notion 항목은 매번 같은 복사본을 고치고(중복 없음), Notion에서 지워진 항목의 복사본만 지운다.
 * 복사본에는 알림을 넣지 않는다(Notion 항목 알림은 RunCal이 따로 건다 — 두 번 울리지 않게).
 * DB를 RunCal에서 뺐거나 동기화가 실패한 DB의 복사본은 지우지 않는다(백업이 사라지지 않게).
 */
class NotionCalendarCopyJob(private val context: Context) {
    private val db = RunCalDatabase.getInstance(context)
    private val resolver get() = context.contentResolver

    suspend fun run(): NotionCopyResult = withContext(Dispatchers.IO) {
        val targetId = AppSettingsRepository(context).settings.first().copyCalendarId
            ?: return@withContext NotionCopyResult(0, 0, 0, "복사할 캘린더가 정해지지 않았습니다")
        if (!hasCalendarWritePermission(context)) return@withContext NotionCopyResult(0, 0, 0, "캘린더 쓰기 권한이 없습니다")
        val target = CalendarRepository(context).getCalendars().firstOrNull { it.id == targetId && it.isWritable }
            ?: return@withContext NotionCopyResult(0, 0, 0, "복사할 캘린더를 찾을 수 없습니다(지워졌거나 읽기 전용)")

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val windowStart = today.minusMonths(COPY_PAST_MONTHS).atStartOfDay(zone).toInstant().toEpochMilli()
        val windowEnd = today.plusMonths(COPY_FUTURE_MONTHS).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val registrations = db.notionDatabaseDao().getAll().associateBy { it.id }
        val rows = db.notionEventDao().getEventsInRangeAllDbs(windowStart, windowEnd).filter { it.registrationId in registrations }
        val usage = db.usageDao()
        val copies = usage.allCopies().associateBy { it.registrationId + ":" + it.pageId }
        val index = EventGroups.index(context)

        var created = 0
        var updated = 0
        var deleted = 0
        val seen = HashSet<String>()
        try {
            for (row in rows) {
                val key = row.registrationId + ":" + row.notionPageId
                seen += key
                val registration = registrations.getValue(row.registrationId)
                // 그룹 색이 있으면 그 색, 없으면 Notion DB 색.
                val asItem = EventItem(
                    id = 0, calendarId = 0, title = row.title, begin = row.startMillis, end = row.endMillis, allDay = row.allDay,
                    color = registration.colorArgb, sourceKind = com.jongsun.runcal.data.source.EventSourceKind.NOTION,
                    notionPageId = row.notionPageId, notionDatabaseId = row.registrationId,
                )
                val color = index.groupOf(asItem)?.colorArgb ?: registration.colorArgb
                val description = buildString {
                    append(NOTION_COPY_MARKER).append(' ').append(registration.displayName)
                    row.statusRaw?.let { append(" · ").append(it) }
                    row.subtitle?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
                    if (row.notionUrl.isNotBlank()) append('\n').append(row.notionUrl)
                }
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, target.id)
                    put(CalendarContract.Events.TITLE, row.title.ifBlank { "(제목 없음)" })
                    put(CalendarContract.Events.DTSTART, row.startMillis)
                    // 끝 시각이 없는 시간 일정은 30분짜리로 둔다(0분 일정은 캘린더 앱마다 다르게 보여서).
                    put(CalendarContract.Events.DTEND, if (row.allDay || row.endMillis > row.startMillis) row.endMillis else row.startMillis + 30 * 60_000L)
                    put(CalendarContract.Events.ALL_DAY, if (row.allDay) 1 else 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, if (row.allDay) "UTC" else zone.id)
                    put(CalendarContract.Events.DESCRIPTION, description)
                    put(CalendarContract.Events.EVENT_COLOR, color)
                    put(CalendarContract.Events.HAS_ALARM, 0)
                }
                val existing = copies[key]
                if (existing != null && copyStillExists(existing.eventId, target.id)) {
                    resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing.eventId), values, null, null)
                    if (existing.startMillis != row.startMillis) usage.putCopy(existing.copy(startMillis = row.startMillis))
                    updated++
                } else {
                    val newId = resolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull() ?: continue
                    usage.putCopy(NotionCopyEntity(row.registrationId, row.notionPageId, newId, row.startMillis))
                    created++
                }
            }
            // Notion에서 지워진 항목: 범위 안에 있었고, 그 DB가 지금 정상 동기화된 경우에만 복사본을 지운다.
            for ((key, copy) in copies) {
                if (key in seen) continue
                val registration = registrations[copy.registrationId] ?: continue
                if (registration.lastSyncStatus != "OK") continue
                if (copy.startMillis !in windowStart until windowEnd) continue
                if (copyStillExists(copy.eventId, target.id)) {
                    resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, copy.eventId), null, null)
                }
                usage.deleteCopy(copy.registrationId, copy.pageId)
                deleted++
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "NotionCalendarCopyJob failed", e)
            return@withContext NotionCopyResult(created, updated, deleted, "캘린더에 쓰지 못했습니다")
        } finally {
            NotionCopies.invalidate()
        }
        Log.d(TAG, "NotionCalendarCopyJob: created=$created updated=$updated deleted=$deleted target=${target.id}")
        NotionCopyResult(created, updated, deleted)
    }

    /** 만든 복사본을 모두 지운다(복사 캘린더를 바꾸거나 그만둘 때). [RunCal 복사] 표시가 있는 것만 지운다. 지운 개수를 돌려준다. */
    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        val usage = db.usageDao()
        var removed = 0
        try {
            usage.allCopies().forEach { copy ->
                val isOurs = resolver.query(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, copy.eventId),
                    arrayOf(CalendarContract.Events.DESCRIPTION),
                    null, null, null,
                )?.use { c -> c.moveToFirst() && c.getString(0).orEmpty().startsWith(NOTION_COPY_MARKER) } ?: false
                if (isOurs) {
                    resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, copy.eventId), null, null)
                    removed++
                }
                usage.deleteCopy(copy.registrationId, copy.pageId)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "NotionCalendarCopyJob.clearAll failed", e)
        } finally {
            NotionCopies.invalidate()
        }
        removed
    }

    /** 복사본이 아직 그 캘린더에 있고, 우리가 만든 것(메모 표시)인지. 사용자가 지웠거나 옮겼으면 새로 만든다. */
    private fun copyStillExists(eventId: Long, calendarId: Long): Boolean {
        resolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.DELETED),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return false
            return c.getLong(0) == calendarId && c.getString(1).orEmpty().startsWith(NOTION_COPY_MARKER) && c.getInt(2) == 0
        }
        return false
    }
}
