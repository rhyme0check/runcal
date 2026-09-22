package com.jongsun.runcal.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
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
import java.time.LocalDate
import java.time.YearMonth

private const val TAG = "RunCal"

private val WEEKDAY_LABELS = listOf("일", "월", "화", "수", "목", "금", "토")

class RunCalCalendarWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val hasPermission = hasCalendarReadPermission(context)
        val today = LocalDate.now()
        val yearMonth = YearMonth.from(today)

        val settings = loadWidgetFilterSettings(context, id)
        Log.d(
            TAG,
            "provideGlance: appWidgetId=$appWidgetId glanceId=$id " +
                "storedCalendarIds=${settings.selectedCalendarIds} fontScaleStep=${settings.fontScaleStep} " +
                "backgroundOpacity=${settings.backgroundOpacity}",
        )
        val textSizes = resolveTextSizes(settings.fontScaleStep)
        val backgroundColor = ColorProvider(resolveBackgroundColor(context, settings.backgroundOpacity))

        val eventsByDay = when {
            !hasPermission -> emptyMap()
            // 사용자가 캘린더를 전부 해제한 경우: getEvents(calendarIds = emptyList())는 "필터 없음"으로 해석되어
            // 전체가 조회되므로, 빈 선택은 여기서 조회 자체를 건너뛰어 빈 결과로 처리한다.
            settings.selectedCalendarIds != null && settings.selectedCalendarIds.isEmpty() -> emptyMap()
            else -> try {
                val repository = CalendarRepository(context)
                val (start, end) = monthRangeMillis(yearMonth)
                val calendarIdsParam = settings.selectedCalendarIds?.toList()
                Log.d(TAG, "provideGlance: appWidgetId=$appWidgetId getEvents(calendarIds=$calendarIdsParam)")
                val events = repository.getEvents(start, end, calendarIds = calendarIdsParam)
                Log.d(TAG, "provideGlance: appWidgetId=$appWidgetId fetched ${events.size} event(s)")
                groupEventsByDay(events, yearMonth)
            } catch (e: SecurityException) {
                Log.e(TAG, "provideGlance: calendar access failed", e)
                emptyMap()
            }
        }

        // TODO: 임시 진단용 - 원인 확인 후 제거
        val diagnosticText = "id=$appWidgetId cals=${settings.selectedCalendarIds?.size ?: -1} " +
            "size=${settings.fontScaleStep}"

        provideContent {
            if (hasPermission) {
                RunCalCalendarWidgetContent(
                    today = today,
                    yearMonth = yearMonth,
                    eventsByDay = eventsByDay,
                    textSizes = textSizes,
                    backgroundColor = backgroundColor,
                    diagnosticText = diagnosticText,
                )
            } else {
                PermissionRequiredContent(
                    backgroundColor = backgroundColor,
                    bodySize = textSizes.body,
                    diagnosticText = diagnosticText,
                )
            }
        }
    }
}

@Composable
private fun PermissionRequiredContent(backgroundColor: ColorProvider, bodySize: TextUnit, diagnosticText: String) {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(backgroundColor)
            .cornerRadius(20.dp)
            .padding(16.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
            // TODO: 임시 진단용 - 원인 확인 후 제거
            DiagnosticText(diagnosticText)
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
}

/** TODO: 임시 진단용 - 위젯 인스턴스별 설정이 올바르게 적용되는지 확인 후 제거. */
@Composable
private fun DiagnosticText(text: String) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = RunCalWidgetColors.sunday,
            textAlign = TextAlign.Center,
        ),
        modifier = GlanceModifier.fillMaxWidth(),
    )
}

@Composable
private fun RunCalCalendarWidgetContent(
    today: LocalDate,
    yearMonth: YearMonth,
    eventsByDay: Map<Int, List<ScheduleEntry>>,
    textSizes: WidgetTextSizes,
    backgroundColor: ColorProvider,
    diagnosticText: String,
) {
    val weeks = buildMonthGrid(yearMonth, today, eventsByDay)
    val title = "${yearMonth.year}년 ${yearMonth.monthValue}월"

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(backgroundColor)
            .cornerRadius(20.dp)
            .padding(10.dp),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            // TODO: 임시 진단용 - 원인 확인 후 제거
            DiagnosticText(diagnosticText)
            Text(
                text = title,
                style = TextStyle(
                    fontSize = textSizes.title,
                    fontWeight = FontWeight.Bold,
                    color = RunCalWidgetColors.onBackground,
                    textAlign = TextAlign.Center,
                ),
                modifier = GlanceModifier.fillMaxWidth(),
            )

            Spacer(modifier = GlanceModifier.height(6.dp))

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

            Spacer(modifier = GlanceModifier.height(4.dp))

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

@Composable
private fun DayCell(day: CalendarDay, textSizes: WidgetTextSizes, modifier: GlanceModifier) {
    Column(
        modifier = modifier.padding(horizontal = 1.dp),
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
