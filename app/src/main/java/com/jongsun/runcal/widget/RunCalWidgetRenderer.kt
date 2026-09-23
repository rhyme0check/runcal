package com.jongsun.runcal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.jongsun.runcal.MainActivity
import com.jongsun.runcal.R
import com.jongsun.runcal.data.CalendarRepository
import com.jongsun.runcal.data.hasCalendarReadPermission
import com.jongsun.runcal.data.monthRangeMillis
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 브로드캐스트 액션/appWidgetId 등 렌더러와 RunCalWidgetActionReceiver가 함께 쓰는 상수. */
object WidgetActionContract {
    const val ACTION_NAV_PREV = "com.jongsun.runcal.widget.ACTION_NAV_PREV"
    const val ACTION_NAV_NEXT = "com.jongsun.runcal.widget.ACTION_NAV_NEXT"
    const val ACTION_JUMP_TODAY = "com.jongsun.runcal.widget.ACTION_JUMP_TODAY"
}

private const val SLOT_OPEN_APP = 1
private const val SLOT_PRESET = 2
private const val SLOT_PICKER = 3
private const val SLOT_NAV_PREV = 4
private const val SLOT_NAV_NEXT = 5
private const val SLOT_JUMP_TODAY = 6
private const val SLOT_PERMISSION_OPEN_APP = 7
private const val DAY_CELL_SLOT_BASE = 100

private val DAY_SLOT_IDS = intArrayOf(
    R.id.day_slot_0, R.id.day_slot_1, R.id.day_slot_2, R.id.day_slot_3,
    R.id.day_slot_4, R.id.day_slot_5, R.id.day_slot_6,
)

/**
 * Glance 대신 고전 RemoteViews로 위젯을 직접 그린다. WorkManager를 전혀 거치지 않고
 * [AppWidgetManager.updateAppWidget]을 이 함수 호출 스택 안에서 바로 호출하므로, 이 함수가
 * 반환하면 위젯 화면은 이미 갱신된 상태다(진단/실측은 호출부의 elapsed 로그로 확인한다).
 */
object RunCalWidgetRenderer {

    suspend fun updateWidget(context: Context, appWidgetId: Int) {
        val views = buildRemoteViews(context, appWidgetId)
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
    }

