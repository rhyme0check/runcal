package com.jongsun.runcal.ui.calendar

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.R
import com.jongsun.runcal.data.APP_PRESET_COLOR_PALETTE
import com.jongsun.runcal.data.AppPreset
import com.jongsun.runcal.data.CalendarInfo
import com.jongsun.runcal.data.MAX_APP_FONT_SCALE_STEP
import com.jongsun.runcal.data.MIN_APP_FONT_SCALE_STEP
import com.jongsun.runcal.data.room.NotionDatabaseEntity
import com.jongsun.runcal.export.WeeklyExportDialog
import com.jongsun.runcal.ui.backup.BackupSettingsSection
import com.jongsun.runcal.ui.notification.ReminderSettingsSection
import com.jongsun.runcal.ui.notion.NotionSettingsSection
import com.jongsun.runcal.widget.RunCalCalendarWidgetProvider
import com.jongsun.runcal.widget.RunCalMonthlyCompactWidgetProvider
import com.jongsun.runcal.widget.RunCalMonthlyStandardWidgetProvider
import com.jongsun.runcal.widget.RunCalTodayHorizontalWidgetProvider
import com.jongsun.runcal.widget.RunCalTodayMiniWidgetProvider
import com.jongsun.runcal.widget.RunCalTodayVerticalWidgetProvider
import com.jongsun.runcal.widget.RunCalWidgetConfigActivity
import java.time.DayOfWeek
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: CalendarViewModel, modifier: Modifier = Modifier) {
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val visibleCalendarIds by viewModel.visibleCalendarIds.collectAsStateWithLifecycle()
    val weekStartDay by viewModel.weekStartDay.collectAsStateWithLifecycle()
    val fontScaleStep by viewModel.appFontScaleStep.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val activePresetId by viewModel.activePresetId.collectAsStateWithLifecycle()
    val notionDatabases by viewModel.notionDatabases.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val selectedIds = visibleCalendarIds ?: calendars.map { it.id }.toSet()
    var localFontStep by remember(fontScaleStep) { mutableIntStateOf(fontScaleStep) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var editingPreset by remember { mutableStateOf<AppPreset?>(null) }
    var showAddPresetDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    LazyColumn(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        item {
            Text(
                text = "표시할 캘린더",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            Text(
                text = "앱 화면 전용 설정이며 위젯별 설정과는 독립적으로 동작합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(calendars) { calendar ->
            SettingsCalendarRow(
                calendar = calendar,
                checked = selectedIds.contains(calendar.id),
                onCheckedChange = { checked ->
                    val newIds = if (checked) selectedIds + calendar.id else selectedIds - calendar.id
                    scope.launch { viewModel.setVisibleCalendarIds(newIds) }
                },
            )
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "필터 프리셋", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showAddPresetDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("추가")
                }
            }
            Text(
                text = "표시할 캘린더 조합을 이름으로 저장해두고 한 번에 전환합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        itemsIndexed(presets) { index, preset ->
            PresetRow(
                preset = preset,
                isActive = preset.id == activePresetId,
                canMoveUp = index > 0,
                canMoveDown = index < presets.lastIndex,
                canDelete = presets.size > 1,
                onApply = { scope.launch { viewModel.applyPreset(preset) } },
                onEdit = { editingPreset = preset },
                onDelete = {
                    scope.launch {
                        val updated = presets.filterNot { it.id == preset.id }
                        viewModel.savePresets(updated)
                        if (activePresetId == preset.id) {
                            viewModel.applyPreset(updated.first())
                        }
                    }
                },
                onMoveUp = {
                    scope.launch {
                        val mutable = presets.toMutableList()
                        mutable.add(index - 1, mutable.removeAt(index))
                        viewModel.savePresets(mutable)
                    }
                },
                onMoveDown = {
                    scope.launch {
                        val mutable = presets.toMutableList()
                        mutable.add(index + 1, mutable.removeAt(index))
                        viewModel.savePresets(mutable)
                    }
                },
            )
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            NotionSettingsSection(viewModel = viewModel)
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            ReminderSettingsSection(viewModel = viewModel)
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            ColorStyleSettingsSection(viewModel = viewModel)
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text(text = "주 시작 요일", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = weekStartDay == DayOfWeek.SUNDAY,
                    onClick = { scope.launch { viewModel.setWeekStartDay(DayOfWeek.SUNDAY) } },
                    label = { Text("일요일") },
                )
                FilterChip(
                    selected = weekStartDay == DayOfWeek.MONDAY,
                    onClick = { scope.launch { viewModel.setWeekStartDay(DayOfWeek.MONDAY) } },
                    label = { Text("월요일") },
                )
            }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text(text = "앱 글자 크기: ${appFontScaleStepLabel(localFontStep)}", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = localFontStep.toFloat(),
                onValueChange = { localFontStep = it.roundToInt().coerceIn(MIN_APP_FONT_SCALE_STEP, MAX_APP_FONT_SCALE_STEP) },
                onValueChangeFinished = { scope.launch { viewModel.setAppFontScaleStep(localFontStep) } },
                valueRange = MIN_APP_FONT_SCALE_STEP.toFloat()..MAX_APP_FONT_SCALE_STEP.toFloat(),
                steps = MAX_APP_FONT_SCALE_STEP - MIN_APP_FONT_SCALE_STEP - 1,
            )
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            PlacedWidgetsSection()
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text(text = "내보내기", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            Button(onClick = { showExportDialog = true }) { Text("주간표 내보내기") }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            BackupSettingsSection(viewModel = viewModel)
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text(text = "테스트 도구", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        viewModel.ensureLocalTestCalendar()
                        statusMessage = "테스트 캘린더를 생성했습니다"
                    }
                }) { Text("테스트 캘린더 생성") }

                Button(onClick = {
                    scope.launch {
                        val calendarId = viewModel.ensureLocalTestCalendar()
                        val inserted = viewModel.addSampleEvents(calendarId, count = 10)
                        statusMessage = "샘플 일정 ${inserted}건을 추가했습니다"
                    }
                }) { Text("샘플 일정 10건 추가") }
            }
            statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
            }
        }
    }

    if (showAddPresetDialog) {
        PresetEditDialog(
            existing = null,
            calendars = calendars,
            notionDatabases = notionDatabases,
            onDismiss = { showAddPresetDialog = false },
            onSave = { newPreset ->
                scope.launch { viewModel.savePresets(presets + newPreset) }
                showAddPresetDialog = false
            },
        )
    }
    editingPreset?.let { preset ->
        PresetEditDialog(
            existing = preset,
            calendars = calendars,
            notionDatabases = notionDatabases,
            onDismiss = { editingPreset = null },
            onSave = { updated ->
                scope.launch { viewModel.savePresets(presets.map { if (it.id == updated.id) updated else it }) }
                editingPreset = null
            },
        )
    }
    if (showExportDialog) {
        WeeklyExportDialog(viewModel = viewModel, onDismiss = { showExportDialog = false })
    }
}

