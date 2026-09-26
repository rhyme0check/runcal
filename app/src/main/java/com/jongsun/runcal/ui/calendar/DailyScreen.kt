package com.jongsun.runcal.ui.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.isBarWorthy
import com.jongsun.runcal.data.room.EventColorStyleEntity
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun DailyScreen(
    viewModel: CalendarViewModel,
    modifier: Modifier = Modifier,
    autoOpenEventId: Long? = null,
    autoOpenOccurrenceBegin: Long? = null,
    onAutoOpenEventConsumed: () -> Unit = {},
) {
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val monthCache by viewModel.monthCache.collectAsStateWithLifecycle()
    val colorStyles by viewModel.eventColorStyles.collectAsStateWithLifecycle()
    val isDarkTheme = isSystemInDarkTheme()

    var editingEvent by remember { mutableStateOf<EventItem?>(null) }
    var creatingDate by remember { mutableStateOf<LocalDate?>(null) }

    // 알림 탭 전용 진입점 — 날짜 선택만으로는 그 회차의 상세까지 열리지 않으므로, 대상 이벤트를
    // 직접 읽어와 편집 다이얼로그를 강제로 띄운다.
    LaunchedEffect(autoOpenEventId) {
        val id = autoOpenEventId ?: return@LaunchedEffect
        val detail = viewModel.getEventDetail(id)
        if (detail != null) {
            // 반복 일정은 마스터의 시작/종료가 아니라 알림이 가리킨 회차의 시각으로 열어야 한다.
            editingEvent = if (autoOpenOccurrenceBegin != null && autoOpenOccurrenceBegin != detail.begin) {
                detail.copy(begin = autoOpenOccurrenceBegin, end = autoOpenOccurrenceBegin + (detail.end - detail.begin))
            } else {
                detail
            }
        }
        onAutoOpenEventConsumed()
    }

    val pagerState = rememberPagerState(
        initialPage = viewModel.pageForDate(selectedDate),
        pageCount = { DAY_PAGE_COUNT },
    )

    // settledPage(스크롤/애니메이션이 완전히 멈춘 뒤의 페이지)를 써야 한다.
    // currentPage는 requestJumpToMonth 등으로 인한 장거리 animateScrollToPage 도중에도
    // 중간값을 계속 흘려보내는데, 그걸 그대로 selectedDate에 반영하면 애니메이션이 채 끝나기도
    // 전에 selectedDate가 중간 날짜로 덮어써져 목표 날짜에 도달하지 못하고 멈추는 문제가 있었다.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            viewModel.selectDateFromPaging(viewModel.dateForPage(page))
        }
    }

    LaunchedEffect(selectedDate) {
        val targetPage = viewModel.pageForDate(selectedDate)
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val date = viewModel.dateForPage(page)
            LaunchedEffect(date) { viewModel.ensureMonthLoaded(YearMonth.from(date)) }
            val dayEvents = remember(monthCache, date) { viewModel.eventsForDate(monthCache, date) }
            DayAgendaContent(
                date = date,
                events = dayEvents,
                colorStyles = colorStyles,
                isDarkTheme = isDarkTheme,
                onEventClick = { event -> editingEvent = event },
            )
        }
        FloatingActionButton(
            onClick = { creatingDate = selectedDate },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, contentDescription = "일정 추가") }
    }

    if (editingEvent != null || creatingDate != null) {
        EventEditDialog(
            viewModel = viewModel,
            existing = editingEvent,
            initialDate = creatingDate ?: selectedDate,
            onDismiss = { editingEvent = null; creatingDate = null },
        )
    }
}

@Composable
private fun DayAgendaContent(
    date: LocalDate,
    events: List<EventItem>,
    colorStyles: Map<String, EventColorStyleEntity>,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    onEventClick: (EventItem) -> Unit = {},
) {
    val (allDayEvents, timedEvents) = remember(events) { events.partition { it.isBarWorthy() } }
    val sortedTimed = remember(timedEvents) { timedEvents.sortedBy { it.begin } }

    if (allDayEvents.isEmpty() && sortedTimed.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "일정이 없습니다", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (allDayEvents.isNotEmpty()) {
            item {
                Text(
                    text = "종일",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(allDayEvents) { event ->
                EventRow(event = event, referenceDate = date, colorStyles = colorStyles, isDarkTheme = isDarkTheme, onClick = onEventClick)
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        }
        items(sortedTimed) { event ->
            EventRow(event = event, referenceDate = date, colorStyles = colorStyles, isDarkTheme = isDarkTheme, onClick = onEventClick)
        }
        item {
            // 리스트 하단 여백(FAB에 안 가리게 여유를 더 둔다)
            Spacer(modifier = Modifier.fillMaxWidth().padding(bottom = 88.dp))
        }
    }
}
