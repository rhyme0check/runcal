package com.jongsun.runcal.ai

import com.jongsun.runcal.data.CalendarInfo
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.source.EventSourceKind
import com.jongsun.runcal.data.dateRange
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val MAX_RESULTS = 30
private const val MAX_RANGE_DAYS = 92L

/** 앱이 모델에게 열어주는 데이터 창구. 화면과 같은 필터(표시 캘린더·프리셋)가 적용된 결과를 준다. */
interface AssistantDataSource {
    suspend fun eventsInRange(startMillis: Long, endMillis: Long): List<EventItem>
    fun calendars(): List<CalendarInfo>
    fun weekStartDay(): DayOfWeek

    /** 새 일정에 기본으로 깔아줄 알림(분). 앱 설정의 "새 일정 기본 알림". */
    fun defaultReminderMinutes(): List<Int>
}

/**
 * 모델에 나가는 데이터를 최소화하기 위한 익명화 장부. 일정은 E1, E2…, 캘린더/Notion DB는 C1, C2…로만 나가고
 * (제목·장소·메모·캘린더 이름은 나가지 않는다), 이 장부(로컬 메모리)만 라벨과 실제 일정을 연결한다.
 * 라벨은 대화가 이어지는 동안 유지되어 "그거 취소해줘" 같은 후속 명령에서 같은 일정을 가리킨다.
 */
class LabelBook {
    private val eventByLabel = LinkedHashMap<String, EventItem>()
    private val labelByEventKey = HashMap<String, String>()
    private val calendarLabels = LinkedHashMap<String, String>()

    fun eventLabel(event: EventItem): String {
        val key = "${event.sourceKind}:${event.id}:${event.begin}"
        return labelByEventKey.getOrPut(key) { "E${labelByEventKey.size + 1}".also { eventByLabel[it] = event } }
    }

    fun event(label: String): EventItem? = eventByLabel[label]

    fun calendarLabel(event: EventItem): String {
        val key = if (event.sourceKind == EventSourceKind.NOTION) "notion:${event.notionDatabaseId}" else "calendar:${event.calendarId}"
        return calendarLabels.getOrPut(key) { "C${calendarLabels.size + 1}" }
    }

    fun reset() {
        eventByLabel.clear()
        labelByEventKey.clear()
        calendarLabels.clear()
    }

    fun calendarKeyFor(label: String): String? = calendarLabels.entries.firstOrNull { it.value == label }?.key
}

object AssistantToolSpecs {
    private fun stringProp(desc: String) = buildJsonObject { put("type", "STRING"); put("description", desc) }

