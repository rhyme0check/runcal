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
import com.jongsun.runcal.data.distinctName
import com.jongsun.runcal.data.AccountEventColors
import com.jongsun.runcal.data.EventStyleChoice
import com.jongsun.runcal.data.hasRestrictedEventColors
import com.jongsun.runcal.data.nearestColor
import com.jongsun.runcal.data.recommendColors
import com.jongsun.runcal.data.BIRTHDAY_PREFIX
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

val REMINDER_PRESET_MINUTES = listOf(0, 5, 10, 15, 30, 60, 120, 1440)
private const val MAX_REMINDERS = 5

/** 반복 일정 수정/삭제 시 사용자가 고르는 3택 범위. */
private enum class RecurrenceEditScope { THIS_ONLY, THIS_AND_FOLLOWING, ALL }

fun reminderLabel(minutes: Int): String = when {
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
fun EventEditDialog(
    viewModel: CalendarViewModel,
    existing: EventItem?,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    // 공유받은 글로 새 일정을 만들 때 미리 채울 제목·메모(P9).
    initialTitle: String = "",
    initialDescription: String = "",
) {
    if (existing != null && existing.sourceKind == EventSourceKind.NOTION) {
        NotionItemDialog(viewModel = viewModel, event = existing, onDismiss = onDismiss)
        return
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            EventEditContent(viewModel = viewModel, existing = existing, initialDate = initialDate, onDismiss = onDismiss, initialTitle = initialTitle, initialDescription = initialDescription)
        }
    }
}

/**
 * Notion 항목 화면. DB가 "앱에서 수정"으로 켜져 있으면 날짜와 상태만 바꿀 수 있다(제목·본문은 Notion에서).
 * 저장은 [CalendarViewModel.applyNotionChange]로 — 저장 직전에 Notion에서 다시 읽어 그 사이 바뀌었으면 저장하지 않는다.
 */
