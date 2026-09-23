package com.jongsun.runcal.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.jongsun.runcal.MainActivity
import com.jongsun.runcal.data.CalendarRepository
import com.jongsun.runcal.data.hasCalendarReadPermission
import com.jongsun.runcal.data.monthRangeMillis
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private const val TAG = "RunCal"

private val WEEKDAY_LABELS = listOf("일", "월", "화", "수", "목", "금", "토")

class RunCalCalendarWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        // 세션(SessionWorker)이 실제로 재개되기까지 얼마나 걸리는지 콜백 시각 대비로 남긴다.
        // 진단 목적: 어떤 조작 이후에 지연이 발생하는지 패턴을 파악하기 위함.
        val elapsedSinceCallback = WidgetCallbackTiming.elapsedSinceCallback(appWidgetId)
        Log.d(TAG, "provideGlance START id=$appWidgetId elapsedSinceCallback=${elapsedSinceCallback?.let { "${it}ms" } ?: "n/a"}")
        val hasPermission = hasCalendarReadPermission(context)
        val today = LocalDate.now()
        val currentActualYearMonth = YearMonth.from(today)

        val settings = resolveAutoReturn(context, id, appWidgetId, today, currentActualYearMonth)
        Log.d(
            TAG,
            "provideGlance: appWidgetId=$appWidgetId viewingYearMonth=${settings.viewingYearMonth} " +
                "presetIndex=${settings.currentPresetIndex}/${settings.presets.size} fontScaleStep=${settings.fontScaleStep}",
        )

        val displayedYearMonth = settings.viewingYearMonth ?: currentActualYearMonth
        val preset = settings.activePreset()
        val textSizes = resolveTextSizes(settings.fontScaleStep)
        val backgroundColor = ColorProvider(resolveBackgroundColor(context, settings.backgroundOpacity))

        val eventsByDay = when {
            !hasPermission -> emptyMap()
            // preset이 캘린더를 전부 해제한 경우: getEvents(calendarIds = emptyList())는 "필터 없음"으로 해석되어
            // 전체가 조회되므로, 빈 선택은 여기서 조회 자체를 건너뛰어 빈 결과로 처리한다.
            preset.calendarIds != null && preset.calendarIds.isEmpty() -> emptyMap()
            else -> try {
                val repository = CalendarRepository(context)
                val (start, end) = monthRangeMillis(displayedYearMonth)
                val calendarIdsParam = preset.calendarIds?.toList()
                val events = repository.getEvents(start, end, calendarIds = calendarIdsParam)
                Log.d(TAG, "provideGlance: appWidgetId=$appWidgetId fetched ${events.size} event(s)")
                groupEventsByDay(events, displayedYearMonth)
            } catch (e: SecurityException) {
                Log.e(TAG, "provideGlance: calendar access failed", e)
                emptyMap()
            }
        }

        provideContent {
            if (hasPermission) {
                RunCalCalendarWidgetContent(
                    appWidgetId = appWidgetId,
                    today = today,
                    displayedYearMonth = displayedYearMonth,
                    isCurrentMonth = displayedYearMonth == currentActualYearMonth,
                    preset = preset,
                    eventsByDay = eventsByDay,
                    textSizes = textSizes,
                    backgroundColor = backgroundColor,
                )
            } else {
                PermissionRequiredContent(
                    backgroundColor = backgroundColor,
                    bodySize = textSizes.body,
                )
            }
        }
    }
}

/**
 * 이번 달이 아닌 달을 보고 있는 상태에서 [AUTO_RETURN_IDLE_MILLIS]가 지났거나 날짜가 바뀌었으면
 * 이번 달로 되돌리고 저장한다. 위젯은 자체 타이머가 없으므로 매 provideGlance 호출(주기 갱신 포함)
 * 시점에 검사한다.
 */
