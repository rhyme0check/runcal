package com.jongsun.runcal.ui.calendar

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.MonthlyRecurrenceType
import com.jongsun.runcal.data.RecurrenceEndType
import com.jongsun.runcal.data.RecurrenceFrequency
import com.jongsun.runcal.data.RecurrenceRule
import com.jongsun.runcal.data.dateRange
import com.jongsun.runcal.data.parseRRule
import com.jongsun.runcal.data.source.EventSourceKind
import com.jongsun.runcal.data.toRRuleString
import com.jongsun.runcal.data.weekdayOrdinalInMonth
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch

private val REMINDER_PRESET_MINUTES = listOf(0, 5, 10, 15, 30, 60, 120, 1440)
private const val MAX_REMINDERS = 5

private fun reminderLabel(minutes: Int): String = when {
    minutes == 0 -> "정시"
    minutes < 60 -> "${minutes}분 전"
    minutes < 1440 -> "${minutes / 60}시간 전"
    else -> "${minutes / 1440}일 전"
}

private fun frequencyLabel(frequency: RecurrenceFrequency): String = when (frequency) {
    RecurrenceFrequency.NONE -> "반복 안 함"
    RecurrenceFrequency.DAILY -> "매일"
    RecurrenceFrequency.WEEKLY -> "매주"
    RecurrenceFrequency.MONTHLY -> "매월"
    RecurrenceFrequency.YEARLY -> "매년"
}

private fun intervalUnitLabel(frequency: RecurrenceFrequency): String = when (frequency) {
    RecurrenceFrequency.DAILY -> "일"
    RecurrenceFrequency.WEEKLY -> "주"
    RecurrenceFrequency.MONTHLY -> "개월"
    RecurrenceFrequency.YEARLY -> "년"
    RecurrenceFrequency.NONE -> ""
}

private fun weekdayShortLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "월"
    DayOfWeek.TUESDAY -> "화"
    DayOfWeek.WEDNESDAY -> "수"
    DayOfWeek.THURSDAY -> "목"
    DayOfWeek.FRIDAY -> "금"
    DayOfWeek.SATURDAY -> "토"
    DayOfWeek.SUNDAY -> "일"
}

private fun ordinalWord(ordinal: Int): String = when (ordinal) {
    -1 -> "마지막"
    1 -> "첫째"
    2 -> "둘째"
    3 -> "셋째"
    4 -> "넷째"
    else -> "${ordinal}번째"
}

/** 저장 버튼 위 요약 한 줄. 규칙이 실제로 어떻게 해석됐는지 저장 전에 눈으로 확인할 수 있게 한다. */
private fun recurrenceSummary(rule: RecurrenceRule, startDate: LocalDate): String {
    if (rule.frequency == RecurrenceFrequency.NONE) return ""
    val intervalPrefix = if (rule.interval > 1) "${rule.interval}${intervalUnitLabel(rule.frequency)}마다" else frequencyLabel(rule.frequency)
    val detail = when (rule.frequency) {
        RecurrenceFrequency.WEEKLY -> {
            val days = rule.byWeekdays.ifEmpty { setOf(startDate.dayOfWeek) }
                .sortedBy { it.value }
                .joinToString(", ") { weekdayShortLabel(it) }
            " $days"
        }
        RecurrenceFrequency.MONTHLY -> if (rule.monthlyType == MonthlyRecurrenceType.BY_DAY_OF_MONTH) {
            " ${startDate.dayOfMonth}일"
        } else {
            " ${ordinalWord(weekdayOrdinalInMonth(startDate))} ${weekdayShortLabel(startDate.dayOfWeek)}요일"
        }
        else -> ""
    }
    val end = when (rule.endType) {
        RecurrenceEndType.NEVER -> ""
        RecurrenceEndType.COUNT -> ", ${rule.count}회"
        RecurrenceEndType.UNTIL -> rule.untilDate?.let { ", ${it}까지" } ?: ""
    }
    return "$intervalPrefix$detail$end"
}

/**
 * 일정 생성/수정 진입점. [existing]이 null이면 생성(날짜는 [initialDate]로 미리 채움), 아니면
 * 수정이다. [existing]이 Notion 항목이면 편집 폼 대신 읽기 전용 안내만 보여준다 — Notion은
 * 읽기 전용 연동이라 여기서 쓰기를 시도하지 않는다.
 */