@Composable
private fun NotionItemDialog(viewModel: CalendarViewModel, event: EventItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notionDatabases by viewModel.notionDatabases.collectAsStateWithLifecycle()
    val database = notionDatabases.firstOrNull { it.id == event.notionDatabaseId }
    val writable = database?.writeEnabled == true
    val statusOptions = remember(database) { com.jongsun.runcal.data.notion.parseSchemaSummary(database?.schemaJson).statusOptions }
    val zone = remember { ZoneId.systemDefault() }

    val original = remember(event) { com.jongsun.runcal.data.notion.NotionWriteService.specOf(event.allDay, event.begin, event.end, zone) }
    var startDate by remember { mutableStateOf(event.dateRange(zone).start) }
    var endDate by remember { mutableStateOf(event.dateRange(zone).endInclusive) }
    var startTime by remember { mutableStateOf(Instant.ofEpochMilli(event.begin).atZone(zone).toLocalTime()) }
    var endTime by remember { mutableStateOf(Instant.ofEpochMilli(event.end).atZone(zone).toLocalTime()) }
    var status by remember { mutableStateOf(event.notionStatus) }
    var picker by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val newSpec: com.jongsun.runcal.data.notion.NotionDateSpec = if (event.allDay) {
        com.jongsun.runcal.data.notion.NotionDateSpec.AllDay(startDate, maxOf(startDate, endDate))
    } else {
        // 원래 며칠에 걸친 항목이면 그 일수 차이를 유지한다(상태만 바꿀 때 날짜가 바뀐 것으로 잡히지 않게).
        val dayOffset = (original as? com.jongsun.runcal.data.notion.NotionDateSpec.Timed)?.let { o ->
            o.end?.let { java.time.temporal.ChronoUnit.DAYS.between(o.start.toLocalDate(), it.toLocalDate()) }
        } ?: 0L
        val start = startDate.atTime(startTime)
        val end = startDate.plusDays(dayOffset).atTime(endTime).let { if (it < start) start else it }
        com.jongsun.runcal.data.notion.NotionDateSpec.Timed(start, end)
    }
    val dateChanged = newSpec != original
    val statusChanged = status != event.notionStatus && status != null
    val changed = dateChanged || statusChanged

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(event.title.ifBlank { "(제목 없음)" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = database?.displayName ?: "Notion",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (event.location.isNotBlank()) Text(text = event.location, style = MaterialTheme.typography.bodySmall)
                if (!writable) {
                    Text(
                        text = "이 DB는 읽기 전용입니다. 날짜·상태를 바꾸려면 설정 > Notion 연동에서 '앱에서 수정'을 켜세요.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text("날짜", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { picker = "startDate" }, enabled = !saving) { Text(formatDate(startDate)) }
                        if (event.allDay) {
                            Text("~")
                            OutlinedButton(onClick = { picker = "endDate" }, enabled = !saving) { Text(formatDate(endDate)) }
                        }
                    }
                    if (!event.allDay) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { picker = "startTime" }, enabled = !saving) { Text(formatTime(startTime)) }
                            Text("~")
                            OutlinedButton(onClick = { picker = "endTime" }, enabled = !saving) { Text(formatTime(endTime)) }
                        }
                    }
                    if (database.statusProperty != null && statusOptions.isNotEmpty()) {
                        Text(database.statusProperty, style = MaterialTheme.typography.labelMedium)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            statusOptions.forEach { option ->
                                FilterChip(
                                    selected = status == option,
                                    onClick = { if (!saving) status = option },
                                    label = { Text(option) },
                                )
                            }
                        }
                    }
                    Text(
                        text = "저장 직전에 Notion에서 다시 확인하고, 그 사이 바뀌었으면 저장하지 않습니다. 저장 후 24시간 동안 AI 명령의 변경 기록에서 되돌릴 수 있습니다.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NotionGroupChooser(viewModel = viewModel, event = event)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (writable) {
                TextButton(
                    enabled = changed && !saving,
                    onClick = {
                        saving = true
                        message = null
                        scope.launch {
                            val change = com.jongsun.runcal.data.notion.NotionChange(
                                date = if (dateChanged) newSpec else null,
                                status = if (statusChanged) status else null,
                            )
                            val result = viewModel.applyNotionChange(event, change)
                            saving = false
                            when (result) {
                                is com.jongsun.runcal.data.notion.NotionWriteResult.Ok -> {
                                    val lines = buildList {
                                        if (dateChanged) add("날짜 변경")
                                        if (statusChanged) add("상태: '${event.notionStatus ?: "없음"}' → '$status'")
                                    }
                                    com.jongsun.runcal.ai.ChangeLog.append(
                                        context, "직접 수정", "Notion 수정: '${event.title}' · " + lines.joinToString(" / "), listOf(result.undoId),
                                    )
                                    android.widget.Toast.makeText(context, "Notion에 반영했습니다", android.widget.Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                                is com.jongsun.runcal.data.notion.NotionWriteResult.Conflict -> message = result.message
                                is com.jongsun.runcal.data.notion.NotionWriteResult.Failed -> message = result.message
                            }
                        }
                    },
                ) { Text("저장") }
            } else {
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !event.notionUrl.isNullOrBlank() && !saving,
                    onClick = {
                        val url = event.notionUrl ?: return@TextButton
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        onDismiss()
                    },
                ) { Text("Notion에서 열기") }
                if (writable) TextButton(onClick = onDismiss, enabled = !saving) { Text("닫기") }
            }
        },
    )

    when (picker) {
        "startDate" -> EventDatePickerDialog(startDate, onDismiss = { picker = null }) { picked ->
            // 시작일을 옮기면 기간 길이를 유지한 채 끝 날짜도 함께 옮긴다.
            val length = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate)
            startDate = picked
            endDate = picked.plusDays(length)
            picker = null
        }
        "endDate" -> EventDatePickerDialog(endDate, onDismiss = { picker = null }) { picked ->
            endDate = maxOf(picked, startDate)
            picker = null
        }
        "startTime" -> EventTimePickerDialog(startTime, onDismiss = { picker = null }) { picked ->
            val minutes = java.time.Duration.between(startTime, endTime).toMinutes()
            startTime = picked
            endTime = picked.plusMinutes(minutes.coerceAtLeast(0))
            picker = null
        }
        "endTime" -> EventTimePickerDialog(endTime, onDismiss = { picker = null }) { picked ->
            endTime = picked
            picker = null
        }
    }
}

/**
 * Notion 항목의 일정그룹 선택(P11). 앱 안에서만 쓰는 분류라 DB의 "앱에서 수정" 설정과 상관없이 바꿀 수 있고, 누르는 즉시 저장된다.
 * "자동"은 그룹의 제목 규칙을 따른다(지금 어떤 그룹으로 잡히는지 함께 보여 준다).
 */
