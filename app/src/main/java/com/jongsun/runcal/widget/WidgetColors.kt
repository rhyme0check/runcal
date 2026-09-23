package com.jongsun.runcal.widget

import androidx.glance.unit.ColorProvider
import com.jongsun.runcal.R

/**
 * 위젯 다크/라이트 테마 색상.
 * values/colors.xml, values-night/colors.xml 리소스를 참조해 시스템 테마에 따라 자동 전환된다.
 * 월 밖 날짜/일정은 낮은 alpha로 흐리게 표시한다.
 * 배경색은 위젯 인스턴스별 투명도 설정이 반영되어야 하므로 [resolveBackgroundColor]에서 동적으로 계산한다.
 */
object RunCalWidgetColors {
    val onBackground = ColorProvider(R.color.runcal_widget_on_background)
    val onBackgroundDim = ColorProvider(R.color.runcal_widget_on_background_dim)

    val sunday = ColorProvider(R.color.runcal_widget_sunday)
    val sundayDim = ColorProvider(R.color.runcal_widget_sunday_dim)

    val saturday = ColorProvider(R.color.runcal_widget_saturday)
    val saturdayDim = ColorProvider(R.color.runcal_widget_saturday_dim)

    val todayBackground = ColorProvider(R.color.runcal_widget_today_background)
    val onTodayBackground = ColorProvider(R.color.runcal_widget_on_today_background)

    val scheduleText = ColorProvider(R.color.runcal_widget_schedule_text)
    val scheduleTextDim = ColorProvider(R.color.runcal_widget_schedule_text_dim)

    /** 헤더 2단에서 이번 달이 아닌 달을 보고 있을 때 연월 텍스트를 강조하는 색상. */
    val accent = ColorProvider(R.color.runcal_widget_sunday)
}
