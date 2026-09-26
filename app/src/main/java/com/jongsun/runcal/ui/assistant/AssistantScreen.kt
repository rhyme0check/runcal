package com.jongsun.runcal.ui.assistant

import android.provider.CalendarContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.ai.AiScope
import com.jongsun.runcal.ai.ProposalItem
import com.jongsun.runcal.ai.ProposalKind
import com.jongsun.runcal.ai.describeEventRange
import com.jongsun.runcal.ai.describeReminders
import com.jongsun.runcal.data.CalendarInfo
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.dateRange
import com.jongsun.runcal.data.source.EventSourceKind
import com.jongsun.runcal.ui.calendar.CalendarViewModel
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private const val PRIVACY_LINE = "무료 등급 사용 중 — 입력한 명령 문장은 Google로 전송됩니다"

/** 자연어 명령 화면. 일정 목록·확인 카드는 앱이 실제 제목으로 그린다(모델에는 익명 라벨만 나간다). */
@Composable
fun AssistantScreen(
    assistant: AssistantViewModel,
    calendarViewModel: CalendarViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    val messages by assistant.messages.collectAsStateWithLifecycle()
    val busy by assistant.busy.collectAsStateWithLifecycle()
    val calendars by calendarViewModel.calendars.collectAsStateWithLifecycle()
    val notionDatabases by calendarViewModel.notionDatabases.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun calendarName(e: EventItem): String = when (e.sourceKind) {
        EventSourceKind.NOTION -> notionDatabases.firstOrNull { it.id == e.notionDatabaseId }?.displayName ?: "Notion"
        else -> calendars.firstOrNull { it.id == e.calendarId }?.displayName ?: ""
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "AI 명령", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { showLog = true }) { Icon(Icons.Default.History, contentDescription = "변경 기록") }
                IconButton(onClick = { assistant.newConversation() }) { Icon(Icons.Default.Refresh, contentDescription = "새 대화") }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "닫기") }
            }
            Text(
                text = PRIVACY_LINE,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            text = "예) 이번 주 러닝 일정 보여줘\n예) 다음 주 화요일 저녁 7시에 회식 추가\n예) 9/25 러닝을 26일로 옮겨줘\n\n추가·수정·삭제는 실행 전에 항상 확인 카드가 나옵니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        calendarName = ::calendarName,
                        calendars = calendars,
                        onScope = { key, scope -> assistant.setScope(message.id, key, scope) },
                        onCalendar = { key, id -> assistant.setCalendar(message.id, key, id) },
                        onConfirm = { assistant.confirm(message.id, calendarViewModel) },
                        onCancel = { assistant.cancel(message.id) },
                        onUndo = { assistant.undo(message.id) },
                        onChoose = { ref -> assistant.choose(message.id, ref, calendarViewModel) },
                        onCancelChoice = { assistant.cancelChoice(message.id) },
                        busy = busy,
                    )
                }
                if (busy) {
                    item { CircularProgressIndicator(modifier = Modifier.padding(8.dp).size(24.dp), strokeWidth = 2.dp) }
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("명령을 입력하세요") },
                    maxLines = 4,
                )
                IconButton(
                    onClick = {
                        assistant.send(input, calendarViewModel)
                        input = ""
                    },
                    enabled = input.isNotBlank() && !busy,
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "보내기") }
            }
        }
    }

    if (showLog) ChangeLogDialog(assistant = assistant, onDismiss = { showLog = false })
}

