package com.jongsun.runcal.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.jongsun.runcal.CalendarObserverManager
import com.jongsun.runcal.data.CALENDAR_PERMISSIONS
import com.jongsun.runcal.data.CalendarInfo
import com.jongsun.runcal.data.CalendarRepository
import com.jongsun.runcal.data.hasCalendarPermissions
import com.jongsun.runcal.ui.theme.RunCalTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 위젯을 홈 화면에 배치할 때(또는 앱에서 재설정할 때) 뜨는 인스턴스별 필터/표시 설정 화면. */
class RunCalWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            RunCalTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WidgetConfigScreen(
                        appWidgetId = appWidgetId,
                        modifier = Modifier.padding(innerPadding),
                        onSaved = {
                            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            setResult(RESULT_OK, resultValue)
                            finish()
                        },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetConfigScreen(
    appWidgetId: Int,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { CalendarRepository(context) }
    val scope = rememberCoroutineScope()

    var permissionGranted by remember { mutableStateOf(hasCalendarPermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permissionGranted = results.values.all { it }
        if (permissionGranted) {
            CalendarObserverManager.register(context)
        }
    }
    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(CALENDAR_PERMISSIONS)
    }

    var glanceId by remember { mutableStateOf<GlanceId?>(null) }
    var calendars by remember { mutableStateOf<List<CalendarInfo>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var fontStep by remember { mutableIntStateOf(DEFAULT_FONT_SCALE_STEP) }
    var opacity by remember { mutableFloatStateOf(DEFAULT_BACKGROUND_OPACITY) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        val id = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
        glanceId = id
        val existing = loadWidgetFilterSettings(context, id)
        val loadedCalendars = repository.getCalendars()
        calendars = loadedCalendars
        selectedIds = existing.selectedCalendarIds ?: loadedCalendars.map { it.id }.toSet()
        fontStep = existing.fontScaleStep
        opacity = existing.backgroundOpacity
        loaded = true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "위젯 설정", style = MaterialTheme.typography.headlineSmall)

        if (!permissionGranted) {
            Text(text = "캘린더 권한이 필요합니다. 권한을 허용해주세요.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { permissionLauncher.launch(CALENDAR_PERMISSIONS) }) { Text("권한 요청하기") }
            OutlinedButton(onClick = onCancel) { Text("취소") }
        } else if (!loaded) {
            Text(text = "불러오는 중...", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(text = "표시할 캘린더", style = MaterialTheme.typography.titleMedium)
            if (calendars.isEmpty()) {
                Text(text = "표시할 캘린더가 없습니다", style = MaterialTheme.typography.bodyMedium)
            } else {
                calendars.forEach { calendar ->
                    CalendarCheckboxRow(
                        calendar = calendar,
                        checked = selectedIds.contains(calendar.id),
                        onCheckedChange = { checked ->
                            selectedIds = if (checked) selectedIds + calendar.id else selectedIds - calendar.id
                        },
                    )
                }
            }

            HorizontalDivider()

            Text(text = "글자 크기: ${fontScaleStepLabel(fontStep)}", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = fontStep.toFloat(),
                onValueChange = { fontStep = it.roundToInt().coerceIn(MIN_FONT_SCALE_STEP, MAX_FONT_SCALE_STEP) },
                valueRange = MIN_FONT_SCALE_STEP.toFloat()..MAX_FONT_SCALE_STEP.toFloat(),
                steps = MAX_FONT_SCALE_STEP - MIN_FONT_SCALE_STEP - 1,
            )

            Text(text = "배경 투명도: ${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = opacity,
                onValueChange = { opacity = it },
                valueRange = 0f..1f,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val id = glanceId ?: run {
                        Log.e("RunCal", "WidgetConfigScreen: appWidgetId=$appWidgetId glanceId not ready, save aborted")
                        return@Button
                    }
                    scope.launch {
                        Log.d(
                            "RunCal",
                            "WidgetConfigScreen: appWidgetId=$appWidgetId saving " +
                                "selected=${selectedIds.size} step=$fontStep opacity=$opacity",
                        )
                        saveWidgetFilterSettings(
                            context = context,
                            glanceId = id,
                            settings = WidgetFilterSettings(
                                selectedCalendarIds = selectedIds,
                                fontScaleStep = fontStep,
                                backgroundOpacity = opacity,
                            ),
                        )
                        onSaved()
                    }
                }) { Text("저장") }

                OutlinedButton(onClick = onCancel) { Text("취소") }
            }
        }
    }
}

@Composable
private fun CalendarCheckboxRow(
    calendar: CalendarInfo,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Box(modifier = Modifier.size(12.dp).background(color = Color(calendar.color), shape = CircleShape))
        Column {
            Text(text = calendar.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(text = calendar.accountName, style = MaterialTheme.typography.bodySmall)
        }
    }
}
