package com.jongsun.runcal.widget

import java.time.YearMonth
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

const val MIN_FONT_SCALE_STEP = 1
const val MAX_FONT_SCALE_STEP = 5
const val DEFAULT_FONT_SCALE_STEP = 3
const val DEFAULT_BACKGROUND_OPACITY = 0.7f

/** 위젯이 이번 달이 아닌 달을 보고 있을 때, 이 시간만큼 조작이 없으면 이번 달로 자동 복귀한다. */
const val AUTO_RETURN_IDLE_MILLIS = 6 * 60 * 60 * 1000L

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

/** [WidgetPreset] 리스트를 JSON으로 (역)직렬화할 때 쓰는 공용 시리얼라이저. */
val presetListSerializer = ListSerializer(WidgetPreset.serializer())

/**
 * RemoteViews에 바로 넘길 수 있는 실측 텍스트/치수 값(sp/dp 그대로의 Float).
 * 1단계에서는 기존 Glance 버전과 비슷한 배율 방식을 유지하고, 5단계 고정 표는 2단계에서 적용한다.
 */
data class WidgetTextSizes(
    val weekdayHeaderSp: Float,
    val dayNumberSp: Float,
    val scheduleSp: Float,
    val dayBadgeSizeDp: Float,
    /** 칸 안에 표시할 일정 최대 건수. */
    val maxSchedulesVisible: Int,
)

private const val BASE_WEEKDAY_HEADER_SP = 10f
private const val BASE_DAY_NUMBER_SP = 12f
private const val BASE_SCHEDULE_SP = 10f
private const val BASE_DAY_BADGE_SIZE_DP = 18f

// 1단계 임시 배율표(2단계에서 고정 sp 표로 교체 예정).
private val SCALE_BY_STEP = mapOf(
    1 to 0.85f,
    2 to 0.92f,
    3 to 1.00f,
    4 to 1.15f,
    5 to 1.30f,
)

/** 글자크기 단계(1~5)를 실제 sp/dp 값으로 변환한다. */
fun resolveTextSizes(step: Int): WidgetTextSizes {
    val scale = SCALE_BY_STEP[step] ?: SCALE_BY_STEP.getValue(DEFAULT_FONT_SCALE_STEP)
    return WidgetTextSizes(
        weekdayHeaderSp = BASE_WEEKDAY_HEADER_SP * scale,
        dayNumberSp = BASE_DAY_NUMBER_SP * scale,
        scheduleSp = BASE_SCHEDULE_SP * scale,
        dayBadgeSizeDp = BASE_DAY_BADGE_SIZE_DP * scale,
        maxSchedulesVisible = if (step >= 5) 2 else 3,
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
