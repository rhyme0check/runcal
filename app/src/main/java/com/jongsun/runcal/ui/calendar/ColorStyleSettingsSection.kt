package com.jongsun.runcal.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.data.EventColorPaletteKey
import com.jongsun.runcal.data.resolveBackgroundColor
import com.jongsun.runcal.data.room.EventColorStyleEntity
import com.jongsun.runcal.data.sourceKeyForCalendar
import com.jongsun.runcal.data.sourceKeyForNotionDatabase
import kotlinx.coroutines.launch

data class ColorStyleSource(val key: String, val displayName: String, val rawColor: Int)

/**
 * 소스(캘린더/Notion DB)별 색상 스타일 설정 목록. 앱·위젯이 공유하는 Room 테이블을 그대로
 * 읽고 쓰므로([CalendarViewModel.eventColorStyles]/[CalendarViewModel.setEventColorStyle]),
 * 여기서 바꾼 값은 저장 즉시 위젯 6종에도 반영된다.
 */
@Composable
fun ColorStyleSettingsSection(viewModel: CalendarViewModel, modifier: Modifier = Modifier) {
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val notionDatabases by viewModel.notionDatabases.collectAsStateWithLifecycle()
    val styles by viewModel.eventColorStyles.collectAsStateWithLifecycle()
    val isDarkTheme = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    var editingSource by remember { mutableStateOf<ColorStyleSource?>(null) }

    val sources = remember(calendars, notionDatabases) {
        calendars.map { ColorStyleSource(sourceKeyForCalendar(it.id), it.displayName, it.color) } +
            notionDatabases.map { ColorStyleSource(sourceKeyForNotionDatabase(it.id), it.displayName, it.colorArgb) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = "색상 스타일", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "소스별로 일정 색상과 글자 굵기를 지정합니다. 앱과 위젯에 동일하게 적용됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sources.isEmpty()) {
            Text(
                text = "표시할 캘린더나 Notion DB가 없습니다",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            sources.forEach { source ->
                ColorStyleSourceRow(
                    source = source,
                    style = styles[source.key],
                    isDarkTheme = isDarkTheme,
                    onClick = { editingSource = source },
                )
            }
        }
    }

    editingSource?.let { source ->
        ColorStyleEditDialog(
            source = source,
            currentStyle = styles[source.key],
            isDarkTheme = isDarkTheme,
            onDismiss = { editingSource = null },
            onSave = { paletteKey, bold ->
                scope.launch {
                    viewModel.setEventColorStyle(source.key, paletteKey, bold)
                    editingSource = null
                }
            },
        )
    }
}

@Composable
private fun ColorStyleSourceRow(
    source: ColorStyleSource,
    style: EventColorStyleEntity?,
    isDarkTheme: Boolean,
    onClick: () -> Unit,
) {
    val backgroundArgb = remember(source, style, isDarkTheme) {
        resolveBackgroundColor(source.rawColor, style, isDarkTheme)
    }
    val paletteLabel = style?.paletteKey
        ?.let { key -> runCatching { EventColorPaletteKey.valueOf(key) }.getOrNull()?.displayName }
        ?: "시스템 기본"
    val subtitle = if (style?.bold == true) "$paletteLabel · 굵게" else paletteLabel

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(14.dp).background(Color(backgroundArgb), CircleShape))
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (style?.bold == true) FontWeight.Bold else FontWeight.Normal,
            )
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColorStyleEditDialog(
    source: ColorStyleSource,
    currentStyle: EventColorStyleEntity?,
    isDarkTheme: Boolean,
    onDismiss: () -> Unit,
    onSave: (paletteKey: String?, bold: Boolean) -> Unit,
) {
    var selectedPaletteKey by remember { mutableStateOf(currentStyle?.paletteKey) }
    var bold by remember { mutableStateOf(currentStyle?.bold ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(source.displayName) },
        text = {
            Column {
                Text(text = "색상", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { selectedPaletteKey = null },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ColorSwatch(
                        argb = resolveBackgroundColor(source.rawColor, null, isDarkTheme),
                        selected = selectedPaletteKey == null,
                        onClick = { selectedPaletteKey = null },
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = "시스템 기본", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(modifier = Modifier.height(10.dp))
                EventColorPaletteKey.entries.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        row.forEach { key ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                ColorSwatch(
                                    argb = key.argbFor(isDarkTheme),
                                    selected = selectedPaletteKey == key.name,
                                    onClick = { selectedPaletteKey = key.name },
                                )
                                Text(text = key.displayName, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "텍스트 굵기", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !bold, onClick = { bold = false }, label = { Text("보통") })
                    FilterChip(selected = bold, onClick = { bold = true }, label = { Text("굵게") })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selectedPaletteKey, bold) }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun ColorSwatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(Color(argb), CircleShape)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}