@Composable
private fun NotionGroupChooser(viewModel: CalendarViewModel, event: EventItem) {
    val groups by viewModel.eventTypes.collectAsStateWithLifecycle()
    if (groups.isEmpty()) return
    val scope = rememberCoroutineScope()
    // null=자동(직접 지정 없음), ""=그룹 없음, 그 밖=그룹 id
    var manual by remember(event) { mutableStateOf<String?>(null) }
    var autoName by remember(event) { mutableStateOf<String?>(null) }
    LaunchedEffect(event, groups) {
        val index = viewModel.groupIndex()
        manual = index.notionManualGroupId(event)
        autoName = index.matchRules(event.title)?.name
    }
    fun choose(value: String?) {
        manual = value
        scope.launch { viewModel.setNotionGroup(event, value) }
    }
    Text("일정그룹 (앱 안에서만 쓰는 분류, Notion에는 쓰지 않음)", style = MaterialTheme.typography.labelMedium)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = manual == null, onClick = { choose(null) }, label = { Text("자동" + (autoName?.let { ": $it" } ?: ": 없음")) })
        groups.forEach { group ->
            FilterChip(
                selected = manual == group.id,
                onClick = { choose(group.id) },
                leadingIcon = { Box(modifier = Modifier.size(10.dp).background(Color(group.colorArgb), CircleShape)) },
                label = { Text(group.name) },
            )
        }
        FilterChip(selected = manual == "", onClick = { choose("") }, label = { Text("그룹 없음") })
    }
}

private fun formatDate(date: LocalDate): String =
    "${date.monthValue}/${date.dayOfMonth}(${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN)})"

