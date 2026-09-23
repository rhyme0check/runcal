package com.jongsun.runcal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.jongsun.runcal.MainActivity
import com.jongsun.runcal.R
import com.jongsun.runcal.data.CalendarRepository
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.hasCalendarReadPermission
import com.jongsun.runcal.ui.calendar.EventBar
import com.jongsun.runcal.ui.calendar.MonthGridDay
import com.jongsun.runcal.ui.calendar.buildMonthGridWeeks
import com.jongsun.runcal.ui.calendar.monthGridDateRange
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields

private const val TAG = "RunCal"

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

        val renderStartMillis = System.currentTimeMillis()
        val today = LocalDate.now()
        val currentActualYearMonth = YearMonth.from(today)
        val settings = resolveAutoReturn(context, appWidgetId, today, currentActualYearMonth)
        val displayedYearMonth = settings.viewingYearMonth ?: currentActualYearMonth
        val isCurrentMonth = displayedYearMonth == currentActualYearMonth
        val preset = settings.activePreset()
        val textSizes = resolveTextSizes(settings.fontScaleStep)
        val backgroundColorInt = resolveBackgroundColorInt(context, settings.backgroundOpacity)

        // 일요일 시작 6주 그리드. 다일간 일정이 그리드 앞뒤(전/다음 달로 삐져나온 날짜)에 걸칠 수 있어
        // 캘린더 조회 범위도 "이번 달"이 아니라 그리드가 실제로 덮는 전체 구간으로 잡는다.
        val weeks = buildMonthGridWeeks(displayedYearMonth, DayOfWeek.SUNDAY)
        val zone = ZoneId.systemDefault()
        val events = if (preset.calendarIds != null && preset.calendarIds.isEmpty()) {
            emptyList()
        } else {
            val repository = CalendarRepository(context)
            val (start, endExclusive) = monthGridDateRange(weeks)
            val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
            repository.getEvents(startMillis, endMillis, calendarIds = preset.calendarIds?.toList())
        }
        Log.d(TAG, "buildRemoteViews: appWidgetId=$appWidgetId fetched ${events.size} event(s)")

        val root = RemoteViews(context.packageName, R.layout.widget_root)
        root.setInt(R.id.widget_background, "setColorFilter", backgroundColorInt)

        bindHeaderRow1(context, root, appWidgetId, preset, displayedYearMonth)
        bindHeaderRow2(context, root, appWidgetId, displayedYearMonth, isCurrentMonth)
        root.setViewVisibility(R.id.week_number_header, if (settings.showWeekNumber) View.VISIBLE else View.GONE)

        // RemoteViews.addView()는 같은 레이아웃을 다시 적용할 때 기존 뷰 트리에 누적되는 경우가 있어
        // (호스트가 매번 새로 inflate하지 않고 기존 트리에 reapply하는 최적화 경로를 타면), 매번 채우기
        // 전에 반드시 비워야 6주 그리드가 중복되지 않는다.
        root.removeAllViews(R.id.week_rows_container)
        weeks.forEachIndexed { weekIndex, week ->
            val weekBars = computeWidgetWeekBars(week, events, textSizes.maxBarsPerCell, zone)
            val weekRow = RemoteViews(context.packageName, R.layout.widget_week_row)

            weekRow.setViewVisibility(R.id.week_number_text, if (settings.showWeekNumber) View.VISIBLE else View.GONE)
            if (settings.showWeekNumber) {
                // ISO 8601: 그 주의 목요일이 속한 연도 기준 주차. 그리드가 일요일 시작이라도
                // 목요일(인덱스 4)은 항상 그 주 안에 있어 이 값으로 계산하면 연말/연초 경계에서도 정확하다.
                val thursday = week.days[4].date
                val weekNumber = thursday.get(WeekFields.ISO.weekOfWeekBasedYear())
                weekRow.setTextViewText(R.id.week_number_text, weekNumber.toString())
                weekRow.setTextColor(R.id.week_number_text, RunCalWidgetColorRes.onBackgroundDim(context))
            }

            week.days.forEachIndexed { dayIndex, day ->
                val barsForCol = weekBars.lanes.map { lane -> lane.getOrNull(dayIndex) }
                val cell = buildDayCell(
                    context = context,
                    appWidgetId = appWidgetId,
                    weekIndex = weekIndex,
                    dayIndex = dayIndex,
                    day = day,
                    today = today,
                    barsForCol = barsForCol,
                    overflowCount = weekBars.overflowCountByCol[dayIndex],
                    textSizes = textSizes,
                )
                weekRow.addView(DAY_SLOT_IDS[dayIndex], cell)
            }
            root.addView(R.id.week_rows_container, weekRow)
        }

        Log.d(TAG, "buildRemoteViews: appWidgetId=$appWidgetId built in ${System.currentTimeMillis() - renderStartMillis}ms")
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
        day: MonthGridDay,
        today: LocalDate,
        barsForCol: List<EventBar?>,
        overflowCount: Int,
        textSizes: WidgetTextSizes,
    ): RemoteViews {
        val isToday = day.date == today
        val cell = RemoteViews(context.packageName, R.layout.widget_day_cell)
        cell.setTextViewText(R.id.day_number_text, day.date.dayOfMonth.toString())
        cell.setTextViewTextSize(R.id.day_number_text, TypedValue.COMPLEX_UNIT_SP, textSizes.dayNumberSp)
        cell.setViewLayoutWidth(R.id.day_number_frame, textSizes.dayBadgeSizeDp, TypedValue.COMPLEX_UNIT_DIP)
        cell.setViewLayoutHeight(R.id.day_number_frame, textSizes.dayBadgeSizeDp, TypedValue.COMPLEX_UNIT_DIP)

        if (isToday) {
            cell.setViewVisibility(R.id.today_badge, View.VISIBLE)
            cell.setInt(R.id.today_badge, "setColorFilter", RunCalWidgetColorRes.todayBackground(context))
            cell.setTextColor(R.id.day_number_text, RunCalWidgetColorRes.onTodayBackground(context))
        } else {
            cell.setViewVisibility(R.id.today_badge, View.GONE)
            cell.setTextColor(R.id.day_number_text, dayNumberColor(context, day))
        }

        // 새로 만든 day_events_container라 이론상 비어있지만, addView 누적 버그를 한 번 겪었으니
        // 방어적으로 한 번 더 비운다.
        cell.removeAllViews(R.id.day_events_container)

        // "+N"은 칸의 막대 한도(maxBarsPerCell)를 넘어서는 추가 줄이 아니라, 그 한도 안의 마지막 한 줄을
        // 대신 차지해야 한다(안 그러면 한 칸에 maxBarsPerCell+1줄이 들어가 아래 주 행과 겹친다).
        // 레인이 이미 꽉 찬 상태에서 이 날짜의 마지막 레인에 실제 막대가 있었다면, 그 막대 하나도
        // "가려짐"으로 쳐서 +N 숫자에 포함한다.
        var visibleBars = barsForCol
        var displayOverflow = overflowCount
        if (overflowCount > 0 && barsForCol.size >= textSizes.maxBarsPerCell) {
            val lastBar = barsForCol.lastOrNull()
            if (lastBar != null) {
                visibleBars = barsForCol.dropLast(1)
                displayOverflow += 1
            }
        }

        visibleBars.forEach { bar ->
            // 제목은 막대의 "진짜" 시작일이 아니라, 이번 주 행에서 이 막대가 처음 보이는 칸에서만
            // 보여준다 — 여러 주에 걸치는 일정은 각 행에서 한 번씩 제목이 다시 보여야 한다
            // (매주 앞쪽으로 스크롤해 원래 시작일을 확인할 필요가 없도록).
            val showText = bar != null && dayIndex == bar.startCol
            cell.addView(R.id.day_events_container, buildBarView(context, bar, showText, textSizes))
        }
        if (displayOverflow > 0) {
            cell.addView(R.id.day_events_container, buildOverflowView(context, displayOverflow, textSizes))
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
     * 레인 한 칸을 그린다. [bar]가 null이면(이 칸엔 일정이 없지만 다른 칸에 걸친 레인이라 자리는 차지)
     * 배경 없는 투명 스페이서로 그려 다른 요일과 세로 정렬을 맞춘다.
     */
    private fun buildBarView(context: Context, bar: EventBar?, showText: Boolean, textSizes: WidgetTextSizes): RemoteViews {
        val view = RemoteViews(context.packageName, R.layout.widget_event_bar)
        view.setViewLayoutHeight(R.id.bar_root, textSizes.barHeightDp, TypedValue.COMPLEX_UNIT_DIP)
        view.setTextViewTextSize(R.id.bar_text, TypedValue.COMPLEX_UNIT_SP, textSizes.scheduleSp)

        if (bar == null) {
            view.setViewVisibility(R.id.bar_background, View.GONE)
            view.setTextViewText(R.id.bar_text, "")
            return view
        }

        val cornerDrawable = when {
            bar.isTrueStart && bar.isTrueEnd -> R.drawable.widget_bar_single
            bar.isTrueStart -> R.drawable.widget_bar_start
            bar.isTrueEnd -> R.drawable.widget_bar_end
            else -> R.drawable.widget_bar_middle
        }
        // TextView는 setColorFilter를 지원하지 않아 배경은 별도 ImageView(bar_background)에 그린다.
        view.setViewVisibility(R.id.bar_background, View.VISIBLE)
        view.setImageViewResource(R.id.bar_background, cornerDrawable)
        view.setInt(R.id.bar_background, "setColorFilter", bar.event.color)
        view.setTextViewText(R.id.bar_text, if (showText) bar.event.title else "")
        view.setTextColor(R.id.bar_text, contrastingTextColor(bar.event.color))
        return view
    }

    private fun buildOverflowView(context: Context, count: Int, textSizes: WidgetTextSizes): RemoteViews {
        val view = RemoteViews(context.packageName, R.layout.widget_event_bar)
        view.setViewLayoutHeight(R.id.bar_root, textSizes.barHeightDp, TypedValue.COMPLEX_UNIT_DIP)
        view.setViewVisibility(R.id.bar_background, View.GONE)
        view.setTextViewTextSize(R.id.bar_text, TypedValue.COMPLEX_UNIT_SP, textSizes.scheduleSp)
        view.setTextViewText(R.id.bar_text, "+$count")
        view.setTextColor(R.id.bar_text, RunCalWidgetColorRes.scheduleTextDim(context))
        return view
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

private fun dayNumberColor(context: Context, day: MonthGridDay): Int = when {
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