@Composable
private fun ChangeLogDialog(assistant: AssistantViewModel, onDismiss: () -> Unit) {
    val entries = remember { assistant.changeLog() }
    val format = remember { SimpleDateFormat("M/d HH:mm", Locale.KOREAN) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("변경 기록") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entries.isEmpty()) Text("아직 기록이 없습니다.", style = MaterialTheme.typography.bodyMedium)
                entries.forEach { e ->
                    Column {
                        Text("${format.format(Date(e.atMillis))} · ${e.kind}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(e.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("이 기록은 이 기기에만 저장되며 백업·전송되지 않습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    calendarName: (EventItem) -> String,
    calendars: List<CalendarInfo>,
    onScope: (String, AiScope) -> Unit,
    onCalendar: (String, Long) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    onChoose: (String) -> Unit,
    onCancelChoice: () -> Unit,
    busy: Boolean,
) {
    val isUser = message.role == ChatRole.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val bubbleColor = when (message.role) {
            ChatRole.USER -> MaterialTheme.colorScheme.primaryContainer
            ChatRole.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
            ChatRole.ERROR -> MaterialTheme.colorScheme.errorContainer
        }
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bubbleColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
        }
        if (message.events.isNotEmpty() && message.card == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                message.events.forEach { EventLine(it, calendarName(it)) }
            }
        }
        message.card?.let { card ->
            ProposalCardView(message.id, card, calendars, onScope, onCalendar, onConfirm, onCancel, onUndo, busy)
        }
        message.choice?.let { choice -> ChoiceCardView(choice, calendarName, onChoose, onCancelChoice, busy) }
    }
}

/** 후보 선택 카드. 후보는 실제 제목으로 보여주고, 사용자가 하나를 탭하면 그 일정으로 명령을 이어간다. */
@Composable
private fun ChoiceCardView(
    choice: ChoiceState,
    calendarName: (EventItem) -> String,
    onChoose: (String) -> Unit,
    onCancel: () -> Unit,
    busy: Boolean,
) {
    val pending = choice.status == ChoiceStatus.PENDING
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.5.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = when (choice.status) {
                ChoiceStatus.PENDING -> "어느 일정인지 골라 주세요"
                ChoiceStatus.CHOSEN -> "선택했습니다"
                ChoiceStatus.CANCELLED -> "선택하지 않았습니다"
            },
            style = MaterialTheme.typography.titleSmall,
        )
        choice.request.candidates.forEach { (ref, event) ->
            val chosen = choice.chosenRef == ref
            if (!pending && !chosen) return@forEach
            OutlinedButton(
                onClick = { onChoose(ref) },
                enabled = pending && !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) { EventLine(event, calendarName(event)) }
            }
        }
        if (pending) {
            TextButton(onClick = onCancel, enabled = !busy) { Text("해당 없음") }
        }
    }
}