    /** 캘린더 데이터 변경 등 특정 위젯 인스턴스를 특정할 수 없는 경우, 배치된 모든 인스턴스를 갱신한다. */
    suspend fun updateAllWidgets(context: Context) {
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, RunCalCalendarWidgetProvider::class.java))
        ids.forEach { appWidgetId -> updateWidget(context, appWidgetId) }
    }

    private suspend fun buildRemoteViews(context: Context, appWidgetId: Int): RemoteViews {
        if (!hasCalendarReadPermission(context)) {
            return RemoteViews(context.packageName, R.layout.widget_permission_required).apply {
                setOnClickPendingIntent(
                    R.id.permission_root,
                    activityPendingIntent(context, appWidgetId, SLOT_PERMISSION_OPEN_APP, openAppIntent(context, null)),
                )
            }
        }

        val today = LocalDate.now()
        val currentActualYearMonth = YearMonth.from(today)
        val settings = resolveAutoReturn(context, appWidgetId, today, currentActualYearMonth)
        val displayedYearMonth = settings.viewingYearMonth ?: currentActualYearMonth
        val isCurrentMonth = displayedYearMonth == currentActualYearMonth
        val preset = settings.activePreset()
        val textSizes = resolveTextSizes(settings.fontScaleStep)
        val backgroundColorInt = resolveBackgroundColorInt(context, settings.backgroundOpacity)

        val eventsByDay = if (preset.calendarIds != null && preset.calendarIds.isEmpty()) {
            emptyMap()
        } else {
            val repository = CalendarRepository(context)
            val (start, end) = monthRangeMillis(displayedYearMonth)
            val events = repository.getEvents(start, end, calendarIds = preset.calendarIds?.toList())
            groupEventsByDay(events, displayedYearMonth)
        }
        val weeks = buildMonthGrid(displayedYearMonth, today, eventsByDay)

        val root = RemoteViews(context.packageName, R.layout.widget_root)
        root.setInt(R.id.widget_background, "setColorFilter", backgroundColorInt)

        bindHeaderRow1(context, root, appWidgetId, preset, displayedYearMonth)
        bindHeaderRow2(context, root, appWidgetId, displayedYearMonth, isCurrentMonth)

        // RemoteViews.addView()는 같은 레이아웃을 다시 적용할 때 기존 뷰 트리에 누적되는 경우가 있어
        // (호스트가 매번 새로 inflate하지 않고 기존 트리에 reapply하는 최적화 경로를 타면), 매번 채우기
        // 전에 반드시 비워야 6주 그리드가 중복되지 않는다.
        root.removeAllViews(R.id.week_rows_container)
        weeks.forEachIndexed { weekIndex, week ->
            val weekRow = RemoteViews(context.packageName, R.layout.widget_week_row)
            week.forEachIndexed { dayIndex, day ->
                val cell = buildDayCell(context, appWidgetId, weekIndex, dayIndex, day, textSizes)
                weekRow.addView(DAY_SLOT_IDS[dayIndex], cell)
            }
            root.addView(R.id.week_rows_container, weekRow)
        }

        return root
    }

    private fun bindHeaderRow1(
        context: Context,
        root: RemoteViews,
        appWidgetId: Int,
        preset: WidgetPreset,
        displayedYearMonth: YearMonth,
    ) {
        root.setTextViewText(R.id.preset_name, preset.name)
        root.setTextColor(R.id.preset_name, RunCalWidgetColorRes.onBackground(context))
        root.setInt(R.id.preset_dot, "setColorFilter", preset.colorArgb)
        root.setOnClickPendingIntent(
            R.id.preset_chip,
            activityPendingIntent(context, appWidgetId, SLOT_PRESET, presetPickerIntent(context, appWidgetId)),
        )
        root.setOnClickPendingIntent(
            R.id.open_app_spacer,
            activityPendingIntent(context, appWidgetId, SLOT_OPEN_APP, openAppIntent(context, displayedYearMonth)),
        )
        root.setTextViewText(R.id.short_yearmonth_button, shortYearMonthLabel(displayedYearMonth))
        root.setTextColor(R.id.short_yearmonth_button, RunCalWidgetColorRes.onBackground(context))
        root.setOnClickPendingIntent(
            R.id.short_yearmonth_button,
            activityPendingIntent(context, appWidgetId, SLOT_PICKER, pickerIntent(context, appWidgetId)),
        )
    }

    private fun bindHeaderRow2(
        context: Context,
        root: RemoteViews,
        appWidgetId: Int,
        displayedYearMonth: YearMonth,
        isCurrentMonth: Boolean,
    ) {
        root.setTextColor(R.id.nav_prev, RunCalWidgetColorRes.onBackground(context))
        root.setTextColor(R.id.nav_next, RunCalWidgetColorRes.onBackground(context))
        root.setOnClickPendingIntent(
            R.id.nav_prev,
            broadcastPendingIntent(context, appWidgetId, SLOT_NAV_PREV, WidgetActionContract.ACTION_NAV_PREV),
        )
        root.setOnClickPendingIntent(
            R.id.nav_next,
            broadcastPendingIntent(context, appWidgetId, SLOT_NAV_NEXT, WidgetActionContract.ACTION_NAV_NEXT),
        )
        root.setTextViewText(R.id.month_title, "${displayedYearMonth.year}년 ${displayedYearMonth.monthValue}월")
        root.setTextColor(
            R.id.month_title,
            if (isCurrentMonth) RunCalWidgetColorRes.onBackground(context) else RunCalWidgetColorRes.accent(context),
        )
        root.setOnClickPendingIntent(
            R.id.month_title,
            broadcastPendingIntent(context, appWidgetId, SLOT_JUMP_TODAY, WidgetActionContract.ACTION_JUMP_TODAY),
        )
    }

    private fun buildDayCell(
        context: Context,
        appWidgetId: Int,
        weekIndex: Int,
        dayIndex: Int,
        day: CalendarDay,
        textSizes: WidgetTextSizes,
    ): RemoteViews {
        val cell = RemoteViews(context.packageName, R.layout.widget_day_cell)
        cell.setTextViewText(R.id.day_number_text, day.date.dayOfMonth.toString())
        cell.setTextViewTextSize(R.id.day_number_text, TypedValue.COMPLEX_UNIT_SP, textSizes.dayNumberSp)

        if (day.isToday) {
            cell.setViewVisibility(R.id.today_badge, View.VISIBLE)
            cell.setInt(R.id.today_badge, "setColorFilter", RunCalWidgetColorRes.todayBackground(context))
            cell.setTextColor(R.id.day_number_text, RunCalWidgetColorRes.onTodayBackground(context))
        } else {
            cell.setViewVisibility(R.id.today_badge, View.GONE)
            cell.setTextColor(R.id.day_number_text, dayNumberColor(context, day))
        }

        day.schedules.take(textSizes.maxSchedulesVisible).forEach { schedule ->
            val line = RemoteViews(context.packageName, R.layout.widget_event_line)
            line.setInt(R.id.event_dot, "setColorFilter", schedule.dotColor)
            line.setTextViewText(R.id.event_text, schedule.text)
            line.setTextViewTextSize(R.id.event_text, TypedValue.COMPLEX_UNIT_SP, textSizes.scheduleSp)
            line.setTextColor(
                R.id.event_text,
                if (day.isCurrentMonth) RunCalWidgetColorRes.scheduleText(context) else RunCalWidgetColorRes.scheduleTextDim(context),
            )
            cell.addView(R.id.day_events_container, line)
        }

        val dayIntent = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("runcal://widget/day/${day.date}")
            putExtra(MainActivity.EXTRA_TARGET_DATE_EPOCH_DAY, day.date.toEpochDay())
        }
        val requestCode = requestCode(appWidgetId, DAY_CELL_SLOT_BASE + weekIndex * 7 + dayIndex)
        cell.setOnClickPendingIntent(
            R.id.day_cell_root,
            PendingIntent.getActivity(context, requestCode, dayIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
        return cell
    }

    /**
     * 이번 달이 아닌 달을 보고 있는 상태에서 [AUTO_RETURN_IDLE_MILLIS]가 지났거나 날짜가 바뀌었으면
     * 이번 달로 되돌리고 저장한다(렌더 자체를 재귀 호출하지 않도록 persistWidgetFilterSettings로 직접 저장).
     */
    private fun resolveAutoReturn(
        context: Context,
        appWidgetId: Int,
        today: LocalDate,
        currentActualYearMonth: YearMonth,
    ): WidgetFilterSettings {
        val settings = loadWidgetFilterSettings(context, appWidgetId)
        val viewing = settings.viewingYearMonth ?: return settings
        if (viewing == currentActualYearMonth) return settings

        val zone = ZoneId.systemDefault()
        val lastNavigatedDate = Instant.ofEpochMilli(settings.lastNavigatedAtMillis).atZone(zone).toLocalDate()
        val idleTooLong = System.currentTimeMillis() - settings.lastNavigatedAtMillis > AUTO_RETURN_IDLE_MILLIS
        val dateChanged = lastNavigatedDate != today
        if (!idleTooLong && !dateChanged) return settings

        val reverted = settings.copy(viewingYearMonth = null, lastNavigatedAtMillis = 0L)
        persistWidgetFilterSettings(context, appWidgetId, reverted)
        return reverted
    }
}