@Composable
fun EventEditDialog(viewModel: CalendarViewModel, existing: EventItem?, initialDate: LocalDate, onDismiss: () -> Unit) {
    if (existing != null && existing.sourceKind == EventSourceKind.NOTION) {
        NotionReadOnlyDialog(event = existing, onDismiss = onDismiss)
        return
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            EventEditContent(viewModel = viewModel, existing = existing, initialDate = initialDate, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun NotionReadOnlyDialog(event: EventItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.title.ifBlank { "(제목 없음)" }) },
        text = {
            Column {
                Text(
                    text = "이 일정은 Notion에서 가져온 항목이라 앱에서 편집할 수 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (event.location.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = event.location, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !event.notionUrl.isNullOrBlank(),
                onClick = {
                    val url = event.notionUrl ?: return@TextButton
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    onDismiss()
                },
            ) { Text("Notion에서 열기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventEditContent(
    viewModel: CalendarViewModel,
    existing: EventItem?,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
) {
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val writableCalendars = remember(calendars) { calendars.filter { it.isWritable } }
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf(existing?.title ?: "") }
    var allDay by remember { mutableStateOf(existing?.allDay ?: false) }
    var startDate by remember { mutableStateOf(existing?.dateRange(zone)?.start ?: initialDate) }
    var endDate by remember { mutableStateOf(existing?.dateRange(zone)?.endInclusive ?: initialDate) }
    var startTime by remember {
        mutableStateOf(existing?.takeIf { !it.allDay }?.let { Instant.ofEpochMilli(it.begin).atZone(zone).toLocalTime() } ?: LocalTime.of(9, 0))
    }
    var endTime by remember {
        mutableStateOf(existing?.takeIf { !it.allDay }?.let { Instant.ofEpochMilli(it.end).atZone(zone).toLocalTime() } ?: LocalTime.of(10, 0))
    }
    var location by remember { mutableStateOf(existing?.location ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var selectedCalendarId by remember { mutableStateOf(existing?.calendarId ?: writableCalendars.firstOrNull()?.id) }
    var reminderMinutes by remember { mutableStateOf<List<Int>>(emptyList()) }
    var recurrenceRule by remember { mutableStateOf(parseRRule(existing?.rrule)) }
    var showReminderMenu by remember { mutableStateOf(false) }
    var showFrequencyMenu by remember { mutableStateOf(false) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var showUntilDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(existing?.id) {
        if (existing != null) reminderMinutes = viewModel.getReminders(existing.id)
        // Instances 조회에서 얻은 begin/end는 탭한 회차의 시각이라 반복 일정의 진짜 DTSTART/기간과
        // 다를 수 있다 — 반복 규칙이 있으면 마스터 행을 다시 읽어 시작 날짜/시간을 정확히 맞춘다.
        if (existing != null && !existing.rrule.isNullOrBlank()) {
            val detail = viewModel.getEventDetail(existing.id) ?: return@LaunchedEffect
            startDate = Instant.ofEpochMilli(detail.begin).atZone(if (detail.allDay) ZoneOffset.UTC else zone).toLocalDate()
            endDate = Instant.ofEpochMilli(detail.end).atZone(if (detail.allDay) ZoneOffset.UTC else zone)
                .let { if (detail.allDay) it.toLocalDate().minusDays(1) else it.toLocalDate() }
            if (!detail.allDay) {
                startTime = Instant.ofEpochMilli(detail.begin).atZone(zone).toLocalTime()
                endTime = Instant.ofEpochMilli(detail.end).atZone(zone).toLocalTime()
            }
        }
    }

    val endBeforeStart = remember(startDate, endDate, startTime, endTime, allDay) {
        val s = if (allDay) startDate.atStartOfDay() else startDate.atTime(startTime)
        val e = if (allDay) endDate.atStartOfDay() else endDate.atTime(endTime)
        e.isBefore(s)
    }
    val canSave = title.isNotBlank() && selectedCalendarId != null && !endBeforeStart

    fun computeMillis(): Pair<Long, Long> = if (allDay) {
        startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to
            endDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } else {
        startDate.atTime(startTime).atZone(zone).toInstant().toEpochMilli() to
            endDate.atTime(endTime).atZone(zone).toInstant().toEpochMilli()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = if (existing == null) "일정 추가" else "일정 수정", style = MaterialTheme.typography.titleLarge)
            Row {
                if (existing != null) {
                    IconButton(onClick = { showDeleteConfirm = true }) { Icon(Icons.Default.Delete, contentDescription = "삭제") }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "닫기") }
            }
        }

        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("제목") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "종일", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = allDay, onCheckedChange = { allDay = it })
            }
            Spacer(modifier = Modifier.height(8.dp))

            Text(text = "시작", style = MaterialTheme.typography.labelMedium)
            Row(modifier = Modifier.padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showStartDatePicker = true }) { Text("$startDate") }
                if (!allDay) OutlinedButton(onClick = { showStartTimePicker = true }) { Text(startTime.toTimeLabel()) }
            }

            Text(text = "종료", style = MaterialTheme.typography.labelMedium)
            Row(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showEndDatePicker = true }) { Text("$endDate") }
                if (!allDay) OutlinedButton(onClick = { showEndTimePicker = true }) { Text(endTime.toTimeLabel()) }
            }
            if (endBeforeStart) {
                Text(
                    text = "종료가 시작보다 빠릅니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))

            Text(text = "반복", style = MaterialTheme.typography.labelMedium)
            Box(modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = { showFrequencyMenu = true }) { Text(frequencyLabel(recurrenceRule.frequency)) }
                DropdownMenu(expanded = showFrequencyMenu, onDismissRequest = { showFrequencyMenu = false }) {
                    RecurrenceFrequency.entries.forEach { freq ->
                        DropdownMenuItem(
                            text = { Text(frequencyLabel(freq)) },
                            onClick = {
                                recurrenceRule = recurrenceRule.copy(frequency = freq)
                                showFrequencyMenu = false
                            },
                        )
                    }
                }
            }

            if (recurrenceRule.frequency != RecurrenceFrequency.NONE) {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = recurrenceRule.interval.toString(),
                        onValueChange = { text ->
                            val n = text.filter { it.isDigit() }.toIntOrNull()
                            recurrenceRule = recurrenceRule.copy(interval = (n ?: 1).coerceIn(1, 99))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(72.dp),
                    )
                    Text(text = "${intervalUnitLabel(recurrenceRule.frequency)}마다")
                }

                if (recurrenceRule.frequency == RecurrenceFrequency.WEEKLY) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DayOfWeek.entries.forEach { day ->
                            val selected = day in recurrenceRule.byWeekdays.ifEmpty { setOf(startDate.dayOfWeek) }
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val current = recurrenceRule.byWeekdays.ifEmpty { setOf(startDate.dayOfWeek) }
                                    val updated = if (selected) current - day else current + day
                                    recurrenceRule = recurrenceRule.copy(byWeekdays = updated.ifEmpty { setOf(day) })
                                },
                                label = { Text(weekdayShortLabel(day)) },
                            )
                        }
                    }
                }

                if (recurrenceRule.frequency == RecurrenceFrequency.MONTHLY) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        FilterChip(
                            selected = recurrenceRule.monthlyType == MonthlyRecurrenceType.BY_DAY_OF_MONTH,
                            onClick = { recurrenceRule = recurrenceRule.copy(monthlyType = MonthlyRecurrenceType.BY_DAY_OF_MONTH) },
                            label = { Text("매월 ${startDate.dayOfMonth}일") },
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        FilterChip(
                            selected = recurrenceRule.monthlyType == MonthlyRecurrenceType.BY_WEEKDAY_ORDINAL,
                            onClick = { recurrenceRule = recurrenceRule.copy(monthlyType = MonthlyRecurrenceType.BY_WEEKDAY_ORDINAL) },
                            label = {
                                val ordinal = ordinalWord(weekdayOrdinalInMonth(startDate))
                                Text("매월 $ordinal ${weekdayShortLabel(startDate.dayOfWeek)}요일")
                            },
                        )
                    }
                }

                Text(text = "종료 조건", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp))
                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = recurrenceRule.endType == RecurrenceEndType.NEVER,
                        onClick = { recurrenceRule = recurrenceRule.copy(endType = RecurrenceEndType.NEVER) },
                        label = { Text("없음") },
                    )
                    FilterChip(
                        selected = recurrenceRule.endType == RecurrenceEndType.COUNT,
                        onClick = { recurrenceRule = recurrenceRule.copy(endType = RecurrenceEndType.COUNT) },
                        label = { Text("횟수") },
                    )
                    FilterChip(
                        selected = recurrenceRule.endType == RecurrenceEndType.UNTIL,
                        onClick = {
                            recurrenceRule = recurrenceRule.copy(endType = RecurrenceEndType.UNTIL)
                            if (recurrenceRule.untilDate == null) showUntilDatePicker = true
                        },
                        label = { Text("날짜") },
                    )
                }
                when (recurrenceRule.endType) {
                    RecurrenceEndType.COUNT -> Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = recurrenceRule.count.toString(),
                            onValueChange = { text ->
                                val n = text.filter { it.isDigit() }.toIntOrNull()
                                recurrenceRule = recurrenceRule.copy(count = (n ?: 1).coerceIn(1, 999))
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(72.dp),
                        )
                        Text(text = "회 반복")
                    }
                    RecurrenceEndType.UNTIL -> OutlinedButton(
                        modifier = Modifier.padding(top = 8.dp),
                        onClick = { showUntilDatePicker = true },
                    ) { Text(recurrenceRule.untilDate?.toString() ?: "날짜 선택") }
                    RecurrenceEndType.NEVER -> {}
                }

                Text(
                    text = recurrenceSummary(recurrenceRule, startDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("장소") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("메모") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "소속 캘린더", style = MaterialTheme.typography.labelMedium)
            if (writableCalendars.isEmpty()) {
                Text(
                    text = "쓸 수 있는 캘린더가 없습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    writableCalendars.forEach { calendar ->
                        FilterChip(
                            selected = selectedCalendarId == calendar.id,
                            onClick = { selectedCalendarId = calendar.id },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier.size(10.dp).background(Color(calendar.color), CircleShape),
                                )
                            },
                            label = { Text(calendar.displayName) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "알림 (최대 ${MAX_REMINDERS}개)", style = MaterialTheme.typography.labelMedium)
                Box {
                    TextButton(enabled = reminderMinutes.size < MAX_REMINDERS, onClick = { showReminderMenu = true }) { Text("알림 추가") }
                    DropdownMenu(expanded = showReminderMenu, onDismissRequest = { showReminderMenu = false }) {
                        REMINDER_PRESET_MINUTES.filter { it !in reminderMinutes }.forEach { minutes ->
                            DropdownMenuItem(
                                text = { Text(reminderLabel(minutes)) },
                                onClick = {
                                    reminderMinutes = (reminderMinutes + minutes).sorted()
                                    showReminderMenu = false
                                },
                            )
                        }
                    }
                }
            }
            if (reminderMinutes.isNotEmpty()) {
                Row(modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    reminderMinutes.forEach { minutes ->
                        ReminderChip(label = reminderLabel(minutes), onRemove = { reminderMinutes = reminderMinutes - minutes })
                    }
                }
            }

            errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), onClick = onDismiss, enabled = !saving) { Text("취소") }
            Button(
                modifier = Modifier.weight(1f),
                enabled = canSave && !saving,
                onClick = {
                    val calendarId = selectedCalendarId ?: return@Button
                    scope.launch {
                        saving = true
                        val (startMillis, endMillis) = computeMillis()
                        val rrule = recurrenceRule.toRRuleString(startDate)
                        val ok = if (existing == null) {
                            viewModel.createLocalEvent(calendarId, title.trim(), startMillis, endMillis, allDay, location.trim(), description.trim(), reminderMinutes, rrule) > 0
                        } else {
                            viewModel.updateLocalEvent(existing.id, title.trim(), startMillis, endMillis, allDay, location.trim(), description.trim(), reminderMinutes, rrule) > 0
                        }
                        saving = false
                        if (ok) onDismiss() else errorMessage = "저장하지 못했습니다. 캘린더 권한을 확인해주세요."
                    }
                },
            ) { Text(if (saving) "저장 중..." else "저장") }
        }
    }

    if (showStartDatePicker) {
        EventDatePickerDialog(
            initialDate = startDate,
            onDismiss = { showStartDatePicker = false },
            onConfirm = { picked ->
                startDate = picked
                if (endDate.isBefore(picked)) endDate = picked
                showStartDatePicker = false
            },
        )
    }
    if (showEndDatePicker) {
        EventDatePickerDialog(
            initialDate = endDate,
            onDismiss = { showEndDatePicker = false },
            onConfirm = { picked ->
                endDate = picked
                if (startDate.isAfter(picked)) startDate = picked
                showEndDatePicker = false
            },
        )
    }
    if (showStartTimePicker) {
        EventTimePickerDialog(
            initialTime = startTime,
            onDismiss = { showStartTimePicker = false },
            onConfirm = { startTime = it; showStartTimePicker = false },
        )
    }
    if (showEndTimePicker) {
        EventTimePickerDialog(
            initialTime = endTime,
            onDismiss = { showEndTimePicker = false },
            onConfirm = { endTime = it; showEndTimePicker = false },
        )
    }
    if (showUntilDatePicker) {
        EventDatePickerDialog(
            initialDate = recurrenceRule.untilDate ?: startDate,
            onDismiss = { showUntilDatePicker = false },
            onConfirm = { picked ->
                recurrenceRule = recurrenceRule.copy(untilDate = picked)
                showUntilDatePicker = false
            },
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("일정 삭제") },
            text = { Text("'${title.ifBlank { "(제목 없음)" }}' 일정을 삭제할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    val id = existing?.id ?: return@TextButton
                    scope.launch {
                        viewModel.deleteLocalEvent(id)
                        showDeleteConfirm = false
                        onDismiss()
                    }
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun ReminderChip(label: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
            Icon(Icons.Default.Close, contentDescription = "알림 제거", modifier = Modifier.size(14.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventDatePickerDialog(initialDate: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val initialMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis != null) onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()) else onDismiss()
            }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventTimePickerDialog(initialTime: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initialTime.hour, initialMinute = initialTime.minute, is24Hour = true)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(24.dp)) {
                TimePicker(state = state)
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("취소") }
                    TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("확인") }
                }
            }
        }
    }
}
