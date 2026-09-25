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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jongsun.runcal.data.AppSettingsRepository
import com.jongsun.runcal.data.backup.BackupJob
import com.jongsun.runcal.data.backup.BackupPayload
import com.jongsun.runcal.data.backup.RestoreMode
import com.jongsun.runcal.data.backup.RestoreSummary
import com.jongsun.runcal.data.backup.UnsupportedBackupFormatException
import com.jongsun.runcal.data.backup.drive.DriveAuthManager
import com.jongsun.runcal.data.backup.drive.DriveBackupFileInfo
import com.jongsun.runcal.data.backup.drive.DriveBackupJob
import com.jongsun.runcal.ui.calendar.CalendarViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** 목록/복원 화면에서 로컬 파일과 Drive 파일을 똑같이 다루기 위한 얇은 래퍼. */
private sealed interface BackupEntry {
    val createdAtMillis: Long
    data class Local(val file: java.io.File, override val createdAtMillis: Long) : BackupEntry
    data class Drive(val info: DriveBackupFileInfo) : BackupEntry {
        override val createdAtMillis get() = info.createdAtMillis
    }
}

private enum class BackupDestination { LOCAL, DRIVE, BOTH }

/**
 * 백업 목록(로컬+Drive 합산, 최신순) + 지금 백업(로컬) + 수동 백업(로컬/Drive/둘 다) +
 * 파일에서 복원 + Drive 연결. Drive가 연결돼 있지 않아도 로컬 백업 전부(지금 백업/파일로
 * 내보내기/파일에서 복원/일일 자동 백업)는 그대로 동작한다 — Drive 관련 기능만 조건부로 보인다.
 */
