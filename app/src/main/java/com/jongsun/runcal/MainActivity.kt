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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.glance.appwidget.updateAll
import com.jongsun.runcal.data.CALENDAR_PERMISSIONS
import com.jongsun.runcal.data.CalendarInfo
import com.jongsun.runcal.data.CalendarRepository
import com.jongsun.runcal.data.hasCalendarPermissions
import com.jongsun.runcal.data.monthRangeMillis
import com.jongsun.runcal.ui.theme.RunCalTheme
import com.jongsun.runcal.widget.RunCalCalendarWidget
import java.time.YearMonth
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (hasCalendarPermissions(this)) {
            CalendarObserverManager.register(this)
        }
        setContent {
            RunCalTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RunCalApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun RunCalApp(modifier: Modifier = Modifier) {
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
        CalendarHomeScreen(modifier = modifier)
    } else {
        PermissionRationaleScreen(
            modifier = modifier,
            permanentlyDenied = permanentlyDenied,
            onRequestPermission = { permissionLauncher.launch(CALENDAR_PERMISSIONS) },
            onOpenSettings = { openAppSettings(context) },
        )
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
            text = "RunCal은 홈 화면 위젯에 일정을 표시하기 위해 캘린더 읽기/쓰기 권한이 필요합니다.",
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

@Composable
private fun CalendarHomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { CalendarRepository(context) }
    val scope = rememberCoroutineScope()

    var calendars by remember { mutableStateOf<List<CalendarInfo>>(emptyList()) }
    var eventCountThisMonth by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        calendars = repository.getCalendars()
        val (start, end) = monthRangeMillis(YearMonth.now())
        eventCountThisMonth = repository.getEvents(start, end, calendarIds = null).size
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "RunCal", style = MaterialTheme.typography.headlineSmall)
        Text(text = "이번 달 일정 ${eventCountThisMonth}건", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    repository.ensureLocalTestCalendar()
                    statusMessage = "테스트 캘린더를 생성했습니다"
                    refresh()
                    RunCalCalendarWidget().updateAll(context)
                }
            }) { Text("테스트 캘린더 생성") }

            Button(onClick = {
                scope.launch {
                    val calendarId = repository.ensureLocalTestCalendar()
                    val inserted = repository.addSampleEvents(calendarId, count = 10)
                    statusMessage = "샘플 일정 ${inserted}건을 추가했습니다"
                    refresh()
                    RunCalCalendarWidget().updateAll(context)
                }
            }) { Text("샘플 일정 10건 추가") }
        }

        statusMessage?.let { message ->
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }

        HorizontalDivider()
        Text(text = "캘린더 목록", style = MaterialTheme.typography.titleMedium)

        if (calendars.isEmpty()) {
            Text(text = "표시할 캘린더가 없습니다", style = MaterialTheme.typography.bodyMedium)
        } else {
            calendars.forEach { calendar ->
                CalendarRow(calendar)
            }
        }
    }
}

@Composable
private fun CalendarRow(calendar: CalendarInfo) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(12.dp).background(color = Color(calendar.color), shape = CircleShape))
        Column {
            Text(text = calendar.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(text = calendar.accountName, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}

@Preview(showBackground = true)
@Composable
fun RunCalAppPreview() {
    RunCalTheme {
        PermissionRationaleScreen(
            permanentlyDenied = false,
            onRequestPermission = {},
            onOpenSettings = {},
        )
    }
}
