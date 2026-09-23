package com.jongsun.runcal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.jongsun.runcal.data.CALENDAR_PERMISSIONS
import com.jongsun.runcal.data.hasCalendarPermissions
import com.jongsun.runcal.ui.calendar.RunCalMainScaffold
import com.jongsun.runcal.ui.theme.RunCalTheme
import java.time.LocalDate
import java.time.YearMonth

/** 위젯에서 앱으로 진입할 때 어디로 이동할지를 나타낸다. */
sealed interface DeepLinkTarget {
    data class Day(val date: LocalDate) : DeepLinkTarget
    data class Month(val yearMonth: YearMonth) : DeepLinkTarget
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

        private fun extractDeepLinkTarget(intent: Intent?): DeepLinkTarget? {
            val epochDay = intent?.getLongExtra(EXTRA_TARGET_DATE_EPOCH_DAY, Long.MIN_VALUE) ?: Long.MIN_VALUE
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

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permissionGranted = results.values.all { it }
        if (permissionGranted) {
            CalendarObserverManager.register(context)
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
