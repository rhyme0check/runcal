package com.jongsun.runcal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private val WEEKDAY_LABELS = listOf("일", "월", "화", "수", "목", "금", "토")
private const val MAX_SCHEDULES_PER_DAY = 2

class RunCalCalendarWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            RunCalCalendarWidgetContent()
        }
    }
}

@Composable
private fun RunCalCalendarWidgetContent() {
    val today = LocalDate.now()
    val yearMonth = YearMonth.from(today)
    val weeks = buildMonthGrid(yearMonth, today)
    val title = "${yearMonth.year}년 ${yearMonth.monthValue}월"

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(RunCalWidgetColors.background)
            .cornerRadius(20.dp)
            .padding(10.dp),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Text(
                text = title,
                style = TextStyle(
                    fontSize = RunCalWidgetTextSizes.Title,
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
                            fontSize = RunCalWidgetTextSizes.WeekdayHeader,
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
private fun DayCell(day: CalendarDay, modifier: GlanceModifier) {
    Column(
        modifier = modifier.padding(horizontal = 1.dp),
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        val badgeModifier = GlanceModifier.size(16.dp).let { base ->
            if (day.isToday) {
                base.background(RunCalWidgetColors.todayBackground).cornerRadius(8.dp)
            } else {
                base
            }
        }

        Box(contentAlignment = Alignment.Center, modifier = badgeModifier) {
            Text(
                text = day.date.dayOfMonth.toString(),
                style = TextStyle(
                    fontSize = RunCalWidgetTextSizes.DayNumber,
                    fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                    color = dayNumberColor(day),
                    textAlign = TextAlign.Center,
                ),
            )
        }

        day.schedules.take(MAX_SCHEDULES_PER_DAY).forEach { schedule ->
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
                        fontSize = RunCalWidgetTextSizes.Schedule,
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