private fun dayNumberColor(context: Context, day: CalendarDay): Int = when {
    !day.isCurrentMonth -> when (day.date.dayOfWeek) {
        DayOfWeek.SUNDAY -> RunCalWidgetColorRes.sundayDim(context)
        DayOfWeek.SATURDAY -> RunCalWidgetColorRes.saturdayDim(context)
        else -> RunCalWidgetColorRes.onBackgroundDim(context)
    }
    else -> when (day.date.dayOfWeek) {
        DayOfWeek.SUNDAY -> RunCalWidgetColorRes.sunday(context)
        DayOfWeek.SATURDAY -> RunCalWidgetColorRes.saturday(context)
        else -> RunCalWidgetColorRes.onBackground(context)
    }
}

/** yy.MM 형식의 짧은 연월 라벨(예: 2026년 9월 → "26.09"). */
private fun shortYearMonthLabel(yearMonth: YearMonth): String {
    val yy = (yearMonth.year % 100).toString().padStart(2, '0')
    val mm = yearMonth.monthValue.toString().padStart(2, '0')
    return "$yy.$mm"
}

private fun requestCode(appWidgetId: Int, slot: Int): Int = appWidgetId * 1000 + slot

private fun activityPendingIntent(context: Context, appWidgetId: Int, slot: Int, intent: Intent): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode(appWidgetId, slot),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

private fun broadcastPendingIntent(context: Context, appWidgetId: Int, slot: Int, action: String): PendingIntent {
    val intent = Intent(context, RunCalWidgetActionReceiver::class.java).apply {
        this.action = action
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }
    return PendingIntent.getBroadcast(
        context,
        requestCode(appWidgetId, slot),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private fun openAppIntent(context: Context, displayedYearMonth: YearMonth?): Intent =
    Intent(context, MainActivity::class.java).apply {
        data = Uri.parse("runcal://widget/open-app/${displayedYearMonth ?: "none"}")
        if (displayedYearMonth != null) {
            putExtra(MainActivity.EXTRA_TARGET_YEAR_MONTH, displayedYearMonth.toString())
        }
    }

private fun pickerIntent(context: Context, appWidgetId: Int): Intent =
    Intent(context, YearMonthPickerActivity::class.java).apply {
        data = Uri.parse("runcal://widget/$appWidgetId/pick-month")
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }

private fun presetPickerIntent(context: Context, appWidgetId: Int): Intent =
    Intent(context, PresetPickerActivity::class.java).apply {
        data = Uri.parse("runcal://widget/$appWidgetId/pick-preset")
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
