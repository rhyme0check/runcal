package com.jongsun.runcal.ai

import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.RecurrenceEndType
import com.jongsun.runcal.data.RecurrenceFrequency
import com.jongsun.runcal.data.RecurrenceRule
import com.jongsun.runcal.data.dateRange
import com.jongsun.runcal.data.source.EventSourceKind
import com.jongsun.runcal.data.toRRuleString
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** 반복 일정에 적용할 범위. 편집 화면의 이번만/이후 전체/전체와 같은 의미. */
enum class AiScope { THIS_ONLY, THIS_AND_FOLLOWING, ALL }

enum class ProposalKind { CREATE, UPDATE, DELETE }

/**
 * 모델이 "제안"한 쓰기 하나. 실행은 사용자가 확인 카드에서 [실행]을 누른 뒤에만 앱 코드가 한다.
 * [calendarId]/[scope]는 카드에서 사용자가 바꿀 수 있다(캘린더 이름은 모델에 보이지 않으므로 카드에서 고른다).
 */
data class ProposalItem(
    val callKey: String,
    val kind: ProposalKind,
    /** 수정·삭제 대상(탭한 그 회차). 생성이면 null. */
    val target: EventItem?,
    val title: String,
    val allDay: Boolean,
    val startMillis: Long,
    val endMillis: Long,
    val reminderMinutes: List<Int>?,
    val rrule: String?,
    val recurrenceLabel: String?,
    val calendarId: Long?,
    val scope: AiScope?,
    /** 수정에서 바뀌는 항목을 사람이 읽을 문장으로(변경 전 → 후). 앱이 실제 값으로 만든다. */
    val changeLines: List<String> = emptyList(),
)

/** 쓰기 호출 하나를 검증해 만든 결과. 제안이거나, 모델에게 되돌릴 오류/사용자에게 보일 안내. */
sealed interface ProposalOutcome {
    data class Ok(val item: ProposalItem) : ProposalOutcome

    /** 모델의 실수(알 수 없는 ref, 형식 오류 등) — 모델에게 오류로 돌려주고 다시 시도하게 한다. */
    data class ModelError(val callKey: String, val message: String) : ProposalOutcome

    /** 정책상 불가(Notion 읽기 전용 등) — 사용자에게 앱이 직접 안내한다. */
    data class Blocked(val callKey: String, val userMessage: String) : ProposalOutcome
}

private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 쓰기 함수 호출을 검증하고 [ProposalItem]으로 바꾼다. 여기서 실제 일정 제목·시각으로 "변경 전 → 후" 문장을 만든다.
 * 이 클래스는 아무것도 쓰지 않는다 — 검증과 문장 만들기만 한다.
 */
class ProposalBuilder(private val data: AssistantDataSource, private val labels: LabelBook) {
    private val zone: ZoneId = ZoneId.systemDefault()

    fun build(callKey: String, name: String, args: JsonObject): ProposalOutcome = when (name) {
        "createEvent" -> create(callKey, args)
        "updateEvent" -> update(callKey, args)
        "deleteEvent" -> delete(callKey, args)
        else -> ProposalOutcome.ModelError(callKey, "알 수 없는 함수입니다: $name")
    }

