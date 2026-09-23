package com.jongsun.runcal.widget

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 위젯 전용 글자 크기 상수.
 * TODO: 추후 사용자 설정(DataStore 등) 값으로 교체 예정.
 */
object RunCalWidgetTextSizes {
    val Title = 15.sp
    val WeekdayHeader = 10.sp
    val DayNumber = 11.sp
    val Schedule = 8.sp
    val Body = 12.sp

    // 헤더 1/2단 전용 — 그리드 공간 보존을 위해 글자크기 설정과 무관하게 항상 고정 크기로 표시한다.
    val HeaderChip = 10.sp
    val HeaderMonthTitle = 12.sp
    val HeaderArrow = 12.sp
}

/** 헤더 1단(프리셋 칩 + 앱 실행 영역) 높이. 그리드 공간을 최대한 보존하기 위해 낮게 잡는다. */
val HEADER_ROW1_HEIGHT = 26.dp

/** 헤더 2단(연월 이동) 높이. 좌우 화살표 터치 영역 확보를 위해 1단보다는 여유를 둔다. */
val HEADER_ROW2_HEIGHT = 32.dp

/** 헤더 2단 좌우 화살표 버튼의 폭(터치 영역). */
val HEADER_NAV_BUTTON_WIDTH = 36.dp
