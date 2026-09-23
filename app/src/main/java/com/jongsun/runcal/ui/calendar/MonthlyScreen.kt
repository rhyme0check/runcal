package com.jongsun.runcal.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.isBarWorthy
import com.jongsun.runcal.data.occursOn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun MonthlyScreen(viewModel: CalendarViewModel, modifier: Modifier = Modifier) {
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val weekStartDay by viewModel.weekStartDay.collectAsStateWithLifecycle()
    val monthCache by viewModel.monthCache.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(
        initialPage = viewModel.pageForYearMonth(YearMonth.from(selectedDate)),
        pageCount = { MONTH_PAGE_COUNT },
    )

    // settledPage를 써야 장거리 animateScrollToPage(월 점프) 도중 중간 페이지 값이
    // 앱바 제목을 스쳐 지나가듯 계속 갱신하는 것을 막을 수 있다.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            viewModel.setVisibleYearMonth(viewModel.yearMonthForPage(page))
        }
    }

    LaunchedEffect(selectedDate) {
        val targetPage = viewModel.pageForYearMonth(YearMonth.from(selectedDate))
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        WeekdayHeaderRow(weekStartDay)
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1.3f)) { page ->
            val yearMonth = viewModel.yearMonthForPage(page)
            LaunchedEffect(yearMonth) { viewModel.ensureMonthLoaded(yearMonth) }
            val events = monthCache[yearMonth].orEmpty()
            MonthGrid(
                yearMonth = yearMonth,
                weekStartDay = weekStartDay,
                events = events,
                today = viewModel.today,
                selectedDate = selectedDate,
                onDateClick = { viewModel.selectDate(it) },
            )
        }
        HorizontalDivider()
        SelectedDateAgenda(
            date = selectedDate,
            events = remember(monthCache, selectedDate) { viewModel.eventsForDate(monthCache, selectedDate) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WeekdayHeaderRow(weekStartDay: DayOfWeek, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        weekdayOrder(weekStartDay).forEach { dow ->
            Text(
                text = dow.koreanShortLabel(),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                color = dayOfWeekColor(dow),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun MonthGrid(
    yearMonth: YearMonth,
    weekStartDay: DayOfWeek,
    events: List<EventItem>,
    today: LocalDate,
    selectedDate: LocalDate,
    onDateClick: (LocalDate) -> Unit,
) {
    val weeks = remember(yearMonth, weekStartDay) { buildMonthGridWeeks(yearMonth, weekStartDay) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        weeks.forEach { week ->
            val lanes = remember(week, events) { computeWeekBars(week, events) }
            WeekRow(
                week = week,
                lanes = lanes,
                allEvents = events,
                today = today,
                selectedDate = selectedDate,
                onDateClick = onDateClick,
            )
        }
    }
}

@Composable
private fun WeekRow(
    week: MonthGridWeek,
    lanes: List<List<EventBar?>>,
    allEvents: List<EventItem>,
    today: LocalDate,
    selectedDate: LocalDate,
    onDateClick: (LocalDate) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            week.days.forEach { day ->
                DayNumberCell(
                    day = day,
                    isToday = day.date == today,
                    isSelected = day.date == selectedDate,
                    onClick = { onDateClick(day.date) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        lanes.forEach { lane ->
            Row(modifier = Modifier.fillMaxWidth().height(16.dp).padding(vertical = 1.dp)) {
                for (col in 0 until DAYS_IN_WEEK) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        lane.getOrNull(col)?.let { bar -> EventBarChip(bar) }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            week.days.forEach { day ->
                DayTimedEventsPreview(
                    events = remember(allEvents, day.date) {
                        allEvents.filter { !it.isBarWorthy() && it.occursOn(day.date) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DayNumberCell(
    day: MonthGridDay,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor = when {
        isToday -> MaterialTheme.colorScheme.onPrimary
        else -> dayOfWeekColor(day.date.dayOfWeek, dimmed = !day.isCurrentMonth)
    }
    Box(
        modifier = modifier
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .then(
                    when {
                        isToday -> Modifier.background(MaterialTheme.colorScheme.primary)
                        isSelected -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else -> Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = day.date.dayOfMonth.toString(),
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EventBarChip(bar: EventBar) {
    val shape = RoundedCornerShape(
        topStart = if (bar.isTrueStart) 6.dp else 0.dp,
        bottomStart = if (bar.isTrueStart) 6.dp else 0.dp,
        topEnd = if (bar.isTrueEnd) 6.dp else 0.dp,
        bottomEnd = if (bar.isTrueEnd) 6.dp else 0.dp,
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = if (bar.isTrueStart) 1.dp else 0.dp, end = if (bar.isTrueEnd) 1.dp else 0.dp)
            .background(Color(bar.event.color), shape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (bar.isTrueStart) {
            Text(
                text = bar.event.title,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DayTimedEventsPreview(events: List<EventItem>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 1.dp)) {
        events.take(2).forEach { event ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(Color(event.color), CircleShape),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SelectedDateAgenda(date: LocalDate, events: List<EventItem>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = date.titleKorean(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (events.isEmpty()) {
            Text(text = "일정이 없습니다", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn {
                items(events.sortedBy { it.begin }) { event ->
                    EventRow(event = event, referenceDate = date)
                }
            }
        }
    }
}
