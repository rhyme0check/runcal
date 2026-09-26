package com.jongsun.runcal.ui.special

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.ui.calendar.CalendarViewModel

/** 월간 화면의 음력·공휴일·절기 표시 on/off. 칸 공간을 쓰므로 기본은 전부 꺼져 있다. */
@Composable
fun SpecialDaySettingsSection(viewModel: CalendarViewModel, modifier: Modifier = Modifier) {
    val flags by viewModel.specialFlags.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = "음력 · 공휴일 · 절기", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "공공데이터포털(한국천문연구원) 정보를 받아 저장해두고 표시합니다. 처음 켜면 잠시 후 나타납니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        ToggleRow("음력 표시", "날짜 아래에 음력(예: 8.15)", flags.showLunar) { viewModel.setSpecialFlags(flags.copy(showLunar = it)) }
        ToggleRow("공휴일 표시", "빨간 날짜 + 이름 막대(대체공휴일 포함)", flags.showHolidays) { viewModel.setSpecialFlags(flags.copy(showHolidays = it)) }
        ToggleRow("절기 표시", "24절기 이름 막대", flags.showSolarTerms) { viewModel.setSpecialFlags(flags.copy(showSolarTerms = it)) }
        Text(
            text = "위젯: 공휴일/절기는 이 설정을 따르고, 음력은 4x5 확장 위젯의 위젯 설정에서 따로 켭니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
