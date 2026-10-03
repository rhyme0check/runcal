package com.jongsun.runcal.ui.calendar

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.data.UsageLog
import com.jongsun.runcal.data.distinctName
import com.jongsun.runcal.data.room.AppErrorEntity
import com.jongsun.runcal.data.room.RunCalDatabase
import com.jongsun.runcal.data.room.UsageTotal
import com.jongsun.runcal.work.NotionCopyWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * Notion 항목을 폰 캘린더(삼성 My calendar 권장)에 매월 15일·말일 복사해 두는 설정. RunCal을 그만 써도 일정이 폰에 남게 한다.
 */
@Composable
fun NotionCopySection(viewModel: CalendarViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allCalendars by viewModel.allCalendars.collectAsStateWithLifecycle()
    val notionDatabases by viewModel.notionDatabases.collectAsStateWithLifecycle()
    val copyCalendarId by viewModel.copyCalendarId.collectAsStateWithLifecycle()
    val writable = remember(allCalendars) { allCalendars.filter { it.isWritable } }
    // 삼성 기기 전용 캘린더("My calendar")를 먼저 보여 준다.
    val ordered = remember(writable) { writable.sortedByDescending { it.accountName.equals("My calendar", ignoreCase = true) || it.displayName.equals("My calendar", ignoreCase = true) } }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val lastDay = remember(refresh) { NotionCopyWorker.lastCopyDay(context) }
    val lastResult = remember(refresh) { NotionCopyWorker.lastResult(context) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("폰 캘린더로 복사(백업)", style = MaterialTheme.typography.titleMedium)
        Text(
            "매월 15일과 말일에 Notion 항목을 고른 폰 캘린더에 복사합니다. RunCal을 그만 써도 일정이 폰에 남습니다. " +
                "복사본은 메모에 [RunCal 복사] 표시가 붙고 알림은 넣지 않으며, RunCal 화면에서는 숨깁니다. Notion에서 지운 항목은 복사본도 지웁니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (notionDatabases.isEmpty()) {
            Text("등록된 Notion DB가 없어 복사할 것이 없습니다.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        Text("복사할 캘린더", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = copyCalendarId == null, onClick = { scope.launch { viewModel.setCopyCalendarId(null) } }, label = { Text("끔") })
            ordered.forEach { cal ->
                FilterChip(
                    selected = copyCalendarId == cal.id,
                    onClick = { scope.launch { viewModel.setCopyCalendarId(cal.id) } },
                    label = { Text(cal.distinctName(allCalendars), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        Text(
            lastDay?.let { "마지막 복사: $it · ${lastResult.orEmpty()}" } ?: "아직 복사하지 않았습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = copyCalendarId != null && !working,
                onClick = {
                    working = true
                    scope.launch {
                        message = viewModel.copyNotionToCalendarNow()
                        working = false
                        refresh++
                    }
                },
            ) { Text(if (working) "복사 중…" else "지금 복사") }
            // 복사 캘린더를 바꾸기 전이나 그만둘 때. [RunCal 복사] 표시가 있는 복사본만 지운다.
            OutlinedButton(
                enabled = !working,
                onClick = {
                    working = true
                    scope.launch {
                        message = viewModel.clearNotionCopies()
                        working = false
                    }
                },
            ) { Text("복사본 모두 지우기") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

/** 사용 기록·오류 기록(앱 안에만 저장). 요약을 보고 텍스트로 내보낸다(카카오톡 나에게 보내기 등). */
@Composable
fun UsageLogSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var totals by remember { mutableStateOf<List<UsageTotal>>(emptyList()) }
    var errors by remember { mutableStateOf<List<AppErrorEntity>>(emptyList()) }
    var errorCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        val dao = RunCalDatabase.getInstance(context).usageDao()
        totals = dao.totalsSince(LocalDate.now().minusDays(30).toString())
        errors = dao.recentErrors(3)
        errorCount = dao.errorCountSince(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
    }
    val zone = remember { ZoneId.systemDefault() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("사용 기록", style = MaterialTheme.typography.titleMedium)
        Text(
            "기능별 사용 횟수와 오류만 이 폰에 남깁니다(일정 제목·내용 없음, 자동 전송 없음, 90일 보관). 내보내서 보내 주시면 다음 개발에 반영합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("최근 30일 많이 쓴 기능", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        if (totals.isEmpty()) Text("아직 기록이 없습니다.", style = MaterialTheme.typography.bodySmall)
        totals.take(8).forEach { Text("${usageLabel(it.name)} · ${it.count}회", style = MaterialTheme.typography.bodySmall) }
        Text("최근 30일 오류 ${errorCount}건", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        errors.forEach { e ->
            Text(
                "${Instant.ofEpochMilli(e.atMillis).atZone(zone).toLocalDate()} ${e.kind}: ${e.message}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val text = UsageLog.exportText(context)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "RunCal 사용 기록")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, "사용 기록 내보내기"))
                }
            }) { Text("내보내기") }
            OutlinedButton(onClick = { scope.launch { UsageLog.clear(context); refresh++ } }) { Text("지우기") }
        }
    }
}

/** 기록 이름 → 사람이 읽는 이름. 모르는 이름은 그대로. */
private fun usageLabel(name: String): String = when {
    name == "event_create" -> "일정 추가"
    name == "event_update" -> "일정 수정"
    name == "event_delete" -> "일정 삭제"
    name == "preset_apply" -> "프리셋 바꾸기"
    name == "notion_write" -> "Notion 수정"
    name == "notion_sync_manual" -> "Notion 지금 동기화"
    name.startsWith("notion_copy") -> "폰 캘린더로 복사"
    name == "ai_command" -> "AI 명령"
    name == "ai_execute" -> "AI 실행"
    name.startsWith("share_") -> "공유 받기"
    name == "widget_open_app" -> "위젯에서 앱 열기"
    name.startsWith("widget_") -> "위젯 " + name.removePrefix("widget_")
    name.startsWith("tab_") -> "화면 " + when (name.removePrefix("tab_")) {
        "monthly" -> "월간"
        "daily" -> "일간"
        "list" -> "목록"
        "settings" -> "설정"
        else -> name.removePrefix("tab_")
    }
    else -> name
}
