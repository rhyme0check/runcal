package com.jongsun.runcal.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class RecurrenceFrequency { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

enum class MonthlyRecurrenceType { BY_DAY_OF_MONTH, BY_WEEKDAY_ORDINAL }

enum class RecurrenceEndType { NEVER, COUNT, UNTIL }

/** 반복 생성 UI의 상태. [toRRuleString]/[parseRRule]로 RFC5545 RRULE 문자열과 상호 변환한다. */
data class RecurrenceRule(
    val frequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
    val interval: Int = 1,
    val byWeekdays: Set<DayOfWeek> = emptySet(),
    val monthlyType: MonthlyRecurrenceType = MonthlyRecurrenceType.BY_DAY_OF_MONTH,
    val endType: RecurrenceEndType = RecurrenceEndType.NEVER,
    val count: Int = 10,
    val untilDate: LocalDate? = null,
)

private val WEEKDAY_CODES = mapOf(
    DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
    DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
)
private val CODE_TO_WEEKDAY = WEEKDAY_CODES.entries.associate { (day, code) -> code to day }
private val UNTIL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

/**
 * [date]가 그 달에서 몇 번째 같은 요일인지 계산한다("매월 셋째 화요일" 라벨/RRULE의 BYDAY 순번에 쓴다).
 * 그 달의 마지막 해당 요일이면 -1(RFC5545의 "마지막"을 뜻하는 음수 순번)을 돌려준다.
 */
fun weekdayOrdinalInMonth(date: LocalDate): Int {
    val ordinal = ((date.dayOfMonth - 1) / 7) + 1
    val isLast = date.dayOfMonth + 7 > date.lengthOfMonth()
    return if (isLast) -1 else ordinal
}

/** [startDate]를 기준(DTSTART)으로 이 규칙을 RFC5545 RRULE 문자열로 만든다. NONE이면 null(반복 아님). */
fun RecurrenceRule.toRRuleString(startDate: LocalDate): String? {
    val freqCode = when (frequency) {
        RecurrenceFrequency.NONE -> return null
        RecurrenceFrequency.DAILY -> "DAILY"
        RecurrenceFrequency.WEEKLY -> "WEEKLY"
        RecurrenceFrequency.MONTHLY -> "MONTHLY"
        RecurrenceFrequency.YEARLY -> "YEARLY"
    }
    val parts = mutableListOf("FREQ=$freqCode")
    if (interval > 1) parts += "INTERVAL=$interval"

    when (frequency) {
        RecurrenceFrequency.WEEKLY -> {
            val days = byWeekdays.ifEmpty { setOf(startDate.dayOfWeek) }
                .sortedBy { it.value }
                .joinToString(",") { WEEKDAY_CODES.getValue(it) }
            parts += "BYDAY=$days"
        }
        RecurrenceFrequency.MONTHLY -> {
            if (monthlyType == MonthlyRecurrenceType.BY_DAY_OF_MONTH) {
                parts += "BYMONTHDAY=${startDate.dayOfMonth}"
            } else {
                val ordinal = weekdayOrdinalInMonth(startDate)
                parts += "BYDAY=$ordinal${WEEKDAY_CODES.getValue(startDate.dayOfWeek)}"
            }
        }
        else -> {}
    }

    when (endType) {
        RecurrenceEndType.COUNT -> parts += "COUNT=${count.coerceAtLeast(1)}"
        RecurrenceEndType.UNTIL -> untilDate?.let { parts += "UNTIL=${it.format(UNTIL_DATE_FORMAT)}" }
        RecurrenceEndType.NEVER -> {}
    }
    return parts.joinToString(";")
}

/**
 * [toRRuleString]의 역변환. BYMONTHDAY/BYDAY 순번 등 필요한 정보가 모두 RRULE 문자열 자체에
 * 들어있어 기준 날짜 없이도 복원할 수 있다. 파싱할 수 없거나 비어 있으면 기본값(NONE)을 돌려준다.
 */
fun parseRRule(rrule: String?): RecurrenceRule {
    if (rrule.isNullOrBlank()) return RecurrenceRule()
    val fields = rrule.split(";").mapNotNull { part ->
        val idx = part.indexOf('=')
        if (idx < 0) null else part.substring(0, idx) to part.substring(idx + 1)
    }.toMap()

    val frequency = when (fields["FREQ"]) {
        "DAILY" -> RecurrenceFrequency.DAILY
        "WEEKLY" -> RecurrenceFrequency.WEEKLY
        "MONTHLY" -> RecurrenceFrequency.MONTHLY
        "YEARLY" -> RecurrenceFrequency.YEARLY
        else -> return RecurrenceRule()
    }
    val interval = fields["INTERVAL"]?.toIntOrNull() ?: 1
    val byDayRaw = fields["BYDAY"]
    val byWeekdays = if (frequency == RecurrenceFrequency.WEEKLY && byDayRaw != null) {
        byDayRaw.split(",").mapNotNull { CODE_TO_WEEKDAY[it.takeLast(2)] }.toSet()
    } else {
        emptySet()
    }
    val monthlyType = if (frequency == RecurrenceFrequency.MONTHLY && byDayRaw != null) {
        MonthlyRecurrenceType.BY_WEEKDAY_ORDINAL
    } else {
        MonthlyRecurrenceType.BY_DAY_OF_MONTH
    }

    var endType = RecurrenceEndType.NEVER
    var count = 10
    var untilDate: LocalDate? = null
    when {
        fields["COUNT"] != null -> {
            endType = RecurrenceEndType.COUNT
            count = fields.getValue("COUNT").toIntOrNull() ?: 10
        }
        fields["UNTIL"] != null -> {
            endType = RecurrenceEndType.UNTIL
            untilDate = runCatching { LocalDate.parse(fields.getValue("UNTIL").take(8), UNTIL_DATE_FORMAT) }.getOrNull()
        }
    }
    return RecurrenceRule(
        frequency = frequency,
        interval = interval,
        byWeekdays = byWeekdays,
        monthlyType = monthlyType,
        endType = endType,
        count = count,
        untilDate = untilDate,
    )
}
