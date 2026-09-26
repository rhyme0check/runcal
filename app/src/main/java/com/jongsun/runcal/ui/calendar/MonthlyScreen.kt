package com.jongsun.runcal.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.dateRange
import com.jongsun.runcal.data.isBarWorthy
import com.jongsun.runcal.data.occursOn
import com.jongsun.runcal.data.resolveEventColor
import com.jongsun.runcal.data.room.EventColorStyleEntity
import com.jongsun.runcal.data.special.SpecialDayFlags
import com.jongsun.runcal.data.special.SpecialDaySnapshot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthlyScreen(viewModel: CalendarViewModel, modifier: Modifier = Modifier) {
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val weekStartDay by viewModel.weekStartDay.collectAsStateWithLifecycle()
    val monthCache by viewModel.monthCache.collectAsStateWithLifecycle()
    val colorStyles by viewModel.eventColorStyles.collectAsStateWithLifecycle()
    val specialFlags by viewModel.specialFlags.collectAsStateWithLifecycle()
    val specialSnapshot by viewModel.specialSnapshot.collectAsStateWithLifecycle()
    val isDarkTheme = isSystemInDarkTheme()

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

    // 탭한 날짜의 일정을 보여줄 하단 시트. null이면 시트를 숨긴다.
    var sheetDate by remember { mutableStateOf<LocalDate?>(null) }
    // P2 편집: 생성 시 날짜만 있고 대상 일정은 없음(null), 수정 시 둘 다 있음.
    var editingEvent by remember { mutableStateOf<EventItem?>(null) }
    var creatingDate by remember { mutableStateOf<LocalDate?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        WeekdayHeaderRow(weekStartDay)
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            val yearMonth = viewModel.yearMonthForPage(page)
            LaunchedEffect(yearMonth) { viewModel.ensureMonthLoaded(yearMonth) }
            val events = monthCache[yearMonth].orEmpty()
            MonthGrid(
                yearMonth = yearMonth,
                weekStartDay = weekStartDay,
                events = events,
                today = viewModel.today,
                selectedDate = selectedDate,
                colorStyles = colorStyles,
                isDarkTheme = isDarkTheme,
                specialFlags = specialFlags,
                specialSnapshot = specialSnapshot,
                onDateClick = { date ->
                    viewModel.selectDate(date)
                    sheetDate = date
                },
                onDateLongClick = { date ->
                    viewModel.selectDate(date)
                    creatingDate = date
                },
            )
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    sheetDate?.let { date ->
        ModalBottomSheet(onDismissRequest = { sheetDate = null }, sheetState = sheetState) {
            SelectedDateAgenda(
                date = date,
                events = remember(monthCache, date) { viewModel.eventsForDate(monthCache, date) },
                colorStyles = colorStyles,
                isDarkTheme = isDarkTheme,
                specialLabel = specialSnapshot.describe(date, specialFlags),
                modifier = Modifier.fillMaxWidth(),
                onAddClick = { creatingDate = date },
                onEventClick = { event -> editingEvent = event },
            )
        }
    }

    if (editingEvent != null || creatingDate != null) {
        EventEditDialog(
            viewModel = viewModel,
            existing = editingEvent,
            initialDate = creatingDate ?: editingEvent?.dateRange()?.start ?: selectedDate,
            onDismiss = { editingEvent = null; creatingDate = null },
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
    colorStyles: Map<String, EventColorStyleEntity>,
    isDarkTheme: Boolean,
    specialFlags: SpecialDayFlags,
    specialSnapshot: SpecialDaySnapshot,
    onDateClick: (LocalDate) -> Unit,
    onDateLongClick: (LocalDate) -> Unit,
) {
    val weeks = remember(yearMonth, weekStartDay) { buildMonthGridWeeks(yearMonth, weekStartDay) }
    // 공휴일/절기 이름은 가짜 종일 일정으로 만들어 일정 막대와 같은 레인 패킹에 태운다(최상단 레인 우선).
    val barEvents = remember(events, weeks, specialSnapshot, specialFlags) {
        val (start, endExclusive) = monthGridDateRange(weeks)
        events + specialSnapshot.specialEvents(start, endExclusive, specialFlags)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        weeks.forEach { week ->
            val lanes = remember(week, barEvents) { computeWeekBars(week, barEvents) }
            WeekRow(
                week = week,
                lanes = lanes,
                allEvents = events,
                today = today,
                selectedDate = selectedDate,
                colorStyles = colorStyles,
                isDarkTheme = isDarkTheme,
                specialFlags = specialFlags,
                specialSnapshot = specialSnapshot,
                onDateClick = onDateClick,
                onDateLongClick = onDateLongClick,
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
    colorStyles: Map<String, EventColorStyleEntity>,
    isDarkTheme: Boolean,
    specialFlags: SpecialDayFlags,
    specialSnapshot: SpecialDaySnapshot,
    onDateClick: (LocalDate) -> Unit,
    onDateLongClick: (LocalDate) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            week.days.forEach { day ->
                DayNumberCell(
                    day = day,
                    isToday = day.date == today,
                    isSelected = day.date == selectedDate,
                    isHoliday = specialFlags.showHolidays && specialSnapshot.isHoliday(day.date),
                    lunarLabel = if (specialFlags.showLunar) specialSnapshot.lunarMarker(day.date) else null,
                    onClick = { onDateClick(day.date) },
                    onLongClick = { onDateLongClick(day.date) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        lanes.forEach { lane ->
            Row(modifier = Modifier.fillMaxWidth().height(16.dp).padding(vertical = 1.dp)) {
                for (col in 0 until DAYS_IN_WEEK) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        lane.getOrNull(col)?.let { bar -> EventBarChip(bar, colorStyles, isDarkTheme) }
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
                    colorStyles = colorStyles,
                    isDarkTheme = isDarkTheme,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayNumberCell(
    day: MonthGridDay,
    isToday: Boolean,
    isSelected: Boolean,
    isHoliday: Boolean,
    lunarLabel: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor = when {
        isToday -> MaterialTheme.colorScheme.onPrimary
        // 공휴일은 요일과 무관하게 일요일과 같은 빨강.
        isHoliday -> dayOfWeekColor(DayOfWeek.SUNDAY, dimmed = !day.isCurrentMonth)
        else -> dayOfWeekColor(day.date.dayOfWeek, dimmed = !day.isCurrentMonth)
    }
    // 삭·망·그믐 표식은 칸 우상단에 겹쳐 그린다 — 별도 행을 잡지 않아 칸 높이/막대 배치가 표식 유무와 무관하다.
    Box(
        modifier = modifier
            .padding(vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
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
        if (lunarLabel != null) {
            Text(
                text = lunarLabel,
                modifier = Modifier.align(Alignment.TopEnd),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (day.isCurrentMonth) 1f else 0.5f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EventBarChip(bar: EventBar, colorStyles: Map<String, EventColorStyleEntity>, isDarkTheme: Boolean) {
    val resolved = remember(bar.event, colorStyles, isDarkTheme) { resolveEventColor(bar.event, colorStyles, isDarkTheme) }
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
            .background(Color(resolved.backgroundArgb), shape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (bar.isTrueStart) {
            Text(
                text = bar.event.title,
                color = Color(resolved.textArgb),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (resolved.bold) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DayTimedEventsPreview(
    events: List<EventItem>,
    colorStyles: Map<String, EventColorStyleEntity>,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 1.dp)) {
        events.take(2).forEach { event ->
            val resolved = remember(event, colorStyles, isDarkTheme) { resolveEventColor(event, colorStyles, isDarkTheme) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(Color(resolved.backgroundArgb), CircleShape),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (resolved.bold) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SelectedDateAgenda(
    date: LocalDate,
    events: List<EventItem>,
    colorStyles: Map<String, EventColorStyleEntity>,
    isDarkTheme: Boolean,
    specialLabel: String? = null,
    modifier: Modifier = Modifier,
    onAddClick: () -> Unit = {},
    onEventClick: (EventItem) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = date.titleKorean(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            IconButton(onClick = onAddClick) { Icon(Icons.Default.Add, contentDescription = "일정 추가") }
        }
        if (specialLabel != null) {
            Text(
                text = specialLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (events.isEmpty()) {
            Text(text = "일정이 없습니다", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn {
                items(events.sortedBy { it.begin }) { event ->
                    EventRow(
                        event = event,
                        referenceDate = date,
                        colorStyles = colorStyles,
                        isDarkTheme = isDarkTheme,
                        onClick = onEventClick,
                    )
                }
            }
        }
    }
}
