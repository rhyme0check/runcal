package com.jongsun.runcal.ui.notion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jongsun.runcal.data.APP_PRESET_COLOR_PALETTE
import com.jongsun.runcal.data.notion.NotionApiException
import com.jongsun.runcal.data.notion.NotionDatabaseSchemaResponse
import com.jongsun.runcal.data.notion.NotionPropertySchema
import com.jongsun.runcal.data.notion.parseNotionDatabaseId
import com.jongsun.runcal.data.room.NotionDatabaseEntity
import com.jongsun.runcal.ui.calendar.CalendarViewModel
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SUBTITLE_ALLOWED_TYPES = setOf("rich_text", "select", "status", "number")
private val STATUS_ALLOWED_TYPES = setOf("status", "select")

private fun notionErrorMessage(e: NotionApiException): String =
    if (e.statusCode == 401 || e.statusCode == 403) {
        "Notion에서 이 DB를 RunCal 통합에 연결해야 합니다"
    } else {
        "조회 실패 (HTTP ${e.statusCode})"
    }

@Composable
fun NotionDbRegisterDialog(
    viewModel: CalendarViewModel,
    existing: NotionDatabaseEntity?,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            NotionDbRegisterContent(viewModel = viewModel, existing = existing, onDismiss = onDismiss)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotionDbRegisterContent(
    viewModel: CalendarViewModel,
    existing: NotionDatabaseEntity?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var urlOrId by remember { mutableStateOf(existing?.notionDatabaseId ?: "") }
    var resolvedDatabaseId by remember { mutableStateOf(existing?.notionDatabaseId) }
    var schema by remember { mutableStateOf<NotionDatabaseSchemaResponse?>(null) }
    var loading by remember { mutableStateOf(existing != null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    var displayName by remember { mutableStateOf(existing?.displayName ?: "") }
    var colorArgb by remember { mutableIntStateOf(existing?.colorArgb ?: APP_PRESET_COLOR_PALETTE.first()) }
    var dateProperty by remember { mutableStateOf(existing?.dateProperty ?: "") }
    var titleProperty by remember { mutableStateOf(existing?.titleProperty ?: "") }
    var subtitleProperty by remember { mutableStateOf(existing?.subtitleProperty) }
    var statusProperty by remember { mutableStateOf(existing?.statusProperty) }

    // 수정 화면은 들어오자마자 최신 스키마를 다시 받아온다 — 그새 Notion에서 속성이 바뀌었을 수 있어서다.
    LaunchedEffect(Unit) {
        if (existing != null) {
            try {
                schema = viewModel.fetchNotionSchema(existing.notionDatabaseId)
            } catch (e: NotionApiException) {
                errorMessage = notionErrorMessage(e)
            }
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = if (existing == null) "Notion DB 추가" else "Notion DB 수정", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "닫기") }
        }

        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            val currentSchema = schema
            if (currentSchema == null) {
                Text(text = "Notion DB URL 또는 ID", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = urlOrId,
                    onValueChange = { urlOrId = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    singleLine = true,
                    placeholder = { Text("https://notion.so/.../xxxxxxxx?v=... 또는 ID") },
                )
                Text(
                    text = "URL의 ?v= 파라미터(뷰 id)는 무시됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorMessage?.let { message ->
                    Text(text = message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    enabled = urlOrId.isNotBlank() && !loading,
                    onClick = {
                        scope.launch {
                            loading = true
                            errorMessage = null
                            val id = parseNotionDatabaseId(urlOrId)
                            if (id == null) {
                                errorMessage = "URL 또는 ID에서 DB id를 찾을 수 없습니다"
                                loading = false
                                return@launch
                            }
                            try {
                                val result = viewModel.fetchNotionSchema(id)
                                schema = result
                                resolvedDatabaseId = id
                                if (displayName.isBlank()) displayName = result.titleText
                                titleProperty = result.properties.values.firstOrNull { it.type == "title" }?.name ?: ""
                            } catch (e: NotionApiException) {
                                errorMessage = notionErrorMessage(e)
                            }
                            loading = false
                        }
                    },
                ) { Text("다음") }
                if (loading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator()
                }
            } else {
                val properties = currentSchema.properties.values.toList()
                val dateOptions = properties.filter { it.type == "date" }
                val subtitleOptions = properties.filter { it.type in SUBTITLE_ALLOWED_TYPES }
                val statusOptions = properties.filter { it.type in STATUS_ALLOWED_TYPES }

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("표시 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                Text(text = "색상", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
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
                Text(text = "제목 (title 속성, 자동 지정)", style = MaterialTheme.typography.labelMedium)
                Text(text = titleProperty.ifBlank { "(없음 — 이 DB에 title 속성이 없습니다)" }, style = MaterialTheme.typography.bodyLarge)

                Spacer(modifier = Modifier.height(12.dp))
                PropertyDropdown(
                    label = "날짜 (필수)",
                    options = dateOptions,
                    selected = dateProperty.ifBlank { null },
                    allowNone = false,
                    onSelect = { dateProperty = it.orEmpty() },
                )

                Spacer(modifier = Modifier.height(12.dp))
                PropertyDropdown(
                    label = "부제 (선택)",
                    options = subtitleOptions,
                    selected = subtitleProperty,
                    allowNone = true,
                    onSelect = { subtitleProperty = it },
                )

                Spacer(modifier = Modifier.height(12.dp))
                PropertyDropdown(
                    label = "상태 (선택)",
                    options = statusOptions,
                    selected = statusProperty,
                    allowNone = true,
                    onSelect = { statusProperty = it },
                )

                if (dateOptions.isEmpty()) {
                    Text(
                        text = "이 DB에 date 타입 속성이 없어 등록할 수 없습니다",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                resultMessage?.let { message ->
                    Text(text = message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss) { Text("취소") }
                    Button(
                        enabled = !saving && displayName.isNotBlank() && dateProperty.isNotBlank() && titleProperty.isNotBlank(),
                        onClick = {
                            scope.launch {
                                saving = true
                                resultMessage = "저장 및 동기화 중..."
                                val entity = NotionDatabaseEntity(
                                    id = existing?.id ?: UUID.randomUUID().toString(),
                                    notionDatabaseId = resolvedDatabaseId ?: existing?.notionDatabaseId ?: return@launch,
                                    displayName = displayName.trim(),
                                    colorArgb = colorArgb,
                                    iconEmojiOrUrl = existing?.iconEmojiOrUrl,
                                    dateProperty = dateProperty,
                                    titleProperty = titleProperty,
                                    subtitleProperty = subtitleProperty,
                                    statusProperty = statusProperty,
                                    schemaJson = "",
                                    lastSchemaCheckedAtMillis = System.currentTimeMillis(),
                                    lastSyncedAtMillis = existing?.lastSyncedAtMillis ?: 0L,
                                    lastSyncStatus = "PENDING",
                                    lastSyncError = null,
                                    createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis(),
                                )
                                val result = viewModel.registerNotionDatabase(entity)
                                saving = false
                                resultMessage = when (result.status) {
                                    "OK" -> "저장 및 동기화 완료: ${result.eventCount}건"
                                    "SCHEMA_INVALID" -> "저장했지만 매핑 오류: ${result.error}"
                                    else -> "저장했지만 동기화 실패: ${result.error}"
                                }
                                if (result.status == "OK") {
                                    delay(700)
                                    onDismiss()
                                }
                            }
                        },
                    ) { Text("저장") }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropertyDropdown(
    label: String,
    options: List<NotionPropertySchema>,
    selected: String?,
    allowNone: Boolean,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected ?: if (allowNone) "없음" else "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) {
                DropdownMenuItem(text = { Text("없음") }, onClick = { onSelect(null); expanded = false })
            }
            options.forEach { prop ->
                DropdownMenuItem(text = { Text("${prop.name} (${prop.type})") }, onClick = { onSelect(prop.name); expanded = false })
            }
        }
    }
}