    val readTools: JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                putJsonArray("functionDeclarations") {
                    add(
                        buildJsonObject {
                            put("name", "searchEvents")
                            put(
                                "description",
                                "기간(과 선택적으로 키워드)으로 일정을 검색한다. 결과에는 제목이 없고 익명 라벨(E1…)·날짜·시간만 있다. " +
                                    "keyword는 사용자가 말한 단어 그대로 넣는다(앱이 로컬에서 제목과 대조한다).",
                            )
                            putJsonObject("parameters") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    put("startDate", stringProp("시작일 YYYY-MM-DD (포함)"))
                                    put("endDate", stringProp("종료일 YYYY-MM-DD (포함). 최대 92일 범위"))
                                    put("keyword", stringProp("제목/장소에 포함된 단어. 사용자가 말한 그대로. 없으면 생략"))
                                    putJsonObject("source") {
                                        put("type", "STRING")
                                        put("description", "all(기본)·calendar·notion")
                                        putJsonArray("enum") { add(kotlinx.serialization.json.JsonPrimitive("all")); add(kotlinx.serialization.json.JsonPrimitive("calendar")); add(kotlinx.serialization.json.JsonPrimitive("notion")) }
                                    }
                                    put("calendarLabel", stringProp("이전 결과에서 본 캘린더 라벨(C1…)로 한정할 때만"))
                                }
                                putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("startDate")); add(kotlinx.serialization.json.JsonPrimitive("endDate")) }
                            }
                        },
                    )
                    add(
                        buildJsonObject {
                            put("name", "getEventsOnDate")
                            put("description", "특정 하루의 일정을 모두 가져온다. 결과 형식은 searchEvents와 같다.")
                            putJsonObject("parameters") {
                                put("type", "OBJECT")
                                putJsonObject("properties") { put("date", stringProp("YYYY-MM-DD")) }
                                putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("date")) }
                            }
                        },
                    )
                    add(
                        buildJsonObject {
                            put("name", "askUserToChoose")
                            put(
                                "description",
                                "수정·삭제할 일정이 여러 후보 중 어느 것인지 확정할 수 없을 때 사용자에게 고르게 한다. 절대 임의로 고르지 말 것. " +
                                    "앱이 후보를 실제 제목으로 보여주고 사용자가 고른 ref를 돌려준다.",
                            )
                            putJsonObject("parameters") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    put("question", stringProp("사용자에게 할 짧은 질문(제목은 언급하지 말 것)"))
                                    putJsonObject("candidateRefs") {
                                        put("type", "ARRAY"); put("description", "후보 일정의 ref들(2~10개)")
                                        putJsonObject("items") { put("type", "STRING") }
                                    }
                                }
                                putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("question")); add(kotlinx.serialization.json.JsonPrimitive("candidateRefs")) }
                            }
                        },
                    )
                    // 쓰기 함수: 모델은 "제안"만 한다. 실제 실행은 앱이 사용자 확인을 받은 뒤에만 한다.
                    add(
                        buildJsonObject {
                            put("name", "createEvent")
                            put("description", "새 일정을 추가하는 제안. 실행 전에 앱이 사용자에게 확인을 받는다. 캘린더는 사용자가 카드에서 고른다.")
                            putJsonObject("parameters") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    put("title", stringProp("일정 제목. 사용자가 말한 그대로"))
                                    put("start", stringProp("시작 YYYY-MM-DDTHH:mm (종일이면 YYYY-MM-DD)"))
                                    put("end", stringProp("종료 YYYY-MM-DDTHH:mm. 말하지 않았으면 생략(1시간으로 처리)"))
                                    putJsonObject("allDay") { put("type", "BOOLEAN"); put("description", "종일 일정이면 true") }
                                    putJsonObject("reminderMinutes") {
                                        put("type", "ARRAY"); put("description", "몇 분 전에 알릴지(0=정시). 말하지 않았으면 생략")
                                        putJsonObject("items") { put("type", "INTEGER") }
                                    }
                                    putJsonObject("recurrence") {
                                        put("type", "OBJECT"); put("description", "반복 규칙. 반복이 아니면 생략")
                                        putJsonObject("properties") {
                                            putJsonObject("frequency") {
                                                put("type", "STRING")
                                                putJsonArray("enum") { listOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY").forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                                            }
                                            putJsonObject("interval") { put("type", "INTEGER"); put("description", "간격(기본 1)") }
                                            putJsonObject("byWeekdays") {
                                                put("type", "ARRAY"); put("description", "WEEKLY일 때 요일: MO TU WE TH FR SA SU")
                                                putJsonObject("items") { put("type", "STRING") }
                                            }
                                            putJsonObject("count") { put("type", "INTEGER"); put("description", "총 횟수(끝이 없으면 생략)") }
                                            put("untilDate", stringProp("종료일 YYYY-MM-DD(끝이 없으면 생략)"))
                                        }
                                        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("frequency")) }
                                    }
                                }
                                putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("title")); add(kotlinx.serialization.json.JsonPrimitive("start")) }
                            }
                        },
                    )
                    add(
                        buildJsonObject {
                            put("name", "updateEvent")
                            put("description", "기존 일정을 수정(제목·일시·알림)하는 제안. eventRef는 조회 결과의 ref. 옮기기는 newStart/newEnd로 표현한다. 실행 전에 앱이 확인을 받는다.")
                            putJsonObject("parameters") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    put("eventRef", stringProp("조회 결과의 ref(E1…)"))
                                    put("newTitle", stringProp("바꿀 제목(바꾸지 않으면 생략)"))
                                    put("newStart", stringProp("바꿀 시작 YYYY-MM-DDTHH:mm (종일이면 YYYY-MM-DD)"))
                                    put("newEnd", stringProp("바꿀 종료. 생략하면 기존 길이를 유지"))
                                    putJsonObject("reminderMinutes") {
                                        put("type", "ARRAY"); put("description", "알림 전체를 이 값으로 교체")
                                        putJsonObject("items") { put("type", "INTEGER") }
                                    }
                                    putJsonObject("scope") {
                                        put("type", "STRING"); put("description", "반복 일정일 때만: 사용자가 말했으면 THIS_ONLY(이번만)·THIS_AND_FOLLOWING(이후 전체)·ALL(전체). 말하지 않았으면 생략(앱이 묻는다)")
                                        putJsonArray("enum") { listOf("THIS_ONLY", "THIS_AND_FOLLOWING", "ALL").forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                                    }
                                }
                                putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("eventRef")) }
                            }
                        },
                    )
                    add(
                        buildJsonObject {
                            put("name", "deleteEvent")
                            put("description", "일정을 삭제하는 제안. 실행 전에 앱이 확인을 받는다.")
                            putJsonObject("parameters") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    put("eventRef", stringProp("조회 결과의 ref(E1…)"))
                                    putJsonObject("scope") {
                                        put("type", "STRING"); put("description", "반복 일정일 때만. 사용자가 말했으면 지정, 아니면 생략(앱이 묻는다)")
                                        putJsonArray("enum") { listOf("THIS_ONLY", "THIS_AND_FOLLOWING", "ALL").forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                                    }
                                }
                                putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("eventRef")) }
                            }
                        },
                    )
                }
            },
        )
    }

    val tools: JsonArray get() = readTools

    /** 쓰기 제안으로 취급하는 함수 이름들. */
    val writeNames = setOf("createEvent", "updateEvent", "deleteEvent")

    const val CHOOSE = "askUserToChoose"
}

