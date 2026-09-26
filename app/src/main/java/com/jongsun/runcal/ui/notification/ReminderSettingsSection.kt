package com.jongsun.runcal.ui.notification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.notification.canScheduleExactAlarms
import com.jongsun.runcal.notification.exactAlarmSettingsIntent
import com.jongsun.runcal.notification.hasNotificationPermission
import com.jongsun.runcal.notification.ignoreBatteryOptimizationsIntent
import com.jongsun.runcal.notification.isIgnoringBatteryOptimizations
import com.jongsun.runcal.notification.notificationSettingsIntent
import com.jongsun.runcal.ui.calendar.CalendarViewModel
import com.jongsun.runcal.ui.calendar.REMINDER_PRESET_MINUTES
import com.jongsun.runcal.ui.calendar.reminderLabel
import kotlinx.coroutines.launch

/**
 * 알림 전체 on/off, 새 일정의 기본 알림 시간, 그리고 세 가지 권한/설정 상태(알림 권한, 정확한
 * 알람 허용, 배터리 최적화 예외)를 보여준다. 권한은 설정 앱에서 바뀌므로 화면에 돌아올 때마다
 * (ON_RESUME) 다시 확인한다 — 안 그러면 사용자가 설정에서 허용하고 돌아와도 앱은 여전히
 * "허용 안 됨"으로 표시해 혼란을 준다.
 */
@Composable
fun ReminderSettingsSection(viewModel: CalendarViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val remindersEnabled by viewModel.remindersEnabled.collectAsStateWithLifecycle()
    val defaultReminderMinutes by viewModel.defaultReminderMinutes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var showDefaultReminderMenu by remember { mutableStateOf(false) }
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var exactAlarmGranted by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    var batteryOptimizationIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationGranted = hasNotificationPermission(context)
                exactAlarmGranted = canScheduleExactAlarms(context)
                batteryOptimizationIgnored = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = "알림", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "일정 알림 사용", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = remindersEnabled,
                onCheckedChange = { checked -> scope.launch { viewModel.setRemindersEnabled(checked) } },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "새 일정 기본 알림", style = MaterialTheme.typography.bodyLarge)
            Box {
                OutlinedButton(onClick = { showDefaultReminderMenu = true }) {
                    Text(if (defaultReminderMinutes == null) "없음" else reminderLabel(defaultReminderMinutes!!))
                }
                DropdownMenu(expanded = showDefaultReminderMenu, onDismissRequest = { showDefaultReminderMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("없음") },
                        onClick = {
                            scope.launch { viewModel.setDefaultReminderMinutes(null) }
                            showDefaultReminderMenu = false
                        },
                    )
                    REMINDER_PRESET_MINUTES.forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text(reminderLabel(minutes)) },
                            onClick = {
                                scope.launch { viewModel.setDefaultReminderMinutes(minutes) }
                                showDefaultReminderMenu = false
                            },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        PermissionStatusRow(
            label = "알림 권한",
            granted = notificationGranted,
            grantedHint = "허용됨",
            deniedHint = "허용 안 됨 — 알림이 뜨지 않습니다",
            actionLabel = "설정 열기",
            onAction = { context.startActivity(notificationSettingsIntent(context)) },
        )
        PermissionStatusRow(
            label = "정확한 알람",
            granted = exactAlarmGranted,
            grantedHint = "허용됨",
            deniedHint = "허용 안 됨 — 알림이 늦게 울리거나 안 울릴 수 있습니다",
            actionLabel = "허용하기",
            onAction = { context.startActivity(exactAlarmSettingsIntent(context)) },
        )
        PermissionStatusRow(
            label = "배터리 최적화 제외",
            granted = batteryOptimizationIgnored,
            grantedHint = "제외됨",
            deniedHint = "최적화 대상 — 절전 중 알림이 지연될 수 있습니다",
            actionLabel = "제외하기",
            onAction = { context.startActivity(ignoreBatteryOptimizationsIntent(context)) },
        )
    }
}

@Composable
private fun PermissionStatusRow(
    label: String,
    granted: Boolean,
    grantedHint: String,
    deniedHint: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.height(20.dp),
            )
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (granted) grantedHint else deniedHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        }
        if (!granted) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