@Composable
fun BackupSettingsSection(viewModel: CalendarViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = remember { AppSettingsRepository(context) }
    val authManager = remember { DriveAuthManager(context) }

    var localBackups by remember { mutableStateOf(BackupJob.listDailyBackups(context)) }
    var driveBackups by remember { mutableStateOf<List<DriveBackupFileInfo>>(emptyList()) }
    var driveConnected by remember { mutableStateOf<String?>(null) }
    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var restoringPayload by remember { mutableStateOf<BackupPayload?>(null) }
    var showManualBackupDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        settingsRepository.settings.collect { settings ->
            driveConnected = settings.driveAccountEmail
        }
    }
    LaunchedEffect(driveConnected) {
        val token = if (driveConnected != null) authManager.silentAccessToken() else null
        driveBackups = if (token != null) runCatching { DriveBackupJob.listBackups(token) }.getOrDefault(emptyList()) else emptyList()
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { BackupJob.readPayload(context, uri) }
            .onSuccess { restoringPayload = it }
            .onFailure { statusMessage = "백업 파일을 읽을 수 없습니다: ${it.message}" }
    }

    val entries = remember(localBackups, driveBackups) {
        (localBackups.map { BackupEntry.Local(it.file, it.createdAtMillis) } + driveBackups.map { BackupEntry.Drive(it) })
            .sortedByDescending { it.createdAtMillis }
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
                            .onSuccess { localBackups = BackupJob.listDailyBackups(context); statusMessage = "백업을 저장했습니다" }
                            .onFailure { statusMessage = "백업 실패: ${it.message}" }
                        busy = false
                    }
                },
            ) { Text("지금 백업") }
            OutlinedButton(enabled = !busy, onClick = { showManualBackupDialog = true }) { Text("수동 백업...") }
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

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        DriveConnectionSection()

        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "백업 목록", style = MaterialTheme.typography.labelMedium)
        if (entries.isEmpty()) {
            Text(
                text = "저장된 백업이 없습니다",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            entries.forEach { entry ->
                BackupEntryRow(
                    entry = entry,
                    onClick = {
                        when (entry) {
                            is BackupEntry.Local -> {
                                runCatching { BackupJob.readPayload(entry.file) }
                                    .onSuccess { restoringPayload = it }
                                    .onFailure { statusMessage = "백업 파일을 읽을 수 없습니다: ${it.message}" }
                            }
                            is BackupEntry.Drive -> {
                                scope.launch {
                                    busy = true
                                    val token = authManager.silentAccessToken()
                                    if (token == null) {
                                        statusMessage = "Drive 인증이 만료됐습니다. 위에서 다시 연결해주세요."
                                    } else {
                                        runCatching { DriveBackupJob.readPayload(token, entry.info.fileId) }
                                            .onSuccess { restoringPayload = it }
                                            .onFailure { statusMessage = "Drive 백업을 읽을 수 없습니다: ${it.message}" }
                                    }
                                    busy = false
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    if (showManualBackupDialog) {
        ManualBackupDialog(
            driveConnected = driveConnected != null,
            busy = busy,
            onDismiss = { showManualBackupDialog = false },
            onConfirm = { destination ->
                showManualBackupDialog = false
                scope.launch {
                    busy = true
                    val messages = mutableListOf<String>()
                    if (destination == BackupDestination.LOCAL || destination == BackupDestination.BOTH) {
                        runCatching { BackupJob.writeDailyBackup(context) }
                            .onSuccess { localBackups = BackupJob.listDailyBackups(context); messages += "로컬 저장 완료" }
                            .onFailure { messages += "로컬 저장 실패: ${it.message}" }
                    }
                    if (destination == BackupDestination.DRIVE || destination == BackupDestination.BOTH) {
                        val token = authManager.silentAccessToken()
                        if (token == null) {
                            messages += "Drive 인증이 만료됐습니다. 다시 연결해주세요."
                        } else {
                            runCatching { DriveBackupJob.writeManualBackup(context, token) }
                                .onSuccess {
                                    driveBackups = runCatching { DriveBackupJob.listBackups(token) }.getOrDefault(driveBackups)
                                    settingsRepository.setDriveLastSuccessAtMillis(System.currentTimeMillis())
                                    messages += "Drive 업로드 완료"
                                }
                                .onFailure { messages += "Drive 업로드 실패: ${it.message}" }
                        }
                    }
                    statusMessage = messages.joinToString(" · ")
                    busy = false
                }
            },
        )
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
private fun ManualBackupDialog(
    driveConnected: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (BackupDestination) -> Unit,
) {
    var destination by remember { mutableStateOf(BackupDestination.LOCAL) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("수동 백업") },
        text = {
            Column {
                Text(text = "저장 위치", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = destination == BackupDestination.LOCAL, onClick = { destination = BackupDestination.LOCAL }, label = { Text("로컬") })
                    FilterChip(
                        selected = destination == BackupDestination.DRIVE,
                        onClick = { destination = BackupDestination.DRIVE },
                        enabled = driveConnected,
                        label = { Text("Drive") },
                    )
                    FilterChip(
                        selected = destination == BackupDestination.BOTH,
                        onClick = { destination = BackupDestination.BOTH },
                        enabled = driveConnected,
                        label = { Text("둘 다") },
                    )
                }
                if (!driveConnected) {
                    Text(
                        text = "Drive에 저장하려면 먼저 위에서 연결하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onConfirm(destination) }) { Text("백업") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun BackupEntryRow(entry: BackupEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceTag(isDrive = entry is BackupEntry.Drive)
            Text(text = formatBackupTimestamp(entry.createdAtMillis), style = MaterialTheme.typography.bodyMedium)
        }
        val sizeLabel = when (entry) {
            is BackupEntry.Local -> formatFileSize(entry.file.length())
            is BackupEntry.Drive -> entry.info.sizeBytes?.let { formatFileSize(it) } ?: ""
        }
        Text(text = sizeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceTag(isDrive: Boolean) {
    Text(
        text = if (isDrive) "Drive" else "로컬",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
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
                    text = "앱을 재설치한 경우 로컬 일정은 '덮어쓰기'를 선택하세요. 병합 시 기존 일정이 중복 생성될 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
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

private fun formatBackupTimestamp(atMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(atMillis))

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
}
