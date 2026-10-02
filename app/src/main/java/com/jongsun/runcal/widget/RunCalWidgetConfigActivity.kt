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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.jongsun.runcal.CalendarObserverManager
import androidx.compose.material3.RadioButton
import com.jongsun.runcal.data.AppPreset
import com.jongsun.runcal.data.CALENDAR_PERMISSIONS
import com.jongsun.runcal.data.SharedPresets
import com.jongsun.runcal.data.CalendarInfo
import com.jongsun.runcal.data.CalendarRepository
import com.jongsun.runcal.data.hasCalendarPermissions
import com.jongsun.runcal.data.room.NotionDatabaseEntity
import com.jongsun.runcal.data.room.RunCalDatabase
import com.jongsun.runcal.ui.theme.RunCalTheme
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 위젯을 홈 화면에 배치할 때(또는 앱에서 재설정할 때) 뜨는 인스턴스별 프리셋/표시 설정 화면. */
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

    var presets by remember { mutableStateOf<List<AppPreset>>(emptyList()) }
    var linkEnabled by remember { mutableStateOf(true) }
    var pinned by remember { mutableStateOf(false) }
    var pinnedPresetId by remember { mutableStateOf<String?>(null) }
    var fontStep by remember { mutableIntStateOf(DEFAULT_FONT_SCALE_STEP) }
    var opacity by remember { mutableFloatStateOf(DEFAULT_BACKGROUND_OPACITY) }
    var showWeekNumber by remember { mutableStateOf(false) }
    var showLunar by remember { mutableStateOf(false) }
    var todoIncludeTomorrow by remember { mutableStateOf(false) }
    val isTodoWidget = remember(appWidgetId) { WidgetKind.forAppWidgetId(context, appWidgetId) == WidgetKind.TODO_LIST }
    var loaded by remember { mutableStateOf(false) }
    // 위젯 종류에 따라 불필요한 항목(예: 오늘 위젯의 주차 번호)을 숨긴다.
    val isMonthlyWidget = remember(appWidgetId) { WidgetKind.forAppWidgetId(context, appWidgetId).isMonthly }
    // 음력은 공간이 넉넉한 4x5 월간 확장 위젯에서만 그린다.
    val isExpandedWidget = remember(appWidgetId) { WidgetKind.forAppWidgetId(context, appWidgetId) == WidgetKind.MONTHLY_EXPANDED }

    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        val existing = loadWidgetFilterSettings(context, appWidgetId)
        // 구버전 위젯 프리셋이 남아 있으면 이 시점에 앱 목록으로 옮겨 둔다(목록에 바로 보이도록).
        val current = resolveWidgetPreset(context, appWidgetId, existing)
        val shared = SharedPresets.snapshot(context)
        val latest = loadWidgetFilterSettings(context, appWidgetId)
        presets = shared.presets
        linkEnabled = shared.linkEnabled
        pinned = latest.presetPinned
        pinnedPresetId = latest.presetId ?: current.id
        fontStep = latest.fontScaleStep
        opacity = latest.backgroundOpacity
        showWeekNumber = latest.showWeekNumber
        showLunar = latest.showLunar
        todoIncludeTomorrow = latest.todoIncludeTomorrow
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
            Text(text = "프리셋", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "프리셋 목록은 앱과 함께 씁니다. 추가·편집은 앱의 설정 > 필터 프리셋에서 하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 연동이 꺼져 있으면 위젯은 항상 자기 선택을 쓰므로 "고정" 여부는 의미가 없다.
            val choosesOwn = !linkEnabled || pinned
            if (linkEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !pinned, onClick = { pinned = false })
                    Text("앱과 연동 — 앱에서 고른 프리셋을 따라감", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = pinned, onClick = { pinned = true })
                    Text("이 위젯은 아래 프리셋으로 고정", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    text = "앱·위젯 프리셋 연동이 꺼져 있어 이 위젯은 아래에서 고른 프리셋을 씁니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (choosesOwn) {
                presets.forEach { preset ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { pinnedPresetId = preset.id }.padding(start = 24.dp),
                    ) {
                        RadioButton(selected = pinnedPresetId == preset.id, onClick = { pinnedPresetId = preset.id })
                        Box(modifier = Modifier.size(12.dp).background(Color(preset.colorArgb), CircleShape))
                        Text(text = preset.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
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

            if (isTodoWidget) {
                Text(text = "표시 범위", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !todoIncludeTomorrow, onClick = { todoIncludeTomorrow = false })
                    Text("오늘만", style = MaterialTheme.typography.bodyMedium)
                    RadioButton(selected = todoIncludeTomorrow, onClick = { todoIncludeTomorrow = true })
                    Text("오늘·내일", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = "위젯 오른쪽 위 버튼으로도 바로 바꿀 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isMonthlyWidget) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = showWeekNumber, onCheckedChange = { showWeekNumber = it })
                    Text(text = "주차 번호 표시", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (isExpandedWidget) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = showLunar, onCheckedChange = { showLunar = it })
                    Column {
                        Text(text = "음력 표시", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "초하루·보름·그믐을 날짜 옆에 작게 표시합니다(4x5 확장 위젯 전용).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        Log.d("RunCal", "WidgetConfigScreen: appWidgetId=$appWidgetId pinned=$pinned step=$fontStep opacity=$opacity")
                        saveWidgetFilterSettings(
                            context = context,
                            appWidgetId = appWidgetId,
                            fontScaleStep = fontStep,
                            backgroundOpacity = opacity,
                            showWeekNumber = showWeekNumber,
                            showLunar = showLunar,
                            presetPinned = if (linkEnabled) pinned else false,
                            presetId = pinnedPresetId,
                            todoIncludeTomorrow = todoIncludeTomorrow,
                        )
                        onSaved()
                    }
                }) { Text("저장") }

                OutlinedButton(onClick = onCancel) { Text("취소") }
            }
        }
    }
}
