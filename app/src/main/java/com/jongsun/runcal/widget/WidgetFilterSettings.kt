package com.jongsun.runcal.widget

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.YearMonth
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val TAG = "RunCal"

object WidgetPreferenceKeys {
    val PRESETS_JSON = stringPreferencesKey("widget_presets_json")
    val CURRENT_PRESET_INDEX = intPreferencesKey("current_preset_index")
    val FONT_SCALE_STEP = intPreferencesKey("font_scale_step")
    val BACKGROUND_OPACITY = floatPreferencesKey("background_opacity")
    val VIEWING_YEAR_MONTH = stringPreferencesKey("viewing_year_month")
    val LAST_NAVIGATED_AT_MILLIS = longPreferencesKey("last_navigated_at_millis")
}

const val MIN_FONT_SCALE_STEP = 1
const val MAX_FONT_SCALE_STEP = 5
const val DEFAULT_FONT_SCALE_STEP = 3
const val DEFAULT_BACKGROUND_OPACITY = 0.7f

/** 위젯이 이번 달이 아닌 달을 보고 있을 때, 이 시간만큼 조작이 없으면 이번 달로 자동 복귀한다. */
const val AUTO_RETURN_IDLE_MILLIS = 6 * 60 * 60 * 1000L

private val DAY_BASE_BACKGROUND_COLOR = Color(0xFFFFFFFF)
private val NIGHT_BASE_BACKGROUND_COLOR = Color(0xFF1C1B1F)

val PRESET_COLOR_PALETTE = listOf(
    0xFFE57373.toInt(),
    0xFF64B5F6.toInt(),
    0xFF81C784.toInt(),
    0xFFFFB74D.toInt(),
    0xFFBA68C8.toInt(),
    0xFF4DB6AC.toInt(),
    0xFF9E9E9E.toInt(),
    0xFFF06292.toInt(),
)

/** 사용자 정의 이름 붙은 필터 세트. [calendarIds]가 null이면 전체 캘린더 표시. */
@Serializable
data class WidgetPreset(
    val id: String,
    val name: String,
    val colorArgb: Int,
    val calendarIds: Set<Long>? = null,
)

val DEFAULT_PRESET = WidgetPreset(id = "__all__", name = "전체", colorArgb = PRESET_COLOR_PALETTE[6], calendarIds = null)

/**
 * 위젯 인스턴스별 필터/표시 설정.
 * [viewingYearMonth]가 null이면 "이번 달을 보는 중"을 의미하고, 값이 있으면 헤더 이동/점프로
 * 다른 달을 보고 있는 상태를 의미하며 [lastNavigatedAtMillis]와 함께 자동 복귀 판단에 쓰인다.
 */
data class WidgetFilterSettings(
    val presets: List<WidgetPreset> = emptyList(),
    val currentPresetIndex: Int = 0,
    val fontScaleStep: Int = DEFAULT_FONT_SCALE_STEP,
    val backgroundOpacity: Float = DEFAULT_BACKGROUND_OPACITY,
    val viewingYearMonth: YearMonth? = null,
    val lastNavigatedAtMillis: Long = 0L,
)

fun WidgetFilterSettings.activePreset(): WidgetPreset {
    if (presets.isEmpty()) return DEFAULT_PRESET
    val idx = currentPresetIndex.coerceIn(presets.indices)
    return presets[idx]
}

fun WidgetFilterSettings.nextPresetIndex(): Int {
    if (presets.size <= 1) return 0
    val idx = currentPresetIndex.coerceIn(presets.indices)
    return (idx + 1) % presets.size
}

private val presetListSerializer = ListSerializer(WidgetPreset.serializer())

fun Preferences.toWidgetFilterSettings(): WidgetFilterSettings {
    val presets = this[WidgetPreferenceKeys.PRESETS_JSON]?.let { json ->
        try {
            Json.decodeFromString(presetListSerializer, json)
        } catch (e: Exception) {
            Log.e(TAG, "toWidgetFilterSettings: failed to parse presets json", e)
            emptyList()
        }
    } ?: emptyList()

    val viewingYearMonth = this[WidgetPreferenceKeys.VIEWING_YEAR_MONTH]?.let {
        try {
            YearMonth.parse(it)
        } catch (e: Exception) {
            null
        }
    }

    return WidgetFilterSettings(
        presets = presets,
        currentPresetIndex = this[WidgetPreferenceKeys.CURRENT_PRESET_INDEX] ?: 0,
        fontScaleStep = this[WidgetPreferenceKeys.FONT_SCALE_STEP] ?: DEFAULT_FONT_SCALE_STEP,
        backgroundOpacity = this[WidgetPreferenceKeys.BACKGROUND_OPACITY] ?: DEFAULT_BACKGROUND_OPACITY,
        viewingYearMonth = viewingYearMonth,
        lastNavigatedAtMillis = this[WidgetPreferenceKeys.LAST_NAVIGATED_AT_MILLIS] ?: 0L,
    )
}

fun MutablePreferences.applyWidgetFilterSettings(settings: WidgetFilterSettings) {
    this[WidgetPreferenceKeys.PRESETS_JSON] = Json.encodeToString(presetListSerializer, settings.presets)
    this[WidgetPreferenceKeys.CURRENT_PRESET_INDEX] = settings.currentPresetIndex
    this[WidgetPreferenceKeys.FONT_SCALE_STEP] = settings.fontScaleStep
    this[WidgetPreferenceKeys.BACKGROUND_OPACITY] = settings.backgroundOpacity
    if (settings.viewingYearMonth == null) {
        this.remove(WidgetPreferenceKeys.VIEWING_YEAR_MONTH)
    } else {
        this[WidgetPreferenceKeys.VIEWING_YEAR_MONTH] = settings.viewingYearMonth.toString()
    }
    this[WidgetPreferenceKeys.LAST_NAVIGATED_AT_MILLIS] = settings.lastNavigatedAtMillis
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
