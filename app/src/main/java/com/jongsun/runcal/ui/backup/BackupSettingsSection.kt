package com.jongsun.runcal.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jongsun.runcal.data.backup.BackupFileInfo
import com.jongsun.runcal.data.backup.BackupJob
import com.jongsun.runcal.data.backup.BackupPayload
import com.jongsun.runcal.data.backup.RestoreMode
import com.jongsun.runcal.data.backup.RestoreSummary
import com.jongsun.runcal.data.backup.UnsupportedBackupFormatException
import com.jongsun.runcal.ui.calendar.CalendarViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 백업 목록 + 지금 백업(로컬/파일로 내보내기) + 복원(목록에서 또는 임의 파일에서). Drive
 * 연동(6단계 B)은 아직 없다 — 여기서 만드는 백업은 전부 로컬(daily 폴더 또는 사용자가 SAF로
 * 고른 위치)에만 쓰인다. "파일에서 복원"은 앱 데이터 삭제/재설치 후에도 복원할 수 있는 유일한
 * 경로다 — daily 폴더는 filesDir 안이라 앱 데이터와 함께 지워지기 때문.
 */
@Composable
fun BackupSettingsSection(viewModel: CalendarViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backups by remember { mutableStateOf(BackupJob.listDailyBackups(context)) }
    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var restoringPayload by remember { mutableStateOf<BackupPayload?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching { BackupJob.writeManualBackupToUri(context, uri) }
                .onSuccess { statusMessage = "파일로 백업을 저장했습니다" }
                .onFailure { statusMessage = "백업 실패: ${it.message}" }
            busy = false
        }
    }
    val openDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { BackupJob.readPayload(context, uri) }
            .onSuccess { restoringPayload = it }
            .onFailure { statusMessage = "백업 파일을 읽을 수 없습니다: ${it.message}" }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = "백업", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "일일 백업은 24시간마다 자동으로 저장되고 7일간 보관됩니다. Notion 캐시나 토큰은 백업에 포함되지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { BackupJob.writeDailyBackup(context) }
                            .onSuccess { backups = BackupJob.listDailyBackups(context); statusMessage = "백업을 저장했습니다" }
                            .onFailure { statusMessage = "백업 실패: ${it.message}" }
                        busy = false
                    }
                },
            ) { Text("지금 백업") }
            OutlinedButton(
                enabled = !busy,
                onClick = { createDocumentLauncher.launch(manualBackupFileName()) },
            ) { Text("파일로 내보내기") }
        }
        OutlinedButton(enabled = !busy, onClick = { openDocumentLauncher.launch(arrayOf("application/json")) }) {
            Text("파일에서 복원")
        }
        statusMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "백업 목록", style = MaterialTheme.typography.labelMedium)
        if (backups.isEmpty()) {
            Text(
                text = "저장된 백업이 없습니다",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            backups.forEach { info ->
                BackupFileRow(
                    info = info,
                    onClick = {
                        runCatching { BackupJob.readPayload(info.file) }
                            .onSuccess { restoringPayload = it }
                            .onFailure { statusMessage = "백업 파일을 읽을 수 없습니다: ${it.message}" }
                    },
                )
            }
        }
    }

    restoringPayload?.let { payload ->
        RestoreDialog(
            payload = payload,
            viewModel = viewModel,
            onDismiss = { restoringPayload = null },
            onRestored = { summary ->
                restoringPayload = null
                statusMessage = "복원 완료 — 프리셋 ${summary.presetsCount}개, 위젯 ${summary.widgetInstancesRestored}개 반영" +
                    (if (summary.widgetInstancesSkipped > 0) "(${summary.widgetInstancesSkipped}개는 이 기기에 없어 건너뜀)" else "") +
                    ", Notion DB ${summary.notionDatabasesRestored}개, 색상 스타일 ${summary.colorStylesRestored}개, 로컬 일정 ${summary.localEventsRestored}건"
            },
            onFailed = { message -> restoringPayload = null; statusMessage = message },
        )
    }
}

@Composable
private fun BackupFileRow(info: BackupFileInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = formatBackupTimestamp(info.createdAtMillis), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = formatFileSize(info.file.length()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RestoreDialog(
    payload: BackupPayload,
    viewModel: CalendarViewModel,
    onDismiss: () -> Unit,
    onRestored: (RestoreSummary) -> Unit,
    onFailed: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(RestoreMode.MERGE) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("백업 복원") },
        text = {
            Column {
                Text(text = backupSummaryLabel(payload), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "복원 방식", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == RestoreMode.MERGE, onClick = { mode = RestoreMode.MERGE }, label = { Text("병합") })
                    FilterChip(selected = mode == RestoreMode.OVERWRITE, onClick = { mode = RestoreMode.OVERWRITE }, label = { Text("덮어쓰기") })
                }
                Text(
                    text = if (mode == RestoreMode.MERGE) {
                        "프리셋·Notion DB·색상 스타일은 합치고, 로컬 일정은 겹치지 않는 것만 추가합니다."
                    } else {
                        "프리셋·Notion DB·색상 스타일·로컬 일정을 백업 내용으로 전부 교체합니다. 앱 설정은 항상 덮어씁니다."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "복원 직전 현재 상태를 안전 백업으로 먼저 저장합니다. 이 백업이 실패하면 복원은 진행되지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        val safetyBackup = runCatching { BackupJob.writePreRestoreSafetyBackup(context) }
                        if (safetyBackup.isFailure) {
                            onFailed("복원 중단: 안전 백업 실패 (${safetyBackup.exceptionOrNull()?.message})")
                            busy = false
                            return@launch
                        }
                        runCatching { viewModel.restoreFromBackup(payload, mode) }
                            .onSuccess { onRestored(it) }
                            .onFailure { e ->
                                val message = if (e is UnsupportedBackupFormatException) {
                                    "이 백업은 더 최신 버전의 앱에서 만들어졌습니다"
                                } else {
                                    "복원 실패: ${e.message}"
                                }
                                onFailed(message)
                            }
                        busy = false
                    }
                },
            ) { Text(if (busy) "복원 중..." else "복원") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("취소") } },
    )
}

private fun backupSummaryLabel(payload: BackupPayload): String =
    "생성: ${formatBackupTimestamp(payload.createdAtMillis)}\n" +
        "프리셋 ${payload.appPresets.size}개 · 위젯 ${payload.widgetInstances.size}개 · " +
        "Notion DB ${payload.notionDatabases.size}개 · 색상 스타일 ${payload.eventColorStyles.size}개 · " +
        "로컬 일정 ${payload.localEvents.size}건"

private fun manualBackupFileName(): String {
    val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA)
    return "runcal_backup_manual_${formatter.format(Date())}.json"
}

private fun formatBackupTimestamp(atMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(atMillis))

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
}
