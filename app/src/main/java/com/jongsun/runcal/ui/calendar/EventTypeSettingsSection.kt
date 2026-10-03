package com.jongsun.runcal.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.data.distinctName
import com.jongsun.runcal.data.AccountEventColors
import com.jongsun.runcal.data.CalendarInfo
import com.jongsun.runcal.data.hasRestrictedEventColors
import com.jongsun.runcal.data.nearestColor
import com.jongsun.runcal.data.parseTitleKeywords
import com.jongsun.runcal.data.recommendColors
import com.jongsun.runcal.data.room.EventTypeEntity
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * 일정그룹 목록(업무·미팅·포인트훈련 …). 그룹마다 색·제목 규칙·기본 캘린더·알림을 정해 둔다. 그룹 색은 개별 일정 색보다 우선하고,
 * 그룹 색을 바꾸면 이미 만든 일정에도 바로 반영된다. 프리셋은 이 그룹들을 골라 묶을 수 있다.
 */
@Composable
fun EventTypeSettingsSection(viewModel: CalendarViewModel, modifier: Modifier = Modifier) {
    val types by viewModel.eventTypes.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<EventTypeEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "일정그룹", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("추가")
            }
        }
        Text(
            text = "저장할 캘린더와 별개로 일정을 묶고 색을 정합니다. 색 우선순위: 프리셋 색(켠 경우) → 그룹 색 → 일정 색 → 캘린더 색. " +
                "제목 규칙을 적어 두면 Notion 항목처럼 직접 고르지 않은 일정도 제목 앞글자로 자동 분류됩니다(예: RP, RACE).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (types.isEmpty()) {
            Text("아직 그룹이 없습니다.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        // 러닝 기본 그룹이 아직 없으면 한 번에 만들 수 있게 한다(제목 규칙 포함).
        if (types.none { it.name == "포인트훈련" || it.name == "이지훈련" }) {
            TextButton(onClick = { scope.launch { viewModel.createRunningGroups() } }) {
                Text("러닝 기본 그룹 만들기 (포인트훈련·이지훈련·보강·휴식)")
            }
        }
        types.forEach { type ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { editing = type }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(14.dp).background(Color(type.colorArgb), CircleShape))
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(type.name, style = MaterialTheme.typography.bodyLarge)
                    val calName = calendars.firstOrNull { it.id == type.defaultCalendarId }?.displayName ?: "캘린더 지정 안 함"
                    Text("$calName · ${reminderText(type.defaultReminderMinutes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (type.titleKeywords.isNotBlank()) {
                        Text("제목 규칙: ${type.titleKeywords}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { scope.launch { viewModel.deleteEventType(type.id) } }) {
                    Icon(Icons.Default.Delete, contentDescription = "그룹 삭제")
                }
            }
        }
    }

    if (creating || editing != null) {
        EventTypeEditDialog(
            existing = editing,
            calendars = calendars.filter { it.isWritable },
            recommended = remember(editing, types, calendars) { recommendColors(viewModel.usedColors(excludeTypeId = editing?.id)) },
            nextSortOrder = (types.maxOfOrNull { it.sortOrder } ?: 0) + 1,
            onDismiss = { creating = false; editing = null },
            onSave = { type ->
                scope.launch { viewModel.saveEventType(type) }
                creating = false; editing = null
            },
        )
    }
}

private fun reminderText(minutes: Int?): String = when (minutes) {
    null -> "알림: 앱 기본값"
    -1 -> "알림 없음"
    else -> "알림 ${reminderLabel(minutes)}"
}

@Composable
private fun EventTypeEditDialog(
    existing: EventTypeEntity?,
    calendars: List<CalendarInfo>,
    recommended: List<Int>,
    nextSortOrder: Int,
    onDismiss: () -> Unit,
    onSave: (EventTypeEntity) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var color by remember { mutableStateOf(existing?.colorArgb ?: recommended.first()) }
    var calendarId by remember { mutableStateOf(existing?.defaultCalendarId) }
    var reminder by remember { mutableStateOf(existing?.defaultReminderMinutes) }
    var keywords by remember { mutableStateOf(existing?.titleKeywords ?: "") }
    var showCalendarMenu by remember { mutableStateOf(false) }
    var showReminderMenu by remember { mutableStateOf(false) }

    val calendar = calendars.firstOrNull { it.id == calendarId }
    // 구글 계정 캘린더로 정하면 그 계정이 허용하는 색만 고를 수 있다(골라 둔 색은 가장 가까운 허용 색으로 맞춘다).
    val restricted = calendar?.takeIf { it.hasRestrictedEventColors() }
        ?.let { AccountEventColors.forAccount(context, it.accountName, it.accountType) }?.takeIf { it.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "그룹 추가" else "그룹 수정") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("제목 규칙(쉼표로 구분, 앞글자 일치)") },
                    placeholder = { Text("예: RP, RACE") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("기본 캘린더", style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(onClick = { showCalendarMenu = true }) { Text(calendar?.distinctName(calendars) ?: "지정 안 함") }
                    DropdownMenu(expanded = showCalendarMenu, onDismissRequest = { showCalendarMenu = false }) {
                        DropdownMenuItem(text = { Text("지정 안 함") }, onClick = { calendarId = null; showCalendarMenu = false })
                        calendars.forEach { cal ->
                            DropdownMenuItem(
                                text = { Text(cal.distinctName(calendars)) },
                                onClick = {
                                    calendarId = cal.id
                                    showCalendarMenu = false
                                    if (cal.hasRestrictedEventColors()) {
                                        nearestColor(color, AccountEventColors.forAccount(context, cal.accountName, cal.accountType))?.let { color = it.argb }
                                    }
                                },
                            )
                        }
                    }
                }
                Text("색상", style = MaterialTheme.typography.labelMedium)
                ColorChoiceRow(selected = color, recommended = recommended, restricted = restricted, onSelect = { it?.let { c -> color = c } })
                Text("기본 알림", style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(onClick = { showReminderMenu = true }) { Text(reminderText(reminder).removePrefix("알림: ").removePrefix("알림 ")) }
                    DropdownMenu(expanded = showReminderMenu, onDismissRequest = { showReminderMenu = false }) {
                        DropdownMenuItem(text = { Text("앱 기본값") }, onClick = { reminder = null; showReminderMenu = false })
                        DropdownMenuItem(text = { Text("알림 없음") }, onClick = { reminder = -1; showReminderMenu = false })
                        REMINDER_PRESET_MINUTES.forEach { m ->
                            DropdownMenuItem(text = { Text(reminderLabel(m)) }, onClick = { reminder = m; showReminderMenu = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        EventTypeEntity(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            colorArgb = color,
                            defaultCalendarId = calendarId,
                            defaultReminderMinutes = reminder,
                            sortOrder = existing?.sortOrder ?: nextSortOrder,
                            titleKeywords = parseTitleKeywords(keywords).joinToString(", "),
                        ),
                    )
                },
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