private fun appFontScaleStepLabel(step: Int): String = when (step) {
    1 -> "작게"
    2 -> "보통"
    3 -> "크게"
    4 -> "아주 크게"
    else -> "보통"
}

@Composable
private fun SettingsCalendarRow(
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

@Composable
private fun PresetRow(
    preset: AppPreset,
    isActive: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onApply)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(14.dp).background(color = Color(preset.colorArgb), shape = CircleShape))
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isActive) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
            val calendarLabel = if (preset.calendarIds == null) "전체 캘린더" else "캘린더 ${preset.calendarIds.size}개"
            val notionLabel = preset.notionDatabaseIds?.takeIf { it.isNotEmpty() }?.let { " · Notion ${it.size}개" }.orEmpty()
            Text(
                text = calendarLabel + notionLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "위로 이동")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "아래로 이동")
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "프리셋 수정")
        }
        IconButton(onClick = onDelete, enabled = canDelete) {
            Icon(Icons.Default.Delete, contentDescription = "프리셋 삭제")
        }
    }
}

@Composable
private fun PresetEditDialog(
    existing: AppPreset?,
    calendars: List<CalendarInfo>,
    notionDatabases: List<NotionDatabaseEntity>,
    onDismiss: () -> Unit,
    onSave: (AppPreset) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var colorArgb by remember { mutableIntStateOf(existing?.colorArgb ?: APP_PRESET_COLOR_PALETTE.first()) }
    // null(전체)과 빈 집합을 구분해야 하므로, 다이얼로그 안에서는 항상 구체적인 집합으로 다룬다.
    var selectedIds by remember {
        mutableStateOf(existing?.calendarIds ?: calendars.map { it.id }.toSet())
    }
    // Notion은 캘린더와 달리 기본이 "없음"이다 — 새 프리셋은 빈 집합에서 시작한다(opt-in).
    var selectedNotionIds by remember {
        mutableStateOf(existing?.notionDatabaseIds ?: emptySet())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "프리셋 추가" else "프리셋 수정") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "색상", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    APP_PRESET_COLOR_PALETTE.forEach { colorOption ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(colorOption), CircleShape)
                                .border(
                                    width = if (colorOption == colorArgb) 2.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape,
                                )
                                .clickable { colorArgb = colorOption },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "포함할 캘린더", style = MaterialTheme.typography.labelMedium)
                calendars.forEach { calendar ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = selectedIds.contains(calendar.id),
                            onCheckedChange = { checked ->
                                selectedIds = if (checked) selectedIds + calendar.id else selectedIds - calendar.id
                            },
                        )
                        Text(text = calendar.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                // 등록된 Notion DB가 있을 때만 보여준다.
                if (notionDatabases.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Notion 데이터베이스", style = MaterialTheme.typography.labelMedium)
                    notionDatabases.forEach { database ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = selectedNotionIds.contains(database.id),
                                onCheckedChange = { checked ->
                                    selectedNotionIds = if (checked) selectedNotionIds + database.id else selectedNotionIds - database.id
                                },
                            )
                            Box(modifier = Modifier.size(10.dp).background(Color(database.colorArgb), CircleShape))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = database.displayName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val allCalendarsSelected = selectedIds.size == calendars.size
                    onSave(
                        AppPreset(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            colorArgb = colorArgb,
                            calendarIds = if (allCalendarsSelected) null else selectedIds,
                            // 빈 선택은 null로 저장(둘 다 "Notion 없음"으로 취급되므로 동일) —
                            // 절대 "전부 선택"을 null로 저장하지 않는다(null=전체가 아니라 없음이므로).
                            notionDatabaseIds = selectedNotionIds.takeIf { it.isNotEmpty() },
                        ),
                    )
                },
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private data class WidgetKindEntry(val provider: Class<*>, val labelRes: Int)

private val PLACED_WIDGET_KINDS = listOf(
    WidgetKindEntry(RunCalCalendarWidgetProvider::class.java, R.string.runcal_calendar_widget_label),
    WidgetKindEntry(RunCalMonthlyStandardWidgetProvider::class.java, R.string.runcal_monthly_standard_widget_label),
    WidgetKindEntry(RunCalMonthlyCompactWidgetProvider::class.java, R.string.runcal_monthly_compact_widget_label),
    WidgetKindEntry(RunCalTodayMiniWidgetProvider::class.java, R.string.runcal_today_mini_widget_label),
    WidgetKindEntry(RunCalTodayHorizontalWidgetProvider::class.java, R.string.runcal_today_horizontal_widget_label),
    WidgetKindEntry(RunCalTodayVerticalWidgetProvider::class.java, R.string.runcal_today_vertical_widget_label),
)

/** 배치된 위젯 목록(6종 전체) → 각 위젯 설정 진입. (기존 MainActivity 디버그 화면에서 이관) */
@Composable
private fun PlacedWidgetsSection() {
    val context = LocalContext.current
    var placedWidgets by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }

    LaunchedEffect(Unit) {
        val manager = AppWidgetManager.getInstance(context)
        placedWidgets = PLACED_WIDGET_KINDS.flatMap { kind ->
            manager.getAppWidgetIds(ComponentName(context, kind.provider)).map { id -> id to kind.labelRes }
        }.sortedBy { it.first }
    }

    Text(text = "배치된 위젯", style = MaterialTheme.typography.titleMedium)
    if (placedWidgets.isEmpty()) {
        Text(text = "배치된 위젯이 없습니다", style = MaterialTheme.typography.bodyMedium)
    } else {
        placedWidgets.forEach { (appWidgetId, labelRes) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
                    Text(text = "#$appWidgetId", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = {
                    // 위젯이 신규 배치될 때 시스템이 보내는 인텐트와 동일한 action/component로 구성해
                    // 앱에서 재설정하는 경로와 최초 배치 경로가 완전히 동일하게 동작하도록 한다.
                    val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                        component = ComponentName(context, RunCalWidgetConfigActivity::class.java)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    }
                    context.startActivity(intent)
                }) { Text("설정") }
            }
        }
    }
}
