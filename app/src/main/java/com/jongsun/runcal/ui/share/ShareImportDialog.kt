package com.jongsun.runcal.ui.share

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.ai.describeEventRange
import com.jongsun.runcal.data.distinctName
import com.jongsun.runcal.data.isRunCalLocal
import com.jongsun.runcal.data.share.IcsEvent
import com.jongsun.runcal.ui.calendar.CalendarViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/**
 * 공유받은 일정 파일(.ics)의 확인 카드(P9). 앱이 직접 읽은 내용만 보여주고(모델 호출 없음),
 * 사용자가 고른 항목만 고른 캘린더에 추가한다.
 */
@Composable
fun ShareIcsDialog(viewModel: CalendarViewModel, events: List<IcsEvent>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val defaultReminder by viewModel.defaultReminderMinutes.collectAsStateWithLifecycle()
    val writable = remember(calendars) { calendars.filter { it.isWritable } }
    var calendarId by remember(writable) { mutableStateOf((writable.firstOrNull { it.isRunCalLocal() } ?: writable.firstOrNull())?.id) }
    val checked = remember { mutableStateListOf(*events.map { true }.toTypedArray()) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("공유받은 일정 ${events.size}건") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("추가할 항목과 캘린더를 확인하세요. 아직 아무것도 추가되지 않았습니다.", style = MaterialTheme.typography.bodySmall)
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    events.forEachIndexed { index, e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked[index], onCheckedChange = { checked[index] = it }, enabled = !saving)
                            Column {
                                Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    describeEventRange(e.allDay, e.startMillis, e.endMillis) + if (e.rrule != null) " · 반복" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (e.location.isNotBlank()) Text(e.location, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Text("캘린더", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    writable.forEach { cal ->
                        FilterChip(
                            selected = calendarId == cal.id,
                            onClick = { if (!saving) calendarId = cal.id },
                            label = { Text(cal.distinctName(writable), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            val count = checked.count { it }
            TextButton(
                enabled = !saving && count > 0 && calendarId != null,
                onClick = {
                    val target = calendarId ?: return@TextButton
                    saving = true
                    scope.launch {
                        var added = 0
                        events.forEachIndexed { index, e ->
                            if (!checked[index]) return@forEachIndexed
                            val id = viewModel.createLocalEvent(
                                target, e.title, e.startMillis, e.endMillis, e.allDay, e.location, e.description,
                                defaultReminder?.let { listOf(it) } ?: emptyList(), e.rrule,
                            )
                            if (id > 0) added++
                        }
                        events.firstOrNull()?.let { first ->
                            val zone = if (first.allDay) ZoneOffset.UTC else ZoneId.systemDefault()
                            viewModel.selectDate(Instant.ofEpochMilli(first.startMillis).atZone(zone).toLocalDate())
                        }
                        Toast.makeText(context, "${added}건 추가했습니다", Toast.LENGTH_SHORT).show()
                        saving = false
                        onDismiss()
                    }
                },
            ) { Text("${count}건 추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("취소") } },
    )
}

/** 공유받은 글(P9). AI로 일정을 만들지(입력창에 채워만 둠), 직접 입력할지 고른다. */
@Composable
fun ShareTextDialog(text: String, aiAvailable: Boolean, onAskAi: () -> Unit, onManual: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("공유받은 내용으로 일정 만들기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (aiAvailable) {
                        "AI로 만들면 이 글이 AI 명령 입력창에 채워집니다(보내기를 눌러야 Google로 전송됩니다). 직접 입력하면 첫 줄이 제목, 전체가 메모로 들어갑니다."
                    } else {
                        "첫 줄을 제목, 전체를 메모로 채운 새 일정 화면을 엽니다."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                if (aiAvailable) TextButton(onClick = onAskAi) { Text("AI로 만들기") }
                TextButton(onClick = onManual) { Text("직접 입력") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
