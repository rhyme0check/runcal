package com.jongsun.runcal.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** 앱 화면 전용 캘린더 표시 프리셋. 위젯의 WidgetPreset과는 독립적으로 저장/관리된다. */
@Serializable
data class AppPreset(
    val id: String,
    val name: String,
    val colorArgb: Int,
    val calendarIds: Set<Long>? = null,
)

val APP_PRESET_COLOR_PALETTE = listOf(
    0xFFEF9A9A.toInt(),
    0xFFFFCC80.toInt(),
    0xFFFFF59D.toInt(),
    0xFFA5D6A7.toInt(),
    0xFF90CAF9.toInt(),
    0xFFB39DDB.toInt(),
    0xFFF48FB1.toInt(),
    0xFFB0BEC5.toInt(),
)

const val DEFAULT_APP_PRESET_ID = "__all__"

val DEFAULT_APP_PRESET = AppPreset(
    id = DEFAULT_APP_PRESET_ID,
    name = "전체",
    colorArgb = APP_PRESET_COLOR_PALETTE[4],
    calendarIds = null,
)

val appPresetListSerializer = ListSerializer(AppPreset.serializer())
