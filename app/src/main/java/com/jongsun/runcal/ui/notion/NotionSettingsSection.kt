package com.jongsun.runcal.ui.notion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.data.NOTION_SYNC_INTERVAL_HOUR_OPTIONS
import com.jongsun.runcal.data.room.NotionDatabaseEntity
import com.jongsun.runcal.ui.calendar.CalendarViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun NotionSettingsSection(viewModel: CalendarViewModel, modifier: Modifier = Modifier) {
    val notionDatabases by viewModel.notionDatabases.collectAsStateWithLifecycle()
    val syncIntervalHours by viewModel.notionSyncIntervalHours.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingDatabase by remember { mutableStateOf<NotionDatabaseEntity?>(null) }
    var deletingDatabase by remember { mutableStateOf<NotionDatabaseEntity?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Notion 연동", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("DB 추가")
            }
        }
        Text(
            text = "읽기 전용 연동입니다. Notion에 다시 쓰지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (notionDatabases.isEmpty()) {
            Text(
                text = "등록된 DB가 없습니다",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            notionDatabases.forEach { database ->
                NotionDatabaseRow(
                    database = database,
                    onClick = { editingDatabase = database },
                    onDelete = { deletingDatabase = database },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "동기화 주기", style = MaterialTheme.typography.labelMedium)
        Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NOTION_SYNC_INTERVAL_HOUR_OPTIONS.forEach { hours ->
                FilterChip(
                    selected = syncIntervalHours == hours,
                    onClick = { scope.launch { viewModel.setNotionSyncIntervalHours(hours) } },
                    label = { Text("${hours}시간") },
                )
            }
        }

        TextButton(
            enabled = !syncing && notionDatabases.isNotEmpty(),
            onClick = {
                scope.launch {
                    syncing = true
                    statusMessage = "동기화 중..."
                    val results = viewModel.syncAllNotionDatabases()
                    syncing = false
                    val totalEvents = results.sumOf { it.eventCount }
                    val failed = results.count { it.status != "OK" }
                    statusMessage = if (failed == 0) {
                        "동기화 완료: ${totalEvents}건"
                    } else {
                        "동기화 완료: ${totalEvents}건, 실패 ${failed}건"
                    }
                }
            },
        ) { Text("지금 동기화") }
        statusMessage?.let { message ->
            Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showAddDialog) {
        NotionDbRegisterDialog(viewModel = viewModel, existing = null, onDismiss = { showAddDialog = false })
    }
    editingDatabase?.let { database ->
        NotionDbRegisterDialog(viewModel = viewModel, existing = database, onDismiss = { editingDatabase = null })
    }
    deletingDatabase?.let { database ->
        AlertDialog(
            onDismissRequest = { deletingDatabase = null },
            title = { Text("DB 삭제") },
            text = { Text("'${database.displayName}'을(를) 삭제하면 캐시된 일정도 함께 지워집니다.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { viewModel.deleteNotionDatabase(database.id) }
                    deletingDatabase = null
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deletingDatabase = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun NotionDatabaseRow(database: NotionDatabaseEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(14.dp).background(color = Color(database.colorArgb), shape = CircleShape))
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = database.displayName, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(status = database.lastSyncStatus)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = lastSyncedLabel(database.lastSyncedAtMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "삭제")
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (label, color) = when (status) {
        "OK" -> "동기화됨" to Color(0xFF2E7D32)
        "SCHEMA_INVALID" -> "매핑 오류" to Color(0xFFEF6C00)
        "ERROR" -> "오류" to Color(0xFFC62828)
        else -> "대기중" to Color(0xFF757575)
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

private fun lastSyncedLabel(atMillis: Long): String {
    if (atMillis <= 0L) return "동기화 전"
    val formatter = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
    return formatter.format(Date(atMillis))
}
