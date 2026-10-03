package com.jongsun.runcal.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** 캘린더 표시 프리셋. 앱과 위젯이 이 목록 하나를 함께 쓴다(정의는 앱 설정에만 저장, 위젯은 선택한 id만 가진다). */
@Serializable
data class AppPreset(
    val id: String,
    val name: String,
    val colorArgb: Int,
    val calendarIds: Set<Long>? = null,
    // null = 이 프리셋에 Notion DB 없음(전체 아님) — calendarIds의 null=전체와 의도적으로 비대칭.
    // 기존에 저장된 프리셋이 새 Notion DB 등록 후에도 그대로 예전처럼 동작하도록 opt-in으로 둔다.
    val notionDatabaseIds: Set<String>? = null,
    // P11: 이 프리셋에 묶은 일정그룹. 고른 캘린더·Notion DB의 일정에 더해 이 그룹들의 일정도 보여 준다(합집합). null=없음.
    val groupIds: Set<String>? = null,
    // 이 프리셋을 볼 때 모든 일정을 [colorArgb]로 그릴지(그룹·개별 색보다 우선). 기본은 끔 — 프리셋 색은 이름 옆 점에만 쓰인다.
    val overrideEventColor: Boolean = false,
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