/** 읽기 함수 실행기. 제목 대조(키워드)는 여기서 로컬로 하고, 모델에 돌려주는 JSON에는 제목이 없다. */
class ReadToolExecutor(private val data: AssistantDataSource, private val labels: LabelBook) {
    private val zone: ZoneId = ZoneId.systemDefault()

    /** 이번 턴에서 마지막으로 실행한 검색의 실제 일정들 — 앱이 실제 제목으로 화면에 그린다. */
    var lastShown: List<EventItem> = emptyList()
        private set

    fun resetTurn() {
        lastShown = emptyList()
    }

    suspend fun execute(name: String, args: JsonObject): JsonObject = run(name, args).also { result ->
        // 검증용 로컬 로그: 어떤 도구가 어떤 인자로 불렸고 몇 건이 나갔는지만 남긴다(일정 제목/결과 본문은 남기지 않음).
        // 감사: 모델로 나가는 결과 JSON에 방금 조회한 일정의 제목/장소가 문자열로 섞여 있지 않은지 확인한다.
        if (!com.jongsun.runcal.BuildConfig.DEBUG) return@also
        val outgoing = result.toString()
        val leaked = lastShown.count { e ->
            (e.title.length >= 3 && outgoing.contains(e.title, ignoreCase = true)) ||
                (e.location.length >= 3 && outgoing.contains(e.location, ignoreCase = true))
        }
        android.util.Log.d("RunCal", "AssistantTool: $name args=$args -> count=${result["count"]} error=${result["error"]} titleLeaks=$leaked")
    }

