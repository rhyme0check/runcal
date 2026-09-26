package com.jongsun.runcal

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jongsun.runcal.data.CALENDAR_PERMISSIONS
import com.jongsun.runcal.data.hasCalendarPermissions
import com.jongsun.runcal.ui.calendar.RunCalMainScaffold
import com.jongsun.runcal.ui.theme.RunCalTheme
import com.jongsun.runcal.work.WorkScheduler
import java.time.LocalDate
import java.time.YearMonth

/** 위젯/알림에서 앱으로 진입할 때 어디로 이동할지를 나타낸다. */
sealed interface DeepLinkTarget {
    data class Day(val date: LocalDate) : DeepLinkTarget
    data class Month(val yearMonth: YearMonth) : DeepLinkTarget
    /** 알림 탭 전용 — 날짜로 이동하는 데서 그치지 않고 그 일정의 상세(편집) 다이얼로그까지 연다. */
    data class Event(val eventId: Long, val date: LocalDate, val occurrenceBeginMillis: Long?) : DeepLinkTarget
}

class MainActivity : ComponentActivity() {
    private var pendingDeepLinkTarget by mutableStateOf<DeepLinkTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (hasCalendarPermissions(this)) {
            CalendarObserverManager.register(this)
        }
        pendingDeepLinkTarget = extractDeepLinkTarget(intent)
        setContent {
            RunCalTheme {
                RunCalApp(
                    pendingDeepLinkTarget = pendingDeepLinkTarget,
                    onDeepLinkConsumed = { pendingDeepLinkTarget = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLinkTarget = extractDeepLinkTarget(intent)
    }

    companion object {
        /** 위젯에서 날짜 칸을 탭했을 때 전달되는 대상 날짜(LocalDate.toEpochDay()). */
        const val EXTRA_TARGET_DATE_EPOCH_DAY = "com.jongsun.runcal.EXTRA_TARGET_DATE_EPOCH_DAY"

        /** 위젯 헤더의 빈 영역을 탭했을 때 전달되는 대상 연월(YearMonth.toString(), 예: "2026-09"). */
        const val EXTRA_TARGET_YEAR_MONTH = "com.jongsun.runcal.EXTRA_TARGET_YEAR_MONTH"

        /** 알림을 탭했을 때 전달되는 대상 일정의 CalendarContract 이벤트 id. */
        const val EXTRA_TARGET_EVENT_ID = "com.jongsun.runcal.EXTRA_TARGET_EVENT_ID"

        /** 알림이 가리키는 반복 일정의 특정 회차 시작 시각(ms). */
        const val EXTRA_TARGET_INSTANCE_BEGIN_MILLIS = "com.jongsun.runcal.EXTRA_TARGET_INSTANCE_BEGIN_MILLIS"

        private fun extractDeepLinkTarget(intent: Intent?): DeepLinkTarget? {
            val eventId = intent?.getLongExtra(EXTRA_TARGET_EVENT_ID, -1L) ?: -1L
            val epochDay = intent?.getLongExtra(EXTRA_TARGET_DATE_EPOCH_DAY, Long.MIN_VALUE) ?: Long.MIN_VALUE
            if (eventId > 0 && epochDay != Long.MIN_VALUE) {
                val occurrenceBegin = intent?.getLongExtra(EXTRA_TARGET_INSTANCE_BEGIN_MILLIS, -1L)?.takeIf { it > 0 }
                return DeepLinkTarget.Event(eventId, LocalDate.ofEpochDay(epochDay), occurrenceBegin)
            }
            if (epochDay != Long.MIN_VALUE) {
                return DeepLinkTarget.Day(LocalDate.ofEpochDay(epochDay))
            }
            val yearMonthText = intent?.getStringExtra(EXTRA_TARGET_YEAR_MONTH)
            if (yearMonthText != null) {
                val yearMonth = runCatching { YearMonth.parse(yearMonthText) }.getOrNull()
                if (yearMonth != null) return DeepLinkTarget.Month(yearMonth)
            }
            return null
        }
    }
}

@Composable
fun RunCalApp(
    pendingDeepLinkTarget: DeepLinkTarget? = null,
    onDeepLinkConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(hasCalendarPermissions(context)) }
    var permanentlyDenied by remember { mutableStateOf(false) }

    // 알림 권한(POST_NOTIFICATIONS)은 캘린더 권한과 별개로 요청한다 — 거부해도 캘린더/위젯 기능은
    // 그대로 쓸 수 있어야 하므로 화면을 막지 않고, 결과와 무관하게 바로 다음으로 넘어간다.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permissionGranted = results.values.all { it }
        if (permissionGranted) {
            CalendarObserverManager.register(context)
            WorkScheduler.triggerReminderResyncNow(context)
        } else {
            val activity = context as? Activity
            val shouldShowRationale = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, CALENDAR_PERMISSIONS.first())
            permanentlyDenied = !shouldShowRationale
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(CALENDAR_PERMISSIONS)
        } else {
            WorkScheduler.triggerReminderResyncNow(context)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (permissionGranted) {
        RunCalMainScaffold(
            pendingDeepLinkTarget = pendingDeepLinkTarget,
            onDeepLinkConsumed = onDeepLinkConsumed,
            modifier = modifier,
        )
    } else {
        Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
            PermissionRationaleScreen(
                modifier = Modifier.padding(innerPadding),
                permanentlyDenied = permanentlyDenied,
                onRequestPermission = { permissionLauncher.launch(CALENDAR_PERMISSIONS) },
                onOpenSettings = { openAppSettings(context) },
            )
        }
    }
}

@Composable
private fun PermissionRationaleScreen(
    modifier: Modifier = Modifier,
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "캘린더 권한이 필요합니다", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "RunCal은 캘린더 화면과 홈 화면 위젯에 일정을 표시하기 위해 캘린더 읽기/쓰기 권한이 필요합니다.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        if (permanentlyDenied) {
            Button(onClick = onOpenSettings) { Text("설정에서 권한 허용하기") }
        } else {
            Button(onClick = onRequestPermission) { Text("권한 요청하기") }
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}