private suspend fun resolveAutoReturn(
    context: Context,
    id: GlanceId,
    appWidgetId: Int,
    today: LocalDate,
    currentActualYearMonth: YearMonth,
): WidgetFilterSettings {
    val settings = loadWidgetFilterSettings(context, id)
    val viewing = settings.viewingYearMonth ?: return settings
    if (viewing == currentActualYearMonth) return settings

    val zone = ZoneId.systemDefault()
    val lastNavigatedDate = Instant.ofEpochMilli(settings.lastNavigatedAtMillis).atZone(zone).toLocalDate()
    val idleTooLong = System.currentTimeMillis() - settings.lastNavigatedAtMillis > AUTO_RETURN_IDLE_MILLIS
    val dateChanged = lastNavigatedDate != today

    if (!idleTooLong && !dateChanged) return settings

    Log.d(TAG, "resolveAutoReturn: appWidgetId=$appWidgetId reverting to current month (idleTooLong=$idleTooLong dateChanged=$dateChanged)")
    val reverted = settings.copy(viewingYearMonth = null, lastNavigatedAtMillis = 0L)
    applyWidgetStateByGlanceId(context, id) { reverted }
    return reverted
}

@Composable
private fun PermissionRequiredContent(backgroundColor: ColorProvider, bodySize: TextUnit) {
    val context = LocalContext.current
    // 아래 openAppIntent/pickerIntent/dayIntent와 마찬가지로, 같은 컴포넌트(MainActivity)를 대상으로
    // 하는 Intent가 위젯 안에 여러 개 있으면 action/data가 같을 때 PendingIntent가 서로 뭉개질 수 있어
    // 고유한 data Uri를 부여한다.
    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        data = Uri.parse("runcal://widget/open-app-permission")
    }
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(backgroundColor)
            .cornerRadius(20.dp)
            .padding(16.dp)
            .clickable(actionStartActivity(openAppIntent)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "캘린더 권한이 필요합니다\n앱을 열어 권한을 허용해주세요",
            style = TextStyle(
                fontSize = bodySize,
                color = RunCalWidgetColors.onBackground,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

@Composable
private fun RunCalCalendarWidgetContent(
    appWidgetId: Int,
    today: LocalDate,
    displayedYearMonth: YearMonth,
    isCurrentMonth: Boolean,
    preset: WidgetPreset,
    eventsByDay: Map<Int, List<ScheduleEntry>>,
    textSizes: WidgetTextSizes,
    backgroundColor: ColorProvider,
) {
    val weeks = buildMonthGrid(displayedYearMonth, today, eventsByDay)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(backgroundColor)
            .cornerRadius(20.dp)
            .padding(10.dp),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            WidgetHeaderRow1(appWidgetId = appWidgetId, preset = preset, displayedYearMonth = displayedYearMonth)
            WidgetHeaderRow2(
                displayedYearMonth = displayedYearMonth,
                isCurrentMonth = isCurrentMonth,
            )

            Spacer(modifier = GlanceModifier.height(2.dp))

            Row(modifier = GlanceModifier.fillMaxWidth()) {
                WEEKDAY_LABELS.forEachIndexed { index, label ->
                    Text(
                        text = label,
                        style = TextStyle(
                            fontSize = textSizes.weekdayHeader,
                            fontWeight = FontWeight.Medium,
                            color = weekdayHeaderColor(index),
                            textAlign = TextAlign.Center,
                        ),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(2.dp))

            Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                weeks.forEach { week ->
                    Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                        week.forEach { day ->
                            DayCell(
                                day = day,
                                textSizes = textSizes,
                                modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** yy.MM 형식의 짧은 연월 라벨(예: 2026년 9월 → "26.09"). */
private fun shortYearMonthLabel(yearMonth: YearMonth): String {
    val yy = (yearMonth.year % 100).toString().padStart(2, '0')
    val mm = yearMonth.monthValue.toString().padStart(2, '0')
    return "$yy.$mm"
}

/**
 * 헤더 1단: 왼쪽 프리셋 칩(탭 시 프리셋 선택 팝업) + 가운데 빈 영역(탭 시 앱 실행)
 * + 오른쪽 짧은 연월 버튼("26.09", 탭 시 연월 선택 팝업).
 */
@Composable
private fun WidgetHeaderRow1(appWidgetId: Int, preset: WidgetPreset, displayedYearMonth: YearMonth) {
    val context = LocalContext.current
    // 같은 컴포넌트(MainActivity)를 향하는 다른 Intent(day cell 등)와 PendingIntent가 겹치지 않도록
    // data Uri에 대상 연월을 담아 고유하게 만든다.
    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        data = Uri.parse("runcal://widget/open-app/$displayedYearMonth")
        putExtra(MainActivity.EXTRA_TARGET_YEAR_MONTH, displayedYearMonth.toString())
    }
    // 위젯에서 실행되는 팝업 액티비티가 기존 MainActivity 태스크에 얹혀 뒤에 남는 문제가 있어
    // 항상 독립된 새 태스크로 띄우고, 이전에 남아있던 태스크가 있으면 비운다.
    val pickerIntent = Intent(context, YearMonthPickerActivity::class.java).apply {
        data = Uri.parse("runcal://widget/$appWidgetId/pick-month")
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    // 프리셋 순환(연타)이 세션 경합을 유발해, 순환 대신 팝업에서 한 번만 선택하도록 바꿨다.
    val presetPickerIntent = Intent(context, PresetPickerActivity::class.java).apply {
        data = Uri.parse("runcal://widget/$appWidgetId/pick-preset")
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }

    Row(modifier = GlanceModifier.fillMaxWidth().height(HEADER_ROW1_HEIGHT)) {
        Row(
            verticalAlignment = Alignment.Vertical.CenterVertically,
            modifier = GlanceModifier
                .fillMaxHeight()
                .clickable(actionStartActivity(presetPickerIntent))
                .padding(horizontal = 10.dp),
        ) {
            Box(
                modifier = GlanceModifier
                    .size(8.dp)
                    .background(ColorProvider(Color(preset.colorArgb)))
                    .cornerRadius(4.dp),
            ) {}
            Spacer(modifier = GlanceModifier.width(4.dp))
            Text(
                text = preset.name,
                maxLines = 1,
                style = TextStyle(fontSize = RunCalWidgetTextSizes.HeaderChip, color = RunCalWidgetColors.onBackground),
            )
        }
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .clickable(actionStartActivity(openAppIntent)),
        ) {}
        Box(
            modifier = GlanceModifier
                .fillMaxHeight()
                .clickable(actionStartActivity(pickerIntent))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = shortYearMonthLabel(displayedYearMonth),
                style = TextStyle(fontSize = RunCalWidgetTextSizes.HeaderChip, color = RunCalWidgetColors.onBackground),
            )
        }
    }
}

/** 헤더 2단: ◀ 전월 / 중앙 연월(탭 시 오늘이 있는 달로 즉시 복귀) / ▶ 다음달. */
@Composable
private fun WidgetHeaderRow2(displayedYearMonth: YearMonth, isCurrentMonth: Boolean) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(HEADER_ROW2_HEIGHT),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .width(HEADER_NAV_BUTTON_WIDTH)
                .fillMaxHeight()
                .clickable(actionRunCallback<NavigateMonthAction>(actionParametersOf(NAV_DIRECTION_KEY to -1))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "◀",
                style = TextStyle(fontSize = RunCalWidgetTextSizes.HeaderArrow, color = RunCalWidgetColors.onBackground),
            )
        }
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .clickable(actionRunCallback<JumpToTodayAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${displayedYearMonth.year}년 ${displayedYearMonth.monthValue}월",
                style = TextStyle(
                    fontSize = RunCalWidgetTextSizes.HeaderMonthTitle,
                    fontWeight = FontWeight.Bold,
                    color = if (isCurrentMonth) RunCalWidgetColors.onBackground else RunCalWidgetColors.accent,
                    textAlign = TextAlign.Center,
                ),
            )
        }
        Box(
            modifier = GlanceModifier
                .width(HEADER_NAV_BUTTON_WIDTH)
                .fillMaxHeight()
                .clickable(actionRunCallback<NavigateMonthAction>(actionParametersOf(NAV_DIRECTION_KEY to 1))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "▶",
                style = TextStyle(fontSize = RunCalWidgetTextSizes.HeaderArrow, color = RunCalWidgetColors.onBackground),
            )
        }
    }
}

@Composable
private fun DayCell(day: CalendarDay, textSizes: WidgetTextSizes, modifier: GlanceModifier) {
    val context = LocalContext.current
    // 42개 셀이 전부 같은 컴포넌트(MainActivity)를 열기 때문에 각 셀 날짜를 data Uri에 담아
    // PendingIntent가 서로 겹치지 않게 한다.
    val dayIntent = Intent(context, MainActivity::class.java).apply {
        data = Uri.parse("runcal://widget/day/${day.date}")
        putExtra(MainActivity.EXTRA_TARGET_DATE_EPOCH_DAY, day.date.toEpochDay())
    }
    Column(
        modifier = modifier
            .padding(horizontal = 1.dp)
            .clickable(actionStartActivity(dayIntent)),
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        val badgeModifier = GlanceModifier.size(textSizes.dayBadgeSize).let { base ->
            if (day.isToday) {
                base.background(RunCalWidgetColors.todayBackground).cornerRadius(textSizes.dayBadgeSize / 2)
            } else {
                base
            }
        }

        Box(contentAlignment = Alignment.Center, modifier = badgeModifier) {
            Text(
                text = day.date.dayOfMonth.toString(),
                style = TextStyle(
                    fontSize = textSizes.dayNumber,
                    fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                    color = dayNumberColor(day),
                    textAlign = TextAlign.Center,
                ),
            )
        }

        day.schedules.take(textSizes.maxSchedulesVisible).forEach { schedule ->
            Row(
                verticalAlignment = Alignment.Vertical.CenterVertically,
                modifier = GlanceModifier.fillMaxWidth().padding(top = 1.dp),
            ) {
                Box(
                    modifier = GlanceModifier
                        .size(4.dp)
                        .background(ColorProvider(schedule.dotColor))
                        .cornerRadius(2.dp),
                ) {}
                Spacer(modifier = GlanceModifier.width(2.dp))
                Text(
                    text = schedule.text,
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = textSizes.schedule,
                        color = scheduleTextColor(day),
                    ),
                )
            }
        }
    }
}

private fun weekdayHeaderColor(index: Int): ColorProvider = when (index) {
    0 -> RunCalWidgetColors.sunday
    6 -> RunCalWidgetColors.saturday
    else -> RunCalWidgetColors.onBackground
}

private fun dayNumberColor(day: CalendarDay): ColorProvider = when {
    day.isToday -> RunCalWidgetColors.onTodayBackground
    !day.isCurrentMonth -> when (day.date.dayOfWeek) {
        DayOfWeek.SUNDAY -> RunCalWidgetColors.sundayDim
        DayOfWeek.SATURDAY -> RunCalWidgetColors.saturdayDim
        else -> RunCalWidgetColors.onBackgroundDim
    }
    else -> when (day.date.dayOfWeek) {
        DayOfWeek.SUNDAY -> RunCalWidgetColors.sunday
        DayOfWeek.SATURDAY -> RunCalWidgetColors.saturday
        else -> RunCalWidgetColors.onBackground
    }
}

private fun scheduleTextColor(day: CalendarDay): ColorProvider =
    if (day.isCurrentMonth) RunCalWidgetColors.scheduleText else RunCalWidgetColors.scheduleTextDim
