package com.jongsun.runcal.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.dateRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 캘린더 색상 점 + 시간 + 제목 + 장소로 구성된 일정 한 줄. 월간 아젠다와 일간 화면에서 공용으로 쓴다. */
@Composable
fun EventRow(event: EventItem, referenceDate: LocalDate, modifier: Modifier = Modifier) {
    val zone = remember { ZoneId.systemDefault() }
    val timeLabel = remember(event, referenceDate) { eventTimeLabel(event, referenceDate, zone) }

    Row(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(10.dp)
                .background(Color(event.color), CircleShape),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = event.title.ifBlank { "(제목 없음)" },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (event.location.isNotBlank()) {
                Text(
                    text = event.location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun eventTimeLabel(event: EventItem, referenceDate: LocalDate, zone: ZoneId): String {
    if (event.allDay) return "종일"
    val range = event.dateRange(zone)
    val startTime = Instant.ofEpochMilli(event.begin).atZone(zone).toLocalTime()
    val endTime = Instant.ofEpochMilli(event.end).atZone(zone).toLocalTime()
    return if (range.start != range.endInclusive) {
        when (referenceDate) {
            range.start -> "${startTime.toTimeLabel()} 시작"
            range.endInclusive -> "${endTime.toTimeLabel()} 종료"
            else -> "종일"
        }
    } else {
        "${startTime.toTimeLabel()} - ${endTime.toTimeLabel()}"
    }
}
