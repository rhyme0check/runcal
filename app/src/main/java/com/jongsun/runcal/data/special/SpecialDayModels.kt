package com.jongsun.runcal.data.special

import android.content.Context
import com.jongsun.runcal.data.EventItem
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** 공휴일/절기 이름 막대용 가짜 이벤트의 calendarId. 진짜 캘린더 id(양수)와 절대 겹치지 않는다. */
const val SPECIAL_CALENDAR_ID = -100L

private const val HOLIDAY_BAR_COLOR = 0xFFD32F2F.toInt()
private const val SOLAR_TERM_BAR_COLOR = 0xFF2E7D32.toInt()

/** 음력 날짜. [isLeap]이면 윤달. */
data class LunarDate(val month: Int, val day: Int, val isLeap: Boolean) {
    /** 월간 그리드 칸용 짧은 표기: "8.15", 윤달은 "윤8.15". */
    fun shortLabel(): String = (if (isLeap) "윤" else "") + "$month.$day"
}

/**
 * Room 캐시 전체를 메모리로 올린 읽기 전용 스냅샷. 위젯 렌더링/앱 화면이 이것만 읽는다.
 * [fetched]에 없는 연/월은 "아직 못 받음"이며, 이때 그 날짜들은 조용히 빈 값으로 렌더링된다.
 */
data class SpecialDaySnapshot(
    val holidays: Map<LocalDate, List<String>> = emptyMap(),
    val solarTerms: Map<LocalDate, String> = emptyMap(),
    val lunar: Map<LocalDate, LunarDate> = emptyMap(),
    val fetched: Map<String, Long> = emptyMap(),
) {
    fun isHoliday(date: LocalDate): Boolean = holidays.containsKey(date)

    /** [start, endExclusive) 범위의 공휴일/절기 이름을 "종일 1일짜리 일정"처럼 만든다. 그리드가 일정 막대와 똑같이 그린다. */
    fun specialEvents(start: LocalDate, endExclusive: LocalDate, flags: SpecialDayFlags): List<EventItem> {
        if (!flags.showHolidays && !flags.showSolarTerms) return emptyList()
        val result = ArrayList<EventItem>()
        var date = start
        while (date < endExclusive) {
            var index = 0
            if (flags.showHolidays) {
                holidays[date]?.forEach { name -> result += specialEvent(date, name, HOLIDAY_BAR_COLOR, index++) }
            }
            if (flags.showSolarTerms) {
                solarTerms[date]?.let { name -> result += specialEvent(date, name, SOLAR_TERM_BAR_COLOR, index++) }
            }
            date = date.plusDays(1)
        }
        return result
    }

    private fun specialEvent(date: LocalDate, name: String, color: Int, index: Int): EventItem {
        val beginMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() // 종일 일정은 UTC 자정 기준
        return EventItem(
            id = -(date.toEpochDay() * 100 + index + 1),
            calendarId = SPECIAL_CALENDAR_ID,
            title = name,
            begin = beginMillis,
            end = beginMillis + 24 * 60 * 60 * 1000L,
            allDay = true,
            color = color,
        )
    }

    /**
     * 월간 그리드 칸에 붙일 음력 표식. 삭(음력 1일)=그 달 "N월", 망(15일)="보름", 그믐(말일)="그믐"만 표시하고
     * 나머지 날은 null. 음력 달은 29일 또는 30일이라 말일은 고정값이 아니라 "다음 날의 음력이 1일인지"로 판단한다
     * (30일은 항상 말일). 다음 날 음력이 아직 캐시에 없으면 29일은 판단을 보류하고 표시하지 않는다.
     */
    fun lunarMarker(date: LocalDate): String? {
        val l = lunar[date] ?: return null
        return when {
            l.day == 1 -> (if (l.isLeap) "윤" else "") + "${l.month}월"
            l.day == 15 -> "보름"
            l.day == 30 -> "그믐"
            l.day == 29 && lunar[date.plusDays(1)]?.day == 1 -> "그믐"
            else -> null
        }
    }

    /** 날짜 하나에 붙일 부가 설명("추석 · 음력 8.15 · 절기 이름"). 없으면 null. */
    fun describe(date: LocalDate, flags: SpecialDayFlags): String? {
        val parts = ArrayList<String>()
        if (flags.showHolidays) holidays[date]?.let { parts += it }
        if (flags.showSolarTerms) solarTerms[date]?.let { parts += it }
        if (flags.showLunar) lunar[date]?.let { parts += "음력 ${it.shortLabel()}" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}

fun EventItem.isSpecialDay(): Boolean = calendarId == SPECIAL_CALENDAR_ID

/** 음력/공휴일/절기 표시 on/off. 기본값은 전부 꺼짐 — 칸 공간을 잡아먹기 때문. */
data class SpecialDayFlags(
    val showLunar: Boolean = false,
    val showHolidays: Boolean = false,
    val showSolarTerms: Boolean = false,
) {
    val anyEnabled: Boolean get() = showLunar || showHolidays || showSolarTerms
}

/**
 * 표시 설정 저장소. 위젯이 렌더링 경로에서 동기로 읽어야 해서 DataStore(비동기) 대신 SharedPreferences를
 * 쓰고, 프로세스 메모리에 올려둔다.
 */
object SpecialDayPrefs {
    private const val NAME = "special_day_prefs"
    private const val KEY_LUNAR = "show_lunar"
    private const val KEY_HOLIDAYS = "show_holidays"
    private const val KEY_TERMS = "show_solar_terms"

    @Volatile private var cached: SpecialDayFlags? = null

    fun load(context: Context): SpecialDayFlags = cached ?: synchronized(this) {
        cached ?: context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).let { prefs ->
            SpecialDayFlags(
                showLunar = prefs.getBoolean(KEY_LUNAR, false),
                showHolidays = prefs.getBoolean(KEY_HOLIDAYS, false),
                showSolarTerms = prefs.getBoolean(KEY_TERMS, false),
            )
        }.also { cached = it }
    }

    fun save(context: Context, flags: SpecialDayFlags) {
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LUNAR, flags.showLunar)
            .putBoolean(KEY_HOLIDAYS, flags.showHolidays)
            .putBoolean(KEY_TERMS, flags.showSolarTerms)
            .apply()
        cached = flags
    }
}

internal fun holidayKey(year: Int) = "holiday:$year"
internal fun termKey(year: Int) = "term:$year"
internal fun lunarKey(yearMonth: YearMonth) = "lunar:$yearMonth"
