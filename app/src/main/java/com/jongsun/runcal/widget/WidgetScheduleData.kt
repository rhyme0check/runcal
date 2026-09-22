package com.jongsun.runcal.widget

import androidx.compose.ui.graphics.Color

/**
 * 더미 일정 데이터 저장소.
 * TODO: 실제 일정 데이터 소스(Room/DataStore/서버 동기화 등)로 교체 예정.
 */
object DummyScheduleRepository {

    private val dotPalette = listOf(
        Color(0xFFEF5350),
        Color(0xFF42A5F5),
        Color(0xFF66BB6A),
        Color(0xFFFFA726),
        Color(0xFFAB47BC),
    )

    private val monthlySchedules: Map<Int, List<String>> = mapOf(
        1 to listOf("신정 휴무"),
        3 to listOf("팀 회의 10:00", "러닝 5km"),
        5 to listOf("병원 예약"),
        8 to listOf("장보기"),
        10 to listOf("프로젝트 마감", "회식 19:00"),
        14 to listOf("러닝 10km"),
        15 to listOf("친구 생일"),
        18 to listOf("월간 정산"),
        21 to listOf("러닝 5km", "세차"),
        25 to listOf("가족 모임"),
        28 to listOf("헬스장 PT"),
    )

    fun schedulesFor(dayOfMonth: Int): List<ScheduleEntry> {
        val texts = monthlySchedules[dayOfMonth].orEmpty()
        return texts.mapIndexed { index, text ->
            ScheduleEntry(text = text, dotColor = dotPalette[(dayOfMonth + index) % dotPalette.size])
        }
    }
}

data class ScheduleEntry(
    val text: String,
    val dotColor: Color,
)
