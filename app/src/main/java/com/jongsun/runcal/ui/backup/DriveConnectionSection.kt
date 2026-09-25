package com.jongsun.runcal.ui.backup

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jongsun.runcal.data.AppSettingsRepository
import com.jongsun.runcal.data.backup.drive.DriveAuthManager
import com.jongsun.runcal.data.backup.drive.DriveAuthorizationOutcome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** OAuth 동의 화면이 Testing 상태라 리프레시 토큰이 7일 뒤 만료된다 — 월간(30일) 백업이 조용히
 * 계속 건너뛰어져도 알아챌 수 있게, 이 기간을 넘기면 마지막 성공 표시를 경고색으로 바꾼다. */
private const val STALE_SUCCESS_THRESHOLD_DAYS = 30L

/**
 * Drive 연결/연결 해제 + 재연결 안내. 액세스 토큰은 저장하지 않는다(Play services가 캐시) —
 * 여기서 영구히 들고 있는 건 표시용 계정 라벨([AppSettings.driveAccountEmail])뿐이다.
 * Drive가 연결돼 있지 않아도 이 섹션 밖의 로컬 백업 기능은 전혀 영향을 받지 않는다.
 */
@Composable
fun DriveConnectionSection(modifier: Modifier = Modifier, onConnectedChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val settingsRepository = remember { AppSettingsRepository(context) }
    val authManager = remember { DriveAuthManager(context) }

    var accountEmail by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var lastSuccessAtMillis by remember { mutableStateOf<Long?>(null) }
    var pendingEmail by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        settingsRepository.settings.collectLatest { settings ->
            accountEmail = settings.driveAccountEmail
            lastError = settings.driveLastError
            lastSuccessAtMillis = settings.driveLastSuccessAtMillis
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        scope.launch {
            val email = pendingEmail
            runCatching { authManager.finishAuthorization(result.data) }
                .onSuccess {
                    settingsRepository.setDriveAccountEmail(email)
                    settingsRepository.setDriveLastError(null)
                    statusMessage = "Drive에 연결했습니다"
                    onConnectedChanged()
                }
                .onFailure { statusMessage = "Drive 연결 실패: ${it.message}" }
            busy = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = "Drive 연동", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "월간 백업과 수동 백업을 내 Google Drive의 앱 전용 저장 공간(appDataFolder)에 올릴 수 있습니다. " +
                "이 공간은 Drive 앱 화면에 보이지 않고 RunCal만 접근합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = accountEmail?.let { "연결됨: $it" } ?: "연결 안 됨",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (accountEmail == null) {
                OutlinedButton(
                    enabled = !busy && activity != null,
                    onClick = {
                        val currentActivity = activity ?: return@OutlinedButton
                        scope.launch {
                            busy = true
                            runCatching {
                                val email = authManager.signIn(currentActivity)
                                pendingEmail = email
                                when (val outcome = authManager.authorize(currentActivity)) {
                                    is DriveAuthorizationOutcome.Authorized -> {
                                        settingsRepository.setDriveAccountEmail(email)
                                        settingsRepository.setDriveLastError(null)
                                        statusMessage = "Drive에 연결했습니다"
                                        onConnectedChanged()
                                        busy = false
                                    }
                                    is DriveAuthorizationOutcome.NeedsConsent -> {
                                        consentLauncher.launch(
                                            IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build(),
                                        )
                                        // busy는 consentLauncher 콜백에서 풀어준다.
                                    }
                                }
                            }.onFailure {
                                statusMessage = "Drive 연결 실패: ${it.message}"
                                busy = false
                            }
                        }
                    },
                ) { Text("연결") }
            } else {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val email = accountEmail ?: return@TextButton
                        scope.launch {
                            busy = true
                            runCatching { authManager.revoke(email) }
                            settingsRepository.setDriveAccountEmail(null)
                            settingsRepository.setDriveLastError(null)
                            statusMessage = "Drive 연결을 해제했습니다"
                            busy = false
                        }
                    },
                ) { Text("연결 해제") }
            }
        }
        val isStale = lastSuccessAtMillis == null ||
            System.currentTimeMillis() - lastSuccessAtMillis!! > STALE_SUCCESS_THRESHOLD_DAYS * 24 * 60 * 60 * 1000L
        Text(
            text = "마지막 성공: " + (lastSuccessAtMillis?.let { formatDriveTimestamp(it) } ?: "기록 없음"),
            style = MaterialTheme.typography.bodySmall,
            color = if (isStale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        lastError?.let { error ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (activity != null) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                runCatching {
                                    val email = authManager.signIn(activity)
                                    pendingEmail = email
                                    when (val outcome = authManager.authorize(activity)) {
                                        is DriveAuthorizationOutcome.Authorized -> {
                                            settingsRepository.setDriveAccountEmail(email)
                                            settingsRepository.setDriveLastError(null)
                                            statusMessage = "Drive에 다시 연결했습니다"
                                            onConnectedChanged()
                                            busy = false
                                        }
                                        is DriveAuthorizationOutcome.NeedsConsent -> {
                                            consentLauncher.launch(
                                                IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build(),
                                            )
                                        }
                                    }
                                }.onFailure {
                                    statusMessage = "재연결 실패: ${it.message}"
                                    busy = false
                                }
                            }
                        },
                    ) { Text("재연결") }
                }
            }
        }
        statusMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun formatDriveTimestamp(atMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(atMillis))
