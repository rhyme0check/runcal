package com.jongsun.runcal.ui.calendar

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.data.CalendarInfo
import com.jongsun.runcal.data.MAX_APP_FONT_SCALE_STEP
import com.jongsun.runcal.data.MIN_APP_FONT_SCALE_STEP
import com.jongsun.runcal.widget.RunCalCalendarWidget
import com.jongsun.runcal.widget.RunCalWidgetConfigActivity
import java.time.DayOfWeek
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: CalendarViewModel, modifier: Modifier = Modifier) {
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val visibleCalendarIds by viewModel.visibleCalendarIds.collectAsStateWithLifecycle()
    val weekStartDay by viewModel.weekStartDay.collectAsStateWithLifecycle()
    val fontScaleStep by viewModel.appFontScaleStep.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val selectedIds = visibleCalendarIds ?: calendars.map { it.id }.toSet()
    var localFontStep by remember(fontScaleStep) { mutableIntStateOf(fontScaleStep) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

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

/** 배치된 위젯 목록 → 각 위젯 설정 진입. (기존 MainActivity 디버그 화면에서 이관) */
@Composable
private fun PlacedWidgetsSection() {
    val context = LocalContext.current
    var widgetIds by remember { mutableStateOf<List<Int>>(emptyList()) }

    LaunchedEffect(Unit) {
        val manager = GlanceAppWidgetManager(context)
        widgetIds = manager.getGlanceIds(RunCalCalendarWidget::class.java).map { manager.getAppWidgetId(it) }
    }

    Text(text = "배치된 위젯", style = MaterialTheme.typography.titleMedium)
    if (widgetIds.isEmpty()) {
        Text(text = "배치된 위젯이 없습니다", style = MaterialTheme.typography.bodyMedium)
    } else {
        widgetIds.forEach { appWidgetId ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "위젯 #$appWidgetId", style = MaterialTheme.typography.bodyLarge)
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