    private suspend fun run(name: String, args: JsonObject): JsonObject = when (name) {
        "searchEvents" -> search(
            start = args["startDate"].asStringOrNull(),
            end = args["endDate"].asStringOrNull(),
            keyword = args["keyword"].asStringOrNull(),
            source = args["source"].asStringOrNull() ?: "all",
            calendarLabel = args["calendarLabel"].asStringOrNull(),
        )
        "getEventsOnDate" -> {
            val d = args["date"].asStringOrNull()
            search(d, d, null, "all", null)
        }
        else -> error("알 수 없는 함수입니다: $name")
    }

    private suspend fun search(start: String?, end: String?, keyword: String?, source: String, calendarLabel: String?): JsonObject {
        val startDate = runCatching { LocalDate.parse(start) }.getOrNull() ?: return error("startDate는 YYYY-MM-DD 형식이어야 합니다")
        val endDate = runCatching { LocalDate.parse(end) }.getOrNull() ?: return error("endDate는 YYYY-MM-DD 형식이어야 합니다")
        if (endDate < startDate) return error("endDate가 startDate보다 빠릅니다")
        if (ChronoUnit.DAYS.between(startDate, endDate) > MAX_RANGE_DAYS) return error("기간은 최대 ${MAX_RANGE_DAYS}일까지입니다")

        val startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val tokens = keyword?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()

        var events = data.eventsInRange(startMillis, endMillis)
        // 화면 조회는 범위와 겹치는 일정을 모두 주므로 실제로 이 기간의 날짜를 지나는 것만 남긴다.
        events = events.filter { e ->
            val range = e.dateRange(zone)
            range.endInclusive >= startDate && range.start <= endDate
        }
        events = when (source) {
            "calendar" -> events.filter { it.sourceKind == EventSourceKind.CALENDAR }
            "notion" -> events.filter { it.sourceKind == EventSourceKind.NOTION }
            else -> events
        }
        if (tokens.isNotEmpty()) {
            events = events.filter { e -> tokens.all { t -> e.title.contains(t, ignoreCase = true) || e.location.contains(t, ignoreCase = true) } }
        }
        if (calendarLabel != null) {
            val wanted = labels.calendarKeyFor(calendarLabel)
            events = if (wanted == null) emptyList() else events.filter { e ->
                val key = if (e.sourceKind == EventSourceKind.NOTION) "notion:${e.notionDatabaseId}" else "calendar:${e.calendarId}"
                key == wanted
            }
        }
        events = events.sortedBy { it.begin }
        val truncated = events.size > MAX_RESULTS
        val shown = events.take(MAX_RESULTS)
        lastShown = shown

        return buildJsonObject {
            put("count", events.size)
            put("truncated", truncated)
            putJsonArray("events") {
                shown.forEach { e ->
                    val range = e.dateRange(zone)
                    add(
                        buildJsonObject {
                            put("ref", labels.eventLabel(e))
                            put("date", range.start.toString())
                            if (range.endInclusive != range.start) put("endDate", range.endInclusive.toString())
                            put("allDay", e.allDay)
                            if (!e.allDay) {
                                put("start", Instant.ofEpochMilli(e.begin).atZone(zone).toLocalTime().toString().take(5))
                                put("end", Instant.ofEpochMilli(e.end).atZone(zone).toLocalTime().toString().take(5))
                            }
                            put("calendar", labels.calendarLabel(e))
                            put("source", if (e.sourceKind == EventSourceKind.NOTION) "notion" else "calendar")
                            put("recurring", !e.rrule.isNullOrBlank())
                            put("readOnly", e.sourceKind == EventSourceKind.NOTION)
                        },
                    )
                }
            }
            if (truncated) put("note", "결과가 많아 처음 ${MAX_RESULTS}건만 보냈습니다. 기간이나 키워드를 좁히세요.")
        }
    }

    private fun error(message: String) = buildJsonObject { put("error", message) }
}
