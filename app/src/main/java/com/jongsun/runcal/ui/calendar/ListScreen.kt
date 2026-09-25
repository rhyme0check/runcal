package com.jongsun.runcal.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.occursOn
import com.jongsun.runcal.data.resolveEventColor
import com.jongsun.runcal.data.room.EventColorStyleEntity
import com.jongsun.runcal.data.source.EventSourceKind
import com.jongsun.runcal.data.weekRange
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

private enum class ListRangeType { THIS_WEEK, THIS_MONTH, NEXT_MONTH, CUSTOM }

/**
 * 프리셋으로 필터링된 일정을 기간별로 모아 목록으로 보여준다. 색상/굵기는 월간·일간과 같은
 * [resolveEventColor]를 써서 항상 일관되게 표시된다. 항목을 탭하면 P2 편집 다이얼로그가 뜬다
 * (Notion 항목은 읽기 전용 안내만). [onNavigateToDate]는 아직 다른 진입 경로가 쓴다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(viewModel: CalendarViewModel, onNavigateToDate: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val weekStartDay by viewModel.weekStartDay.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val notionDatabases by viewModel.notionDatabases.collectAsStateWithLifecycle()
    val colorStyles by viewModel.eventColorStyles.collectAsStateWithLifecycle()
    val isDarkTheme = isSystemInDarkTheme()
    val zone = remember { ZoneId.systemDefault() }

    var rangeType by remember { mutableStateOf(ListRangeType.THIS_WEEK) }
    var customStart by remember { mutableStateOf(viewModel.today) }
    var customEnd by remember { mutableStateOf(viewModel.today.plusDays(6)) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var events by remember { mutableStateOf<List<EventItem>>(emptyList()) }
    var editingEvent by remember { mutableStateOf<EventItem?>(null) }
    var creatingDate by remember { mutableStateOf<LocalDate?>(null) }

    val (rangeStart, rangeEndExclusive) = remember(rangeType, customStart, customEnd, weekStartDay, viewModel.today) {
        when (rangeType) {
            ListRangeType.THIS_WEEK -> weekRange(viewModel.today, weekStartDay)
            ListRangeType.THIS_MONTH -> {
                val ym = YearMonth.from(viewModel.today)
                ym.atDay(1) to ym.plusMonths(1).atDay(1)
            }
            ListRangeType.NEXT_MONTH -> {
                val ym = YearMonth.from(viewModel.today).plusMonths(1)
                ym.atDay(1) to ym.plusMonths(1).atDay(1)
            }
            ListRangeType.CUSTOM -> customStart to customEnd.plusDays(1)
        }
    }

    LaunchedEffect(rangeStart, rangeEndExclusive) {
        val startMillis = rangeStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = rangeEndExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        events = viewModel.eventsInRange(startMillis, endMillis)
    }

    val calendarNameById = remember(calendars) { calendars.associate { it.id to it.displayName } }
    val notionNameById = remember(notionDatabases) { notionDatabases.associate { it.id to it.displayName } }

    val filteredEvents = remember(events, query) {
        if (query.isBlank()) events else events.filter { it.title.contains(query, ignoreCase = true) }
    }

    val groupedByDate = remember(filteredEvents, rangeStart, rangeEndExclusive, zone) {
        generateSequence(rangeStart) { it.plusDays(1) }
            .takeWhile { it < rangeEndExclusive }
            .mapNotNull { date ->
                val dayEvents = filteredEvents.filter { it.occursOn(date, zone) }.sortedBy { it.begin }
                if (dayEvents.isEmpty()) null else date to dayEvents
            }
            .toList()
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = rangeType == ListRangeType.THIS_WEEK, onClick = { rangeType = ListRangeType.THIS_WEEK }, label = { Text("이번 주") })
            FilterChip(selected = rangeType == ListRangeType.THIS_MONTH, onClick = { rangeType = ListRangeType.THIS_MONTH }, label = { Text("이번 달") })
            FilterChip(selected = rangeType == ListRangeType.NEXT_MONTH, onClick = { rangeType = ListRangeType.NEXT_MONTH }, label = { Text("다음 달") })
            FilterChip(selected = rangeType == ListRangeType.CUSTOM, onClick = { rangeType = ListRangeType.CUSTOM }, label = { Text("임의 기간") })
        }

        if (rangeType == ListRangeType.CUSTOM) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { showStartPicker = true }) { Text("시작 $customStart") }
                OutlinedButton(onClick = { showEndPicker = true }) { Text("종료 $customEnd") }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("제목 검색") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "지우기") }
                }
            },
        )

        if (groupedByDate.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "표시할 일정이 없습니다", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                groupedByDate.forEach { (date, dayEvents) ->
                    item {
                        Text(
                            text = date.titleKorean(),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(dayEvents) { event ->
                        val sourceLabel = when (event.sourceKind) {
                            EventSourceKind.CALENDAR -> calendarNameById[event.calendarId] ?: "캘린더"
                            EventSourceKind.NOTION -> notionNameById[event.notionDatabaseId] ?: "Notion"
                        }
                        ListEventRow(
                            event = event,
                            referenceDate = date,
                            sourceLabel = sourceLabel,
                            colorStyles = colorStyles,
                            isDarkTheme = isDarkTheme,
                            zone = zone,
                            onClick = { editingEvent = event },
                        )
                    }
                }
                item { Spacer(modifier = Modifier.fillMaxWidth().padding(bottom = 88.dp)) }
            }
        }
    }
        FloatingActionButton(
            onClick = { creatingDate = viewModel.today },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, contentDescription = "일정 추가") }
    }

    if (editingEvent != null || creatingDate != null) {
        EventEditDialog(
            viewModel = viewModel,
            existing = editingEvent,
            initialDate = creatingDate ?: viewModel.today,
            onDismiss = { editingEvent = null; creatingDate = null },
        )
    }

    if (showStartPicker) {
        ListDatePickerField(
            initialDate = customStart,
            onDismiss = { showStartPicker = false },
            onConfirm = { picked ->
                customStart = picked
                if (customEnd.isBefore(picked)) customEnd = picked
                showStartPicker = false
            },
        )
    }
    if (showEndPicker) {
        ListDatePickerField(
            initialDate = customEnd,
            onDismiss = { showEndPicker = false },
            onConfirm = { picked ->
                customEnd = picked
                if (customStart.isAfter(picked)) customStart = picked
                showEndPicker = false
            },
        )
    }
}

@Composable
private fun ListEventRow(
    event: EventItem,
    referenceDate: LocalDate,
    sourceLabel: String,
    colorStyles: Map<String, EventColorStyleEntity>,
    isDarkTheme: Boolean,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    val resolved = remember(event, colorStyles, isDarkTheme) { resolveEventColor(event, colorStyles, isDarkTheme) }
    val timeLabel = remember(event, referenceDate) { eventTimeLabel(event, referenceDate, zone) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(10.dp)
                .background(Color(resolved.backgroundArgb), CircleShape),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = timeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SourceBadge(label = sourceLabel)
            }
            Text(
                text = event.title.ifBlank { "(제목 없음)" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (resolved.bold) FontWeight.Bold else FontWeight.Normal,
            )
            if (event.location.isNotBlank()) {
                Text(text = event.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SourceBadge(label: String) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListDatePickerField(initialDate: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val initialMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis != null) {
                    onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                } else {
                    onDismiss()
                }
            }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    ) {
        DatePicker(state = state)
    }
}