@Composable
private fun ProposalCardView(
    messageId: Long,
    card: ProposalCardState,
    calendars: List<CalendarInfo>,
    onScope: (String, AiScope) -> Unit,
    onCalendar: (String, Long) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    busy: Boolean,
) {
    val hasDelete = card.items.any { it.kind == ProposalKind.DELETE }
    val borderColor = if (hasDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val pending = card.status == CardStatus.PENDING
    val scopesReady = card.items.all { !it.needsScopeChoice() || card.scopes[it.callKey] != null }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = when (card.status) {
                CardStatus.PENDING -> if (hasDelete) "삭제 확인 — 아직 삭제되지 않았습니다" else "확인이 필요합니다 — 아직 아무것도 바뀌지 않았습니다"
                CardStatus.EXECUTED -> "실행했습니다"
                CardStatus.PARTIAL -> "일부만 실행됐습니다"
                CardStatus.FAILED -> "실행하지 못했습니다"
                CardStatus.CANCELLED -> "취소했습니다(아무것도 바뀌지 않음)"
                CardStatus.UNDONE -> "실행을 되돌렸습니다"
            },
            style = MaterialTheme.typography.titleSmall,
            color = if (hasDelete && pending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (card.items.size > 1) {
            Text("${card.items.size}건을 한 번에 처리합니다. 전체 목록을 확인하세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        card.items.forEach { item ->
            ItemBlock(item, card, calendars, pending, onScope, onCalendar)
            if (item != card.items.last()) HorizontalDivider()
        }

        if (pending) {
            if (!scopesReady) {
                Text("반복 일정입니다. 적용 범위를 먼저 선택하세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 삭제는 [취소]를 앞에 두고 더 눈에 띄게, [삭제 실행]은 경고색으로 분리한다.
                FilledTonalButton(onClick = onCancel, enabled = !busy) { Text("취소") }
                if (hasDelete) {
                    Button(
                        onClick = onConfirm,
                        enabled = scopesReady && !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    ) { Text("삭제 실행") }
                } else {
                    Button(onClick = onConfirm, enabled = scopesReady && !busy) { Text("실행") }
                }
            }
        } else {
            card.resultLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val until = card.undoUntilMillis
            if (until != null && card.status == CardStatus.EXECUTED) {
                val now by produceState(System.currentTimeMillis(), messageId, until) {
                    while (value < until) {
                        delay(300)
                        value = System.currentTimeMillis()
                    }
                }
                if (now < until) {
                    OutlinedButton(onClick = onUndo, enabled = !busy) { Text("실행 취소 (${((until - now) / 1000 + 1)}초)") }
                }
            }
            card.undoNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ItemBlock(
    item: ProposalItem,
    card: ProposalCardState,
    calendars: List<CalendarInfo>,
    pending: Boolean,
    onScope: (String, AiScope) -> Unit,
    onCalendar: (String, Long) -> Unit,
) {
    val label = when (item.kind) {
        ProposalKind.CREATE -> "추가"
        ProposalKind.UPDATE -> "수정"
        ProposalKind.DELETE -> "삭제"
    }
    val labelColor = if (item.kind == ProposalKind.DELETE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = labelColor, fontWeight = FontWeight.Bold)
            Text(
                text = "  '${item.target?.title ?: item.title}'",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (item.kind) {
            ProposalKind.CREATE -> {
                Text(describeEventRange(item.allDay, item.startMillis, item.endMillis), style = MaterialTheme.typography.bodyMedium)
                item.recurrenceLabel?.let { Text("반복: $it", style = MaterialTheme.typography.bodyMedium) }
                item.reminderMinutes?.let { Text("알림: ${describeReminders(it)}", style = MaterialTheme.typography.bodySmall) }
                val writable = calendars.filter { it.isWritable }
                if (writable.size > 1) {
                    Text("캘린더", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        writable.forEach { cal ->
                            FilterChip(
                                selected = card.calendars[item.callKey] == cal.id,
                                onClick = { if (pending) onCalendar(item.callKey, cal.id) },
                                label = { Text(cal.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                enabled = pending,
                            )
                        }
                    }
                } else {
                    writable.firstOrNull()?.let { Text("캘린더: ${it.displayName}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            ProposalKind.UPDATE -> item.changeLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            ProposalKind.DELETE -> item.target?.let { t ->
                Text(describeEventRange(t.allDay, t.begin, t.end), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (item.needsScopeChoice()) {
            val target = item.target
            val isLocal = target != null && calendars.firstOrNull { it.id == target.calendarId }?.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL
            Text("반복 일정 — 적용 범위", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(AiScope.THIS_ONLY to "이번만", AiScope.THIS_AND_FOLLOWING to "이후 전체", AiScope.ALL to "전체").forEach { (scope, text) ->
                    if (scope == AiScope.THIS_ONLY && isLocal) return@forEach // 로컬 캘린더는 "이번만"을 지원하지 않는다(편집 화면과 같은 규칙).
                    FilterChip(
                        selected = card.scopes[item.callKey] == scope,
                        onClick = { if (pending) onScope(item.callKey, scope) },
                        label = { Text(text) },
                        enabled = pending,
                    )
                }
            }
            if (isLocal) Text("기기 안에만 있는 캘린더라 '이번만'은 선택할 수 없습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            card.impacts[item.callKey]?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (item.kind == ProposalKind.DELETE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun ProposalItem.needsScopeChoice(): Boolean = kind != ProposalKind.CREATE && !target?.rrule.isNullOrBlank()

@Composable
private fun EventLine(event: EventItem, calendarName: String) {
    val zone = ZoneId.systemDefault()
    val range = event.dateRange(zone)
    val start = range.start
    val weekday = start.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
    val time = if (event.allDay) {
        "종일"
    } else {
        val begin = Instant.ofEpochMilli(event.begin).atZone(zone).toLocalTime()
        "%02d:%02d".format(begin.hour, begin.minute)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(Color(event.color), CircleShape))
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = "${start.monthValue}/${start.dayOfMonth}($weekday) $time",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = event.title.ifBlank { "(제목 없음)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (calendarName.isNotBlank()) {
                Text(text = calendarName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
