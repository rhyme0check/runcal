package com.jongsun.runcal.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

object WidgetPreferenceKeys {
    val SELECTED_CALENDAR_IDS = stringSetPreferencesKey("selected_calendar_ids")
    val FONT_SCALE_STEP = intPreferencesKey("font_scale_step")
    val BACKGROUND_OPACITY = floatPreferencesKey("background_opacity")
}

const val MIN_FONT_SCALE_STEP = 1
const val MAX_FONT_SCALE_STEP = 5
const val DEFAULT_FONT_SCALE_STEP = 3
const val DEFAULT_BACKGROUND_OPACITY = 0.7f

private val DAY_BASE_BACKGROUND_COLOR = Color(0xFFFFFFFF)
private val NIGHT_BASE_BACKGROUND_COLOR = Color(0xFF1C1B1F)

/**
 * 위젯 인스턴스별 필터/표시 설정.
 * [selectedCalendarIds]가 null이면 "설정 없음 = 전체 캘린더 표시" 기본값을 의미하고,
 * 빈 Set이면 사용자가 캘린더를 전부 해제한 상태(아무 일정도 표시하지 않음)를 의미한다.
 */
data class WidgetFilterSettings(
    val selectedCalendarIds: Set<Long>? = null,
    val fontScaleStep: Int = DEFAULT_FONT_SCALE_STEP,
    val backgroundOpacity: Float = DEFAULT_BACKGROUND_OPACITY,
)

fun Preferences.toWidgetFilterSettings(): WidgetFilterSettings = WidgetFilterSettings(
    selectedCalendarIds = this[WidgetPreferenceKeys.SELECTED_CALENDAR_IDS]
        ?.mapNotNull { it.toLongOrNull() }
        ?.toSet(),
    fontScaleStep = this[WidgetPreferenceKeys.FONT_SCALE_STEP] ?: DEFAULT_FONT_SCALE_STEP,
    backgroundOpacity = this[WidgetPreferenceKeys.BACKGROUND_OPACITY] ?: DEFAULT_BACKGROUND_OPACITY,
)

fun MutablePreferences.applyWidgetFilterSettings(settings: WidgetFilterSettings) {
    this[WidgetPreferenceKeys.SELECTED_CALENDAR_IDS] =
        settings.selectedCalendarIds.orEmpty().map { it.toString() }.toSet()
    this[WidgetPreferenceKeys.FONT_SCALE_STEP] = settings.fontScaleStep
    this[WidgetPreferenceKeys.BACKGROUND_OPACITY] = settings.backgroundOpacity
}

data class WidgetTextSizes(
    val title: TextUnit,
    val weekdayHeader: TextUnit,
    val dayNumber: TextUnit,
    val schedule: TextUnit,
    val body: TextUnit,
    /** 오늘 날짜 원형 배지 지름. 날짜 숫자와 같은 완만한 배율로 커져 숫자가 잘리지 않게 한다. */
    val dayBadgeSize: Dp,
    /** 칸 안에 표시할 일정 최대 건수. 일정 글자가 커질수록 줄어 칸을 넘치지 않게 한다. */
    val maxSchedulesVisible: Int,
)

// 날짜 숫자(+원형 배지)는 완만하게 커진다 — 배지가 셀을 벗어나지 않도록 폭을 좁게 잡는다.
private val DAY_NUMBER_SCALE_BY_STEP = mapOf(
    1 to 0.90f,
    2 to 0.95f,
    3 to 1.00f,
    4 to 1.08f,
    5 to 1.15f,
)

// 일정 텍스트는 단계별로 뚜렷하게 커진다.
private val SCHEDULE_SCALE_BY_STEP = mapOf(
    1 to 0.70f,
    2 to 0.85f,
    3 to 1.00f,
    4 to 1.25f,
    5 to 1.50f,
)

private const val BASE_DAY_BADGE_SIZE_DP = 16f

/** 글자크기 단계(1~5)를 [RunCalWidgetTextSizes] 기준(3단계=보통) 실제 sp/dp 값으로 변환한다. */
fun resolveTextSizes(step: Int): WidgetTextSizes {
    val dayScale = DAY_NUMBER_SCALE_BY_STEP[step] ?: DAY_NUMBER_SCALE_BY_STEP.getValue(DEFAULT_FONT_SCALE_STEP)
    val scheduleScale = SCHEDULE_SCALE_BY_STEP[step] ?: SCHEDULE_SCALE_BY_STEP.getValue(DEFAULT_FONT_SCALE_STEP)
    return WidgetTextSizes(
        title = (RunCalWidgetTextSizes.Title.value * dayScale).sp,
        weekdayHeader = (RunCalWidgetTextSizes.WeekdayHeader.value * dayScale).sp,
        dayNumber = (RunCalWidgetTextSizes.DayNumber.value * dayScale).sp,
        schedule = (RunCalWidgetTextSizes.Schedule.value * scheduleScale).sp,
        body = (RunCalWidgetTextSizes.Body.value * dayScale).sp,
        dayBadgeSize = (BASE_DAY_BADGE_SIZE_DP * dayScale).dp,
        // 일정 글자 배율이 가장 큰 단계(5)에서는 두 줄이 칸 높이를 넘치므로 한 건만 표시한다.
        maxSchedulesVisible = if (step >= 5) 1 else 2,
    )
}

fun fontScaleStepLabel(step: Int): String = when (step) {
    1 -> "아주 작게"
    2 -> "작게"
    3 -> "보통"
    4 -> "크게"
    5 -> "아주 크게"
    else -> "보통"
}

/** 시스템 다크/라이트 모드 기준 배경 베이스 색상에 [opacity](0~1)를 적용한다. */
fun resolveBackgroundColor(context: Context, opacity: Float): Color {
    val isNightMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val base = if (isNightMode) NIGHT_BASE_BACKGROUND_COLOR else DAY_BASE_BACKGROUND_COLOR
    return base.copy(alpha = opacity.coerceIn(0f, 1f))
}
