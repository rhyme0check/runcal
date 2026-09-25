package com.jongsun.runcal.export

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.data.room.NotionDatabaseEntity
import com.jongsun.runcal.data.weekRange
import com.jongsun.runcal.ui.calendar.CalendarViewModel
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun WeeklyExportDialog(viewModel: CalendarViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            WeeklyExportContent(viewModel = viewModel, onDismiss = onDismiss)
        }
    }
}

/** null = 캘린더 소스, non-null = 그 Notion DB가 소스. */
private typealias ExportSource = NotionDatabaseEntity?

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeeklyExportContent(viewModel: CalendarViewModel, onDismiss: () -> Unit) {
    val weekStartDay by viewModel.weekStartDay.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val notionDatabases by viewModel.notionDatabases.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val zone = remember { ZoneId.systemDefault() }
    val calendarNameById = remember(calendars) { calendars.associate { it.id to it.displayName } }

    var source by remember { mutableStateOf<ExportSource>(null) }
    var rangeType by remember { mutableStateOf(ExportRangeType.THIS_WEEK) }
    var customStart by remember { mutableStateOf(LocalDate.now()) }
    var customEnd by remember { mutableStateOf(LocalDate.now().plusDays(6)) }
    var format by remember { mutableStateOf(ExportFormat.MARKDOWN) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var previewText by remember { mutableStateOf("") }
    var rowCount by remember { mutableStateOf(0) }
    var copiedMessage by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    // source가 사라진 DB를 계속 가리키지 않도록(예: 다른 화면에서 삭제됨) 목록과 동기화
    LaunchedEffect(notionDatabases) {
        val current = source
        if (current != null && notionDatabases.none { it.id == current.id }) {
            source = null
        }
    }

    val (rangeStart, rangeEndExclusive) = remember(rangeType, customStart, customEnd, weekStartDay) {
        when (rangeType) {
            ExportRangeType.THIS_WEEK -> weekRange(LocalDate.now(), weekStartDay)
            ExportRangeType.NEXT_WEEK -> weekRange(LocalDate.now().plusWeeks(1), weekStartDay)
            ExportRangeType.CUSTOM -> customStart to customEnd.plusDays(1)
        }
    }

    LaunchedEffect(rangeStart, rangeEndExclusive, format, source, refreshTick) {
        val startMillis = rangeStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = rangeEndExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val activeSource = source
        val table = if (activeSource == null) {
            buildCalendarExportTable(viewModel.calendarEventsInRange(startMillis, endMillis), calendarNameById, zone)
        } else {
            val rows = viewModel.notionEventsInRangeForExport(activeSource.id, startMillis, endMillis)
            buildNotionExportTable(rows, activeSource, zone)
        }
        rowCount = table.rows.size
        previewText = when (format) {
            ExportFormat.MARKDOWN -> renderMarkdownTable(table)
            ExportFormat.CSV -> renderCsv(table)
        }
        copiedMessage = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "주간표 내보내기", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "닫기") }
        }

        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Text(text = "소스", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = source == null, onClick = { source = null }, label = { Text("캘린더") })
                notionDatabases.forEach { database ->
                    FilterChip(
                        selected = source?.id == database.id,
                        onClick = { source = database },
                        label = { Text(database.displayName) },
                    )
                }
            }

            source?.let { database ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "마지막 동기화: ${lastSyncedLabel(database.lastSyncedAtMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        enabled = !syncing,
                        onClick = {
                            scope.launch {
                                syncing = true
                                viewModel.syncNotionDatabase(database)
                                syncing = false
                                refreshTick++
                            }
                        },
                    ) { Text(if (syncing) "동기화 중..." else "지금 동기화") }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(text = "범위", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = rangeType == ExportRangeType.THIS_WEEK,
                    onClick = { rangeType = ExportRangeType.THIS_WEEK },
                    label = { Text("이번 주") },
                )
                FilterChip(
                    selected = rangeType == ExportRangeType.NEXT_WEEK,
                    onClick = { rangeType = ExportRangeType.NEXT_WEEK },
                    label = { Text("다음 주") },
                )
                FilterChip(
                    selected = rangeType == ExportRangeType.CUSTOM,
                    onClick = { rangeType = ExportRangeType.CUSTOM },
                    label = { Text("임의 기간") },
                )
            }

            if (rangeType == ExportRangeType.CUSTOM) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    OutlinedButton(onClick = { showStartPicker = true }) { Text("시작 ${customStart}") }
                    OutlinedButton(onClick = { showEndPicker = true }) { Text("종료 ${customEnd}") }
                }
            } else {
                Text(
                    text = "${rangeStart} ~ ${rangeEndExclusive.minusDays(1)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(text = "형식", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = format == ExportFormat.MARKDOWN,
                    onClick = { format = ExportFormat.MARKDOWN },
                    label = { Text("마크다운 표") },
                )
                FilterChip(
                    selected = format == ExportFormat.CSV,
                    onClick = { format = ExportFormat.CSV },
                    label = { Text("CSV") },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(text = "미리보기 (${rowCount}건)", style = MaterialTheme.typography.titleMedium)
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                SelectionContainer {
                    Text(
                        text = previewText.ifBlank { "표시할 일정이 없습니다" },
                        modifier = Modifier
                            .padding(12.dp)
                            .horizontalScroll(rememberScrollState())
                            .height(240.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
            if (copiedMessage) {
                Text(
                    text = "클립보드에 복사했습니다",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    clipboardManager.setText(AnnotatedString(previewText))
                    copiedMessage = true
                },
            ) { Text("클립보드 복사") }
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, previewText)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "주간표 공유"))
                },
            ) { Text("공유") }
        }
    }

    if (showStartPicker) {
        DatePickerField(
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
        DatePickerField(
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

private fun lastSyncedLabel(atMillis: Long): String {
    if (atMillis <= 0L) return "동기화 전"
    val formatter = SimpleDateFormat("HH:mm", Locale.KOREA)
    return formatter.format(Date(atMillis))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerField(initialDate: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    // Material3 DatePicker는 UTC 자정 기준 epoch millis로 날짜를 표현하므로, 로컬 존이 아닌
    // UTC로 변환해야 화면에 보이는 날짜와 선택되는 날짜가 하루 어긋나지 않는다.
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
