package com.jongsun.runcal.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.YearMonth

/** "연월 선택" 날짜 점프 다이얼로그. 연도 스테퍼 + 12개월 그리드로 원하는 달을 바로 고른다. */
@Composable
fun MonthYearPickerDialog(
    initialYearMonth: YearMonth,
    onDismiss: () -> Unit,
    onConfirm: (YearMonth) -> Unit,
) {
    var year by remember { mutableIntStateOf(initialYearMonth.year) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("날짜 이동") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { year-- }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "이전 연도")
                    }
                    Text(text = "${year}년", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { year++ }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "다음 연도")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                for (row in 0 until 4) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (col in 0 until 3) {
                            val month = row * 3 + col + 1
                            val isSelected = year == initialYearMonth.year && month == initialYearMonth.monthValue
                            TextButton(
                                onClick = { onConfirm(YearMonth.of(year, month)) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = "${month}월",
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        },
    )
}
