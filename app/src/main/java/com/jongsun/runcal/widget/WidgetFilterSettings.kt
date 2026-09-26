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
    // null = 이 프리셋에 Notion DB 없음(전체 아님) — calendarIds의 null=전체와 의도적으로 비대칭.
    // 기존에 저장된 프리셋이 새 Notion DB 등록 후에도 그대로 예전처럼 동작하도록 opt-in으로 둔다.
    val notionDatabaseIds: Set<String>? = null,
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
    /** 각 주 왼쪽에 ISO 주차 숫자 칸을 추가로 그릴지. 기본 off. */
    val showWeekNumber: Boolean = false,
    /** 각 날짜 아래에 음력을 작게 표시할지. 4x5 월간 확장 위젯에서만 효과가 있다(나머지는 공간 부족). 기본 off. */
    val showLunar: Boolean = false,
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

/** RemoteViews에 바로 넘길 수 있는 실측 텍스트/치수 값(sp/dp 그대로의 Float). */
data class WidgetTextSizes(
    val weekdayHeaderSp: Float,
    val dayNumberSp: Float,
    val scheduleSp: Float,
    val dayBadgeSizeDp: Float,
    /** 칸 하나에 쌓을 수 있는 최대 막대(레인) 수. 넘치면 "+N"으로 표시. */
    val maxBarsPerCell: Int,
    /** 막대 한 줄의 높이. 4/5단계에서 막대 2개를 확보하려고 여백을 줄인 값. */
    val barHeightDp: Float,
)

private data class SizeStep(
    val weekdayHeaderSp: Float,
    val dayNumberSp: Float,
    val dayBadgeSizeDp: Float,
    val scheduleSp: Float,
    val maxBarsPerCell: Int,
    val barHeightDp: Float,
)

// 글자크기 5단계 고정 표. 일정 막대 텍스트는 10~14sp 범위를 확실히 반영하고,
// 4/5단계도 "월간 뷰 정보량 부족" 피드백에 따라 막대를 최소 2개는 보장한다(넘치면 +N).
private val SIZE_TABLE = mapOf(
    1 to SizeStep(weekdayHeaderSp = 10f, dayNumberSp = 12f, dayBadgeSizeDp = 20f, scheduleSp = 10f, maxBarsPerCell = 3, barHeightDp = 16f),
    2 to SizeStep(weekdayHeaderSp = 11f, dayNumberSp = 13f, dayBadgeSizeDp = 22f, scheduleSp = 11f, maxBarsPerCell = 3, barHeightDp = 16f),
    3 to SizeStep(weekdayHeaderSp = 12f, dayNumberSp = 14f, dayBadgeSizeDp = 24f, scheduleSp = 12f, maxBarsPerCell = 3, barHeightDp = 16f),
    4 to SizeStep(weekdayHeaderSp = 13f, dayNumberSp = 15f, dayBadgeSizeDp = 26f, scheduleSp = 13f, maxBarsPerCell = 2, barHeightDp = 14f),
    5 to SizeStep(weekdayHeaderSp = 14f, dayNumberSp = 16f, dayBadgeSizeDp = 28f, scheduleSp = 14f, maxBarsPerCell = 2, barHeightDp = 12f),
)

/** 글자크기 단계(1~5)를 실제 sp/dp 값으로 변환한다. */
fun resolveTextSizes(step: Int): WidgetTextSizes {
    val s = SIZE_TABLE[step] ?: SIZE_TABLE.getValue(DEFAULT_FONT_SCALE_STEP)
    return WidgetTextSizes(
        weekdayHeaderSp = s.weekdayHeaderSp,
        dayNumberSp = s.dayNumberSp,
        scheduleSp = s.scheduleSp,
        dayBadgeSizeDp = s.dayBadgeSizeDp,
        maxBarsPerCell = s.maxBarsPerCell,
        barHeightDp = s.barHeightDp,
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