    private fun create(callKey: String, args: JsonObject): ProposalOutcome {
        val title = args.str("title") ?: return ProposalOutcome.ModelError(callKey, "title이 필요합니다")
        val allDay = (args["allDay"] as? JsonPrimitive)?.booleanOrNull ?: false
        val startText = args.str("start") ?: return ProposalOutcome.ModelError(callKey, "start가 필요합니다")
        val (startMillis, endMillis) = resolveTimes(allDay, startText, args.str("end"), fallbackStart = null, fallbackEnd = null)
            ?: return ProposalOutcome.ModelError(callKey, "start/end 형식이 올바르지 않습니다(YYYY-MM-DDTHH:mm, 종일이면 YYYY-MM-DD)")
        if (endMillis < startMillis) return ProposalOutcome.ModelError(callKey, "end가 start보다 빠릅니다")

        val startDate = startLocalDate(allDay, startMillis)
        val rule = (args["recurrence"] as? JsonObject)?.let { parseRecurrence(it, startDate) }
        val rrule = rule?.toRRuleString(startDate)
        val writable = data.calendars().filter { it.isWritable }
        val calendarId = writable.firstOrNull()?.id
            ?: return ProposalOutcome.Blocked(callKey, "일정을 추가할 수 있는(쓰기 가능한) 캘린더가 없습니다.")
        val reminders = (args["reminderMinutes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.distinct()?.take(5)
        return ProposalOutcome.Ok(
            ProposalItem(
                callKey = callKey, kind = ProposalKind.CREATE, target = null, title = title, allDay = allDay,
                startMillis = startMillis, endMillis = endMillis,
                reminderMinutes = reminders ?: data.defaultReminderMinutes(),
                rrule = rrule, recurrenceLabel = rule?.let { recurrenceLabel(it, startDate) },
                calendarId = calendarId, scope = null,
            ),
        )
    }

    private fun update(callKey: String, args: JsonObject): ProposalOutcome {
        val target = resolveTarget(callKey, args) ?: return targetProblem
        blocked(callKey, target)?.let { return it }
        val recurring = !target.rrule.isNullOrBlank()
        val scope = parseScope(args.str("scope"))

        val newTitle = args.str("newTitle")
        val newStartText = args.str("newStart")
        val newEndText = args.str("newEnd")
        val newReminders = (args["reminderMinutes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.distinct()?.take(5)
        if (newTitle == null && newStartText == null && newEndText == null && newReminders == null) {
            return ProposalOutcome.ModelError(callKey, "바꿀 항목(newTitle/newStart/newEnd/reminderMinutes)이 하나도 없습니다")
        }

        val resolved = if (newStartText != null || newEndText != null) {
            resolveTimes(target.allDay, newStartText ?: fmt(target.allDay, target.begin), newEndText, fallbackStart = target.begin, fallbackEnd = target.end, keepDuration = newEndText == null)
                ?: return ProposalOutcome.ModelError(callKey, "newStart/newEnd 형식이 올바르지 않습니다")
        } else {
            target.begin to target.end
        }
        if (resolved.second < resolved.first) return ProposalOutcome.ModelError(callKey, "newEnd가 newStart보다 빠릅니다")

        val lines = ArrayList<String>()
        val finalTitle = newTitle ?: target.title
        if (newTitle != null && newTitle != target.title) lines += "제목: '${target.title}' → '$newTitle'"
        if (resolved.first != target.begin || resolved.second != target.end) {
            lines += "일시: ${describeRange(target.allDay, target.begin, target.end)} → ${describeRange(target.allDay, resolved.first, resolved.second)}"
        }
        if (newReminders != null) lines += "알림: ${reminderText(newReminders)}로 변경"
        if (lines.isEmpty()) return ProposalOutcome.ModelError(callKey, "현재 값과 같아서 바뀌는 것이 없습니다")

        return ProposalOutcome.Ok(
            ProposalItem(
                callKey = callKey, kind = ProposalKind.UPDATE, target = target, title = finalTitle, allDay = target.allDay,
                startMillis = resolved.first, endMillis = resolved.second, reminderMinutes = newReminders,
                rrule = target.rrule, recurrenceLabel = null, calendarId = target.calendarId,
                scope = if (recurring) scope else null, changeLines = lines,
            ),
        )
    }

    private fun delete(callKey: String, args: JsonObject): ProposalOutcome {
        val target = resolveTarget(callKey, args) ?: return targetProblem
        blocked(callKey, target)?.let { return it }
        val recurring = !target.rrule.isNullOrBlank()
        return ProposalOutcome.Ok(
            ProposalItem(
                callKey = callKey, kind = ProposalKind.DELETE, target = target, title = target.title, allDay = target.allDay,
                startMillis = target.begin, endMillis = target.end, reminderMinutes = null, rrule = target.rrule,
                recurrenceLabel = null, calendarId = target.calendarId,
                scope = if (recurring) parseScope(args.str("scope")) else null,
            ),
        )
    }

    private var targetProblem: ProposalOutcome = ProposalOutcome.ModelError("", "")

    private fun resolveTarget(callKey: String, args: JsonObject): EventItem? {
        val ref = args.str("eventRef")
        val event = ref?.let { labels.event(it) }
        if (event == null) {
            targetProblem = ProposalOutcome.ModelError(callKey, "알 수 없는 eventRef입니다. 먼저 searchEvents/getEventsOnDate로 조회한 결과의 ref만 쓸 수 있습니다.")
            return null
        }
        return event
    }

    /** Notion 항목·읽기 전용 캘린더는 수정/삭제할 수 없다 — 사용자에게 앱이 직접 안내한다(실제 제목은 화면에만 나온다). */
    private fun blocked(callKey: String, target: EventItem): ProposalOutcome.Blocked? {
        if (target.sourceKind == EventSourceKind.NOTION) {
            return ProposalOutcome.Blocked(callKey, "'${target.title}'은(는) Notion 항목이라 읽기만 가능합니다. 수정·삭제할 수 없어요.")
        }
        val calendar = data.calendars().firstOrNull { it.id == target.calendarId }
        if (calendar != null && !calendar.isWritable) {
            return ProposalOutcome.Blocked(callKey, "'${target.title}'이(가) 속한 캘린더(${calendar.displayName})는 읽기 전용이라 바꿀 수 없어요.")
        }
        return null
    }

    private fun parseScope(text: String?): AiScope? = when (text?.uppercase()) {
        "THIS_ONLY" -> AiScope.THIS_ONLY
        "THIS_AND_FOLLOWING" -> AiScope.THIS_AND_FOLLOWING
        "ALL" -> AiScope.ALL
        else -> null
    }

    private fun startLocalDate(allDay: Boolean, startMillis: Long): LocalDate =
        Instant.ofEpochMilli(startMillis).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()

    /** 종일 일정은 UTC 자정 기준(편집 화면과 같은 규칙), 시간 지정은 로컬 시간대 기준. */
    private fun resolveTimes(
        allDay: Boolean,
        startText: String,
        endText: String?,
        fallbackStart: Long?,
        fallbackEnd: Long?,
        keepDuration: Boolean = false,
    ): Pair<Long, Long>? {
        if (allDay) {
            val start = runCatching { LocalDate.parse(startText.take(10)) }.getOrNull() ?: return null
            val end = endText?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() ?: return null } ?: start
            return start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to
                end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val start = parseDateTime(startText) ?: return null
        val startMillis = start.atZone(zone).toInstant().toEpochMilli()
        val endMillis = when {
            endText != null -> (parseDateTime(endText) ?: return null).atZone(zone).toInstant().toEpochMilli()
            keepDuration && fallbackStart != null && fallbackEnd != null -> startMillis + (fallbackEnd - fallbackStart)
            else -> startMillis + 60 * 60 * 1000L // 종료가 없으면 1시간
        }
        return startMillis to endMillis
    }

    private fun parseDateTime(text: String): LocalDateTime? {
        val t = text.trim()
        return runCatching { LocalDateTime.parse(t) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t.replace(' ', 'T')) }.getOrNull()
            ?: runCatching { LocalDate.parse(t).atTime(LocalTime.of(9, 0)) }.getOrNull()
    }

    private fun fmt(allDay: Boolean, millis: Long): String =
        if (allDay) startLocalDate(true, millis).toString() else Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime().withSecond(0).withNano(0).toString()

    private fun parseRecurrence(obj: JsonObject, startDate: LocalDate): RecurrenceRule? {
        val freq = when (obj.str("frequency")?.uppercase()) {
            "DAILY" -> RecurrenceFrequency.DAILY
            "WEEKLY" -> RecurrenceFrequency.WEEKLY
            "MONTHLY" -> RecurrenceFrequency.MONTHLY
            "YEARLY" -> RecurrenceFrequency.YEARLY
            else -> return null
        }
        val days = (obj["byWeekdays"] as? JsonArray)?.mapNotNull { weekdayOf((it as? JsonPrimitive)?.content) }?.toSet().orEmpty()
        val count = (obj["count"] as? JsonPrimitive)?.intOrNull
        val until = obj.str("untilDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return RecurrenceRule(
            frequency = freq,
            interval = ((obj["interval"] as? JsonPrimitive)?.intOrNull ?: 1).coerceAtLeast(1),
            byWeekdays = if (freq == RecurrenceFrequency.WEEKLY) days.ifEmpty { setOf(startDate.dayOfWeek) } else emptySet(),
            endType = when {
                count != null -> RecurrenceEndType.COUNT
                until != null -> RecurrenceEndType.UNTIL
                else -> RecurrenceEndType.NEVER
            },
            count = count ?: 10,
            untilDate = until,
        )
    }

    private fun weekdayOf(code: String?): DayOfWeek? = when (code?.uppercase()) {
        "MO" -> DayOfWeek.MONDAY; "TU" -> DayOfWeek.TUESDAY; "WE" -> DayOfWeek.WEDNESDAY; "TH" -> DayOfWeek.THURSDAY
        "FR" -> DayOfWeek.FRIDAY; "SA" -> DayOfWeek.SATURDAY; "SU" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun recurrenceLabel(rule: RecurrenceRule, startDate: LocalDate): String {
        val every = if (rule.interval > 1) "${rule.interval}" else ""
        val base = when (rule.frequency) {
            RecurrenceFrequency.DAILY -> if (every.isEmpty()) "매일" else "${every}일마다"
            RecurrenceFrequency.WEEKLY -> {
                val days = rule.byWeekdays.sortedBy { it.value }.joinToString("·") { it.getDisplayName(TextStyle.FULL, Locale.KOREAN) }
                (if (every.isEmpty()) "매주 " else "${every}주마다 ") + days
            }
            RecurrenceFrequency.MONTHLY -> if (every.isEmpty()) "매월 ${startDate.dayOfMonth}일" else "${every}개월마다"
            RecurrenceFrequency.YEARLY -> if (every.isEmpty()) "매년" else "${every}년마다"
            RecurrenceFrequency.NONE -> "반복 안 함"
        }
        return base + when (rule.endType) {
            RecurrenceEndType.COUNT -> " (${rule.count}회)"
            RecurrenceEndType.UNTIL -> " (${rule.untilDate}까지)"
            RecurrenceEndType.NEVER -> " (종료 없음)"
        }
    }

    fun describeRange(allDay: Boolean, begin: Long, end: Long): String {
        val s = Instant.ofEpochMilli(begin).atZone(if (allDay) ZoneOffset.UTC else zone)
        val wd = s.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
        val head = "${s.monthValue}/${s.dayOfMonth}($wd)"
        if (allDay) {
            val last = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
            val first = s.toLocalDate()
            return if (last > first) "$head 종일 ~ ${last.monthValue}/${last.dayOfMonth}" else "$head 종일"
        }
        val e = Instant.ofEpochMilli(end).atZone(zone)
        val time = "%02d:%02d–%02d:%02d".format(s.hour, s.minute, e.hour, e.minute)
        return "$head $time"
    }

    fun reminderText(minutes: List<Int>): String =
        if (minutes.isEmpty()) "없음" else minutes.joinToString(", ") { m -> if (m == 0) "정시" else if (m < 60) "${m}분 전" else if (m < 1440) "${m / 60}시간 전" else "${m / 1440}일 전" }
}

/** 삭제 카드에 쓸 영향 범위 설명용 — 대상이 지나는 날짜(사람이 읽는 문장은 화면 쪽에서). */
fun EventItem.firstDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate = dateRange(zone).start

private val DESCRIBE_ZONE: ZoneId get() = ZoneId.systemDefault()

/** "9/25(금) 19:00–20:00" 또는 "9/25(금) 종일" 형식의 사람이 읽는 일시 문구(종일은 UTC 기준으로 날짜를 읽는다). */
fun describeEventRange(allDay: Boolean, begin: Long, end: Long): String {
    val s = Instant.ofEpochMilli(begin).atZone(if (allDay) ZoneOffset.UTC else DESCRIBE_ZONE)
    val wd = s.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
    val head = "${s.monthValue}/${s.dayOfMonth}($wd)"
    if (allDay) {
        val last = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        val first = s.toLocalDate()
        return if (last > first) "$head 종일 ~ ${last.monthValue}/${last.dayOfMonth}" else "$head 종일"
    }
    val e = Instant.ofEpochMilli(end).atZone(DESCRIBE_ZONE)
    return "$head " + "%02d:%02d–%02d:%02d".format(s.hour, s.minute, e.hour, e.minute)
}

fun describeReminders(minutes: List<Int>): String =
    if (minutes.isEmpty()) "없음" else minutes.joinToString(", ") { m -> if (m == 0) "정시" else if (m < 60) "${m}분 전" else if (m < 1440) "${m / 60}시간 전" else "${m / 1440}일 전" }
