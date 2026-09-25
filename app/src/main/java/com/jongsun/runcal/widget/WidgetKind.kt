package com.jongsun.runcal.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/** 위젯 6종 provider 클래스 전체 — updateAllWidgets/백업이 배치된 인스턴스를 열거할 때 공용으로 쓴다. */
val ALL_WIDGET_PROVIDER_CLASSES = listOf(
    RunCalCalendarWidgetProvider::class.java,
    RunCalMonthlyStandardWidgetProvider::class.java,
    RunCalMonthlyCompactWidgetProvider::class.java,
    RunCalTodayMiniWidgetProvider::class.java,
    RunCalTodayHorizontalWidgetProvider::class.java,
    RunCalTodayVerticalWidgetProvider::class.java,
)

/** 현재 기기에 배치된 RunCal 위젯 인스턴스(6종 전체)의 appWidgetId를 전부 모은다. */
fun enumeratePlacedWidgetIds(context: Context): List<Int> {
    val manager = AppWidgetManager.getInstance(context)
    return ALL_WIDGET_PROVIDER_CLASSES.flatMap { providerClass ->
        manager.getAppWidgetIds(ComponentName(context, providerClass)).toList()
    }
}

/** 위젯 6종의 종류. 렌더러/설정 화면이 이 값으로 분기한다. */
enum class WidgetKind {
    MONTHLY_EXPANDED,
    MONTHLY_STANDARD,
    MONTHLY_COMPACT,
    TODAY_MINI,
    TODAY_HORIZONTAL,
    TODAY_VERTICAL,
    ;

    val isMonthly: Boolean get() = this == MONTHLY_EXPANDED || this == MONTHLY_STANDARD || this == MONTHLY_COMPACT

    companion object {
        /**
         * appWidgetId 자체는 종류를 모르므로, Android가 이미 관리하는 appWidgetId→provider 매핑을
         * 물어봐서 역으로 구한다. 별도 SharedPreferences에 "이 위젯은 무슨 종류"를 저장하지 않는
         * 이유도 이거다 — 저장해두면 어긋날 수 있지만, 이 방법은 항상 실제 배치된 provider와 일치한다.
         */
        fun forAppWidgetId(context: Context, appWidgetId: Int): WidgetKind {
            val providerClassName = AppWidgetManager.getInstance(context)
                .getAppWidgetInfo(appWidgetId)?.provider?.className
            return when (providerClassName) {
                RunCalMonthlyStandardWidgetProvider::class.java.name -> MONTHLY_STANDARD
                RunCalMonthlyCompactWidgetProvider::class.java.name -> MONTHLY_COMPACT
                RunCalTodayMiniWidgetProvider::class.java.name -> TODAY_MINI
                RunCalTodayHorizontalWidgetProvider::class.java.name -> TODAY_HORIZONTAL
                RunCalTodayVerticalWidgetProvider::class.java.name -> TODAY_VERTICAL
                else -> MONTHLY_EXPANDED
            }
        }
    }
}
