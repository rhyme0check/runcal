package com.jongsun.runcal.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jongsun.runcal.ai.AI_MODEL_OPTIONS
import com.jongsun.runcal.ai.AiPrefs
import com.jongsun.runcal.ai.aiKeyPresent

/** AI 기능 on/off(기본 꺼짐)·모델 선택·개인정보 안내. 키가 없으면 켤 수 없고 ✨ 아이콘도 나오지 않는다. */
@Composable
fun AiSettingsSection(assistant: AssistantViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings by AiPrefs.state(context).collectAsStateWithLifecycle()
    val keyPresent = aiKeyPresent()

    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = "AI 자연어 명령", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "무료 등급 사용 중 — 입력한 명령 문장은 Google로 전송됩니다",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "일정 제목·장소·메모·캘린더 이름은 보내지 않고 익명 라벨(E1, C1…)로 바꿔 보냅니다. 무료 등급에서 전송된 내용은 Google 제품 개선에 쓰일 수 있으니 명령 문장에 민감한 정보를 넣지 마세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "AI 기능 사용", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (keyPresent) "켜면 월간/일간 화면 상단에 ✨ 아이콘이 나타납니다" else "API 키가 없습니다(local.properties의 GEMINI_API_KEY)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.enabled && keyPresent,
                enabled = keyPresent,
                onCheckedChange = {
                    AiPrefs.setEnabled(context, it)
                    if (!it) assistant.newConversation()
                },
            )
        }
        if (settings.enabled && keyPresent) {
            Text(text = "모델", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
            AI_MODEL_OPTIONS.forEach { (model, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.model == model, onClick = { AiPrefs.setModel(context, model) })
                    Column {
                        Text(text = model, style = MaterialTheme.typography.bodyMedium)
                        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            OutlinedButton(onClick = { assistant.newConversation() }, modifier = Modifier.padding(top = 8.dp)) { Text("대화 기록 삭제") }
        }
    }
}
