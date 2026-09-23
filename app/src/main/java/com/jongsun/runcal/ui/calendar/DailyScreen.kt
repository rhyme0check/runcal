package com.jongsun.runcal.ui.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.isBarWorthy
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun DailyScreen(viewModel: CalendarViewModel, modifier: Modifier = Modifier) {
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val monthCache by viewModel.monthCache.collectAsStateWithLifecycle()

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

    HorizontalPager(state = pagerState, modifier = modifier.fillMaxSize()) { page ->
        val date = viewModel.dateForPage(page)
        LaunchedEffect(date) { viewModel.ensureMonthLoaded(YearMonth.from(date)) }
        val dayEvents = remember(monthCache, date) { viewModel.eventsForDate(monthCache, date) }
        DayAgendaContent(date = date, events = dayEvents)
    }
}

@Composable
private fun DayAgendaContent(date: LocalDate, events: List<EventItem>, modifier: Modifier = Modifier) {
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
            items(allDayEvents) { event -> EventRow(event = event, referenceDate = date) }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        }
        items(sortedTimed) { event -> EventRow(event = event, referenceDate = date) }
        item {
            // 리스트 하단 여백
            Spacer(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp))
        }
    }
}
