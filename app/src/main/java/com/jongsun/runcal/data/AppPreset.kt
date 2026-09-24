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
    // null = 이 프리셋에 Notion DB 없음(전체 아님) — calendarIds의 null=전체와 의도적으로 비대칭.
    // 기존에 저장된 프리셋이 새 Notion DB 등록 후에도 그대로 예전처럼 동작하도록 opt-in으로 둔다.
    val notionDatabaseIds: Set<String>? = null,
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
