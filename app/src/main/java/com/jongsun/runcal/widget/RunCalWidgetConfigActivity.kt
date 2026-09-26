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
import com.jongsun.runcal.data.CALENDAR_PERMISSIONS
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

private fun newPreset(index: Int, calendars: List<CalendarInfo>): WidgetPreset = WidgetPreset(
    id = UUID.randomUUID().toString(),
    name = "프리셋 ${index + 1}",
    colorArgb = PRESET_COLOR_PALETTE[index % PRESET_COLOR_PALETTE.size],
    calendarIds = calendars.map { it.id }.toSet(),
)

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

    var calendars by remember { mutableStateOf<List<CalendarInfo>>(emptyList()) }
    var notionDatabases by remember { mutableStateOf<List<NotionDatabaseEntity>>(emptyList()) }
    var presets by remember { mutableStateOf<List<WidgetPreset>>(emptyList()) }
    var currentPresetIndex by remember { mutableIntStateOf(0) }
    var expandedPresetId by remember { mutableStateOf<String?>(null) }
    var fontStep by remember { mutableIntStateOf(DEFAULT_FONT_SCALE_STEP) }
    var opacity by remember { mutableFloatStateOf(DEFAULT_BACKGROUND_OPACITY) }
    var showWeekNumber by remember { mutableStateOf(false) }
    var showLunar by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    // 위젯 종류에 따라 불필요한 항목(예: 오늘 위젯의 주차 번호)을 숨긴다.
    val isMonthlyWidget = remember(appWidgetId) { WidgetKind.forAppWidgetId(context, appWidgetId).isMonthly }
    // 음력은 공간이 넉넉한 4x5 월간 확장 위젯에서만 그린다.
    val isExpandedWidget = remember(appWidgetId) { WidgetKind.forAppWidgetId(context, appWidgetId) == WidgetKind.MONTHLY_EXPANDED }

    // Notion 연동은 캘린더 권한과 무관하므로 별도로, 바로 불러온다.
    LaunchedEffect(Unit) {
        notionDatabases = RunCalDatabase.getInstance(context).notionDatabaseDao().getAll()
    }

    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        val existing = loadWidgetFilterSettings(context, appWidgetId)
        val loadedCalendars = repository.getCalendars()
        calendars = loadedCalendars
        presets = existing.presets.ifEmpty { listOf(newPreset(0, loadedCalendars)) }
        currentPresetIndex = existing.currentPresetIndex
        fontStep = existing.fontScaleStep
        opacity = existing.backgroundOpacity
        showWeekNumber = existing.showWeekNumber
        showLunar = existing.showLunar
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
                text = "위젯 헤더의 색상 칩을 탭하면 아래 순서로 순환합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            presets.forEachIndexed { index, preset ->
                PresetEditorCard(
                    preset = preset,
                    calendars = calendars,
                    notionDatabases = notionDatabases,
                    expanded = expandedPresetId == preset.id,
                    canDelete = presets.size > 1,
                    onToggleExpand = {
                        expandedPresetId = if (expandedPresetId == preset.id) null else preset.id
                    },
                    onNameChange = { newName ->
                        presets = presets.toMutableList().apply { this[index] = preset.copy(name = newName) }
                    },
                    onColorChange = { newColor ->
                        presets = presets.toMutableList().apply { this[index] = preset.copy(colorArgb = newColor) }
                    },
                    onCalendarToggle = { calendarId, checked ->
                        val current = preset.calendarIds ?: calendars.map { it.id }.toSet()
                        val updated = if (checked) current + calendarId else current - calendarId
                        presets = presets.toMutableList().apply { this[index] = preset.copy(calendarIds = updated) }
                    },
                    onNotionToggle = { databaseId, checked ->
                        // calendarIds와 달리 null이 "전체"가 아니라 "없음"이므로 기본값을 emptySet()으로 둔다.
                        val current = preset.notionDatabaseIds ?: emptySet()
                        val updated = if (checked) current + databaseId else current - databaseId
                        presets = presets.toMutableList().apply { this[index] = preset.copy(notionDatabaseIds = updated) }
                    },
                    onDelete = {
                        presets = presets.filterIndexed { i, _ -> i != index }
                        if (currentPresetIndex >= presets.size) currentPresetIndex = 0
                    },
                )
            }

            OutlinedButton(onClick = { presets = presets + newPreset(presets.size, calendars) }) {
                Text("+ 프리셋 추가")
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
                            text = "날짜 아래에 음력을 작게 표시합니다(4x5 확장 위젯 전용).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        Log.d(
                            "RunCal",
                            "WidgetConfigScreen: appWidgetId=$appWidgetId saving presets=${presets.size} step=$fontStep opacity=$opacity",
                        )
                        saveWidgetFilterSettings(
                            context = context,
                            appWidgetId = appWidgetId,
                            presets = presets,
                            currentPresetIndex = currentPresetIndex,
                            fontScaleStep = fontStep,
                            backgroundOpacity = opacity,
                            showWeekNumber = showWeekNumber,
                            showLunar = showLunar,
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
private fun PresetEditorCard(
    preset: WidgetPreset,
    calendars: List<CalendarInfo>,
    notionDatabases: List<NotionDatabaseEntity>,
    expanded: Boolean,
    canDelete: Boolean,
    onToggleExpand: () -> Unit,
    onNameChange: (String) -> Unit,
    onColorChange: (Int) -> Unit,
    onCalendarToggle: (Long, Boolean) -> Unit,
    onNotionToggle: (String, Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val selectedCalendarIds = preset.calendarIds ?: calendars.map { it.id }.toSet()
    val selectedNotionIds = preset.notionDatabaseIds ?: emptySet()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.size(16.dp).background(Color(preset.colorArgb), CircleShape))
            OutlinedTextField(
                value = preset.name,
                onValueChange = onNameChange,
                singleLine = true,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                label = { Text("이름") },
            )
            IconButton(onClick = onToggleExpand) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "접기" else "펼치기",
                )
            }
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "프리셋 삭제")
                }
            }
        }

        if (expanded) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                PRESET_COLOR_PALETTE.forEach { colorArgb ->
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color(colorArgb), CircleShape)
                            .border(
                                width = if (colorArgb == preset.colorArgb) 2.dp else 0.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = CircleShape,
                            )
                            .clickable { onColorChange(colorArgb) },
                    )
                }
            }

            Text(
                text = "표시할 캘린더",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            calendars.forEach { calendar ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = selectedCalendarIds.contains(calendar.id),
                        onCheckedChange = { checked -> onCalendarToggle(calendar.id, checked) },
                    )
                    Box(modifier = Modifier.size(10.dp).background(Color(calendar.color), CircleShape))
                    Column {
                        Text(text = calendar.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(text = calendar.accountName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // 등록된 Notion DB가 있을 때만 보여준다.
            if (notionDatabases.isNotEmpty()) {
                Text(
                    text = "Notion 데이터베이스",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                notionDatabases.forEach { database ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = selectedNotionIds.contains(database.id),
                            onCheckedChange = { checked -> onNotionToggle(database.id, checked) },
                        )
                        Box(modifier = Modifier.size(10.dp).background(Color(database.colorArgb), CircleShape))
                        Text(text = database.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
