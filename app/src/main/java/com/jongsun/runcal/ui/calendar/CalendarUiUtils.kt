package com.jongsun.runcal.ui.calendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val KOREAN_WEEKDAY_SHORT = mapOf(
    DayOfWeek.SUNDAY to "일",
    DayOfWeek.MONDAY to "월",
    DayOfWeek.TUESDAY to "화",
    DayOfWeek.WEDNESDAY to "수",
    DayOfWeek.THURSDAY to "목",
    DayOfWeek.FRIDAY to "금",
    DayOfWeek.SATURDAY to "토",
)

fun DayOfWeek.koreanShortLabel(): String = KOREAN_WEEKDAY_SHORT.getValue(this)

/** [weekStartDay]부터 시작하는 요일 순서(7개)를 반환한다. */
fun weekdayOrder(weekStartDay: DayOfWeek): List<DayOfWeek> =
    (0 until DAYS_IN_WEEK).map { weekStartDay.plus(it.toLong()) }

/** 일요일 빨강 / 토요일 파랑 / 평일은 테마 기본 색상. */
@Composable
@ReadOnlyComposable
fun dayOfWeekColor(dayOfWeek: DayOfWeek, dimmed: Boolean = false): Color {
    val base = when (dayOfWeek) {
        DayOfWeek.SUNDAY -> Color(0xFFD32F2F)
        DayOfWeek.SATURDAY -> Color(0xFF1565C0)
        else -> MaterialTheme.colorScheme.onSurface
    }
    return if (dimmed) base.copy(alpha = 0.38f) else base
}

fun YearMonth.titleKorean(): String = "${year}년 ${monthValue}월"

fun LocalDate.titleKorean(): String {
    val weekday = dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
    return "${year}년 ${monthValue}월 ${dayOfMonth}일 ($weekday)"
}

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun LocalTime.toTimeLabel(): String = format(TIME_FORMATTER)