private fun formatTime(time: LocalTime): String = com.jongsun.runcal.data.formatClock(time.hour, time.minute)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventEditContent(
    viewModel: CalendarViewModel,
    existing: EventItem?,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    initialTitle: String = "",
    initialDescription: String = "",
) {
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val writableCalendars = remember(calendars) { calendars.filter { it.isWritable } }
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()

    // 생일 일정은 화면에 🎂가 붙어 오므로 편집할 때는 떼고 보여 준다(캘린더에는 원래 제목만 저장).
    var title by remember { mutableStateOf(existing?.title?.let { if (existing.isBirthday) it.removePrefix(BIRTHDAY_PREFIX) else it } ?: initialTitle) }
    var birthday by remember { mutableStateOf(existing?.isBirthday ?: false) }
    var anniversary by remember { mutableStateOf(existing?.isAnniversary ?: false) }
    var marksTouched by remember { mutableStateOf(false) }
    // 저장으로 만들어지거나 바뀐 일정 id(반복 예외·분리로 새 id가 생길 수 있음) — 생일·기념일 표시를 여기에 붙인다.
    val markIds = remember { mutableListOf<Long>() }
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
    var description by remember { mutableStateOf(existing?.description ?: initialDescription) }
    var selectedCalendarId by remember { mutableStateOf(existing?.calendarId ?: writableCalendars.firstOrNull()?.id) }
    // 새 일정만 기본 알림을 미리 채운다 — 기존 일정은 항상 아래 LaunchedEffect가 저장된 값으로 덮어쓴다.
    val defaultReminderMinutes by viewModel.defaultReminderMinutes.collectAsStateWithLifecycle()
    var reminderMinutes by remember {
        mutableStateOf(if (existing == null) defaultReminderMinutes?.let { listOf(it) } ?: emptyList() else emptyList())
    }
    var recurrenceRule by remember { mutableStateOf(parseRRule(existing?.rrule)) }
    var showReminderMenu by remember { mutableStateOf(false) }
    var showFrequencyMenu by remember { mutableStateOf(false) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var showUntilDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSaveScopeDialog by remember { mutableStateOf(false) }
    var showDeleteScopeDialog by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 날짜/시간 필드는 일부러 탭한 "그 회차"의 시각을 그대로 보여준다(마스터의 진짜 DTSTART가
    // 아님) — 사용자가 지금 보고 있는 것이 바로 그 회차이고, "이번만"/"이후 전체" 예외 처리가
    // 전부 이 회차의 원래 시각(existing.begin/end)을 기준으로 동작해야 하기 때문이다. "전체"
    // 범위를 선택했을 때만 마스터의 진짜 시작 시각을 다시 읽어(getEventDetail) 델타를 계산한다.
    val isRecurring = !existing?.rrule.isNullOrBlank()
    // 실측으로 확인된 결함: ACCOUNT_TYPE_LOCAL(동기화 어댑터 없는) 캘린더는 반복 예외를 하나라도
    // 만들면 그 예외보다 앞선 회차들이 Instances 조회에서 사라진다(구글 계정 등 동기화 캘린더는
    // 정상). "이번만"은 이 결함을 그대로 노출하므로 로컬 캘린더에서는 선택지 자체를 감춘다.
    val isLocalCalendar = calendars.find { it.id == existing?.calendarId }?.accountType == android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL

    // 일정 유형·색. 기존 일정은 저장된 유형을 읽어 오고, 사용자가 손댄 경우에만 저장 시 반영한다(styleTouched).
    val eventTypes by viewModel.eventTypes.collectAsStateWithLifecycle()
    var typeId by remember { mutableStateOf<String?>(null) }
    var eventColor by remember { mutableStateOf(existing?.eventColor) }
    var styleTouched by remember { mutableStateOf(false) }

    LaunchedEffect(existing?.id) {
        if (existing != null) {
            reminderMinutes = viewModel.getReminders(existing.id)
            typeId = viewModel.getEventTypeId(existing.id)
            if (!marksTouched) {
                val (b, a) = viewModel.getEventMark(existing.id)
                birthday = b
                anniversary = a
            }
        }
    }
    fun styleForSave(): EventStyleChoice? = if (existing == null || styleTouched) EventStyleChoice(eventColor, typeId) else null

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

    suspend fun performSaveCore(calendarId: Long, editScope: RecurrenceEditScope): Boolean {
        val (newBegin, newEnd) = computeMillis()
        if (existing == null) {
            val rrule = recurrenceRule.toRRuleString(startDate)
            val id = viewModel.createLocalEvent(calendarId, title.trim(), newBegin, newEnd, allDay, location.trim(), description.trim(), reminderMinutes, rrule, style = styleForSave())
            if (id > 0) markIds += id
            return id > 0
        }
        return when {
            editScope == RecurrenceEditScope.THIS_ONLY -> viewModel.createSingleOccurrenceException(
                masterEventId = existing.id,
                originalInstanceBeginMillis = existing.begin,
                title = title.trim(),
                startMillis = newBegin,
                endMillis = newEnd,
                allDay = allDay,
                location = location.trim(),
                description = description.trim(),
                reminderMinutes = reminderMinutes,
                style = styleForSave(),
            ).also { if (it > 0) markIds += it } > 0
            editScope == RecurrenceEditScope.THIS_AND_FOLLOWING -> {
                val newAnchorDate = Instant.ofEpochMilli(newBegin).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()
                viewModel.updateFollowingOccurrences(
                    masterEventId = existing.id,
                    masterAllDay = existing.allDay,
                    masterRrule = existing.rrule.orEmpty(),
                    splitInstanceBeginMillis = existing.begin,
                    calendarId = calendarId,
                    title = title.trim(),
                    startMillis = newBegin,
                    endMillis = newEnd,
                    allDay = allDay,
                    location = location.trim(),
                    description = description.trim(),
                    reminderMinutes = reminderMinutes,
                    newRrule = recurrenceRule.toRRuleString(newAnchorDate),
                    style = styleForSave(),
                ).also { if (it > 0) markIds += it.toLong() } > 0
            }
            // ALL. 반복 일정이면 탭한 회차에 적용한 시간 이동분(델타)만큼 마스터의 진짜 DTSTART를
            // 함께 옮긴다 — 그래야 5번째 회차를 열어 시간만 한 시간 늦췄을 때 전체 시리즈가
            // 정확히 한 시간씩 밀리지, 마스터가 엉뚱한 날짜로 재고정되지 않는다.
            isRecurring -> {
                val master = viewModel.getEventDetail(existing.id) ?: return false
                val newMasterStart = master.begin + (newBegin - existing.begin)
                val newMasterEnd = master.end + (newEnd - existing.end)
                val newAnchorDate = Instant.ofEpochMilli(newMasterStart).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()
                viewModel.updateLocalEvent(
                    existing.id, title.trim(), newMasterStart, newMasterEnd, allDay, location.trim(), description.trim(),
                    reminderMinutes, recurrenceRule.toRRuleString(newAnchorDate),
                    style = styleForSave(),
                ) > 0
            }
            else -> viewModel.updateLocalEvent(
                existing.id, title.trim(), newBegin, newEnd, allDay, location.trim(), description.trim(),
                reminderMinutes, recurrenceRule.toRRuleString(startDate),
                style = styleForSave(),
            ) > 0
        }
    }

    suspend fun performSave(calendarId: Long, editScope: RecurrenceEditScope): Boolean {
        markIds.clear()
        existing?.let { markIds += it.id }
        val ok = performSaveCore(calendarId, editScope)
        if (ok && (marksTouched || (existing == null && (birthday || anniversary)))) {
            markIds.distinct().forEach { viewModel.setEventMark(it, birthday, anniversary) }
        }
        return ok
    }

    suspend fun performDelete(editScope: RecurrenceEditScope): Boolean {
        val id = existing?.id ?: return false
        return when (editScope) {
            RecurrenceEditScope.THIS_ONLY -> viewModel.deleteSingleOccurrence(
                masterEventId = id,
                originalInstanceBeginMillis = existing.begin,
            ) > 0
            RecurrenceEditScope.THIS_AND_FOLLOWING -> viewModel.deleteFollowingOccurrences(
                masterEventId = id,
                masterAllDay = existing.allDay,
                masterRrule = existing.rrule.orEmpty(),
                splitInstanceBeginMillis = existing.begin,
            )
            RecurrenceEditScope.ALL -> viewModel.deleteLocalEvent(id) > 0
        }
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
                    IconButton(onClick = { if (isRecurring) showDeleteScopeDialog = true else showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "삭제")
                    }
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
            if (eventTypes.isNotEmpty()) {
                Text(text = "일정그룹 (그룹 색이 일정 색보다 우선)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Row(
                    modifier = Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = typeId == null,
                        onClick = { typeId = null; styleTouched = true },
                        // 직접 고르지 않으면 그룹의 제목 규칙으로 자동 분류된다.
                        label = { Text("자동(제목 규칙)") },
                    )
                    eventTypes.forEach { type ->
                        FilterChip(
                            selected = typeId == type.id,
                            onClick = {
                                typeId = type.id
                                styleTouched = true
                                eventColor = type.colorArgb
                                // 새 일정에서만 캘린더·알림을 그룹 기본값으로 채운다(기존 일정의 저장 위치는 함부로 바꾸지 않음).
                                if (existing == null) {
                                    type.defaultCalendarId?.takeIf { id -> writableCalendars.any { it.id == id } }?.let { selectedCalendarId = it }
                                    when (val m = type.defaultReminderMinutes) {
                                        null -> Unit
                                        -1 -> reminderMinutes = emptyList()
                                        else -> reminderMinutes = listOf(m)
                                    }
                                }
                            },
                            leadingIcon = { Box(modifier = Modifier.size(10.dp).background(Color(type.colorArgb), CircleShape)) },
                            label = { Text(type.name) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "종일", style = MaterialTheme.typography.bodyLarge)
                // 반복 일정은 종일/시간 지정 여부를 회차마다 다르게 둘 수 없다(ORIGINAL_ALL_DAY가
                // 시리즈 전체에 대해 하나로 고정됨) — 예외 처리 로직을 단순하고 안전하게 유지하려고
                // 기존 반복 일정을 열었을 때는 이 토글을 잠근다.
                Switch(checked = allDay, onCheckedChange = { allDay = it }, enabled = !isRecurring)
            }
            // 생일: 매년 반복 + 제목 앞 🎂. 기념일: 매년 반복 + 공휴일과 같은 양식(빨간 막대·빨간 날짜). 둘 다 켤 수 있다.
            fun ensureYearly() {
                if (recurrenceRule.frequency == RecurrenceFrequency.NONE && !isRecurring) {
                    recurrenceRule = RecurrenceRule(frequency = RecurrenceFrequency.YEARLY, interval = 1, endType = RecurrenceEndType.NEVER)
                    allDay = true
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "생일 🎂", style = MaterialTheme.typography.bodyLarge)
                    Text("매년 반복, 제목 앞에 🎂 표시", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = birthday, onCheckedChange = { birthday = it; marksTouched = true; if (it) ensureYearly() })
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "기념일", style = MaterialTheme.typography.bodyLarge)
                    Text("매년 반복, 공휴일과 같은 양식(빨간 막대·빨간 날짜)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = anniversary, onCheckedChange = { anniversary = it; marksTouched = true; if (it) ensureYearly() })
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
                            label = { Text(calendar.distinctName(writableCalendars)) },
                        )
                    }
                }
            }
            val selectedCalendar = writableCalendars.firstOrNull { it.id == selectedCalendarId }
            val context = androidx.compose.ui.platform.LocalContext.current
            val restrictedColors = remember(selectedCalendar) {
                selectedCalendar?.takeIf { it.hasRestrictedEventColors() }
                    ?.let { AccountEventColors.forAccount(context, it.accountName, it.accountType) }?.takeIf { it.isNotEmpty() }
            }
            val recommended = remember(calendars, eventTypes) { recommendColors(viewModel.usedColors()) }
            // 구글 캘린더로 바꾸면 고른 색을 그 계정이 허용하는 가장 가까운 색으로 보여 준다(저장도 그 색으로 된다).
            val shownColor = eventColor?.let { c -> restrictedColors?.let { nearestColor(c, it)?.argb } ?: c }
            Text(text = "색상", style = MaterialTheme.typography.labelMedium)
            ColorChoiceRow(
                selected = shownColor,
                recommended = recommended,
                restricted = restrictedColors,
                onSelect = { eventColor = it; styleTouched = true },
                noneLabel = "캘린더 색 사용",
                noneColor = selectedCalendar?.color,
            )
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
                    if (isRecurring) {
                        showSaveScopeDialog = true
                    } else {
                        val calendarId = selectedCalendarId ?: return@Button
                        scope.launch {
                            saving = true
                            val ok = performSave(calendarId, RecurrenceEditScope.ALL)
                            saving = false
                            if (ok) onDismiss() else errorMessage = "저장하지 못했습니다. 캘린더 권한을 확인해주세요."
                        }
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
    if (showSaveScopeDialog) {
        RecurrenceScopeDialog(
            actionLabel = "수정",
            showThisOnly = !isLocalCalendar,
            onDismiss = { showSaveScopeDialog = false },
            onSelect = { editScope ->
                showSaveScopeDialog = false
                val calendarId = selectedCalendarId
                if (calendarId != null) {
                    scope.launch {
                        saving = true
                        val ok = performSave(calendarId, editScope)
                        saving = false
                        if (ok) onDismiss() else errorMessage = "저장하지 못했습니다. 캘린더 권한을 확인해주세요."
                    }
                }
            },
        )
    }
    if (showDeleteScopeDialog) {
        RecurrenceScopeDialog(
            actionLabel = "삭제",
            showThisOnly = !isLocalCalendar,
            onDismiss = { showDeleteScopeDialog = false },
            onSelect = { editScope ->
                showDeleteScopeDialog = false
                scope.launch {
                    val ok = performDelete(editScope)
                    if (ok) onDismiss() else errorMessage = "삭제하지 못했습니다. 캘린더 권한을 확인해주세요."
                }
            },
        )
    }
}

/**
 * "이번만 / 이후 전체 / 전체" 3택 — 반복 일정 수정·삭제 공용. [showThisOnly]가 false면 "이번만"을
 * 아예 감추고 그 이유를 안내한다 — 동기화 어댑터가 없는(ACCOUNT_TYPE_LOCAL) 캘린더는 예외를
 * 만들면 그 이전 회차가 사라지는 CalendarProvider 결함이 실측으로 확인됐다(구글 계정 등 동기화
 * 캘린더는 정상 동작).
 */
@Composable
private fun RecurrenceScopeDialog(
    actionLabel: String,
    showThisOnly: Boolean,
    onDismiss: () -> Unit,
    onSelect: (RecurrenceEditScope) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("반복 일정 $actionLabel") },
        text = {
            Column {
                if (showThisOnly) {
                    TextButton(onClick = { onSelect(RecurrenceEditScope.THIS_ONLY) }, modifier = Modifier.fillMaxWidth()) {
                        Text("이번만", modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    Text(
                        text = "이 캘린더는 기기 안에만 있는(동기화 안 되는) 캘린더라 \"이번만\"은 지원하지 않습니다. " +
                            "\"이후 전체\" 또는 \"전체\"를 선택해주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                TextButton(onClick = { onSelect(RecurrenceEditScope.THIS_AND_FOLLOWING) }, modifier = Modifier.fillMaxWidth()) {
                    Text("이후 전체", modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = { onSelect(RecurrenceEditScope.ALL) }, modifier = Modifier.fillMaxWidth()) {
                    Text("전체", modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
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
    val state = rememberTimePickerState(initialHour = initialTime.hour, initialMinute = initialTime.minute, is24Hour = com.jongsun.runcal.data.TimeFormatPrefs.use24h)
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
