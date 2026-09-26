package com.jongsun.runcal.ai

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ChangeEntry(val atMillis: Long, val kind: String, val summary: String)

/**
 * AI 명령으로 실행된(또는 취소·되돌려진) 변경 기록. 앱 전용 저장소(filesDir/assistant/)에만 쓰고,
 * 자체 백업(BackupPayload)에 들어가지 않으며 OS 자동 백업 규칙에서도 제외된다.
 * 일정 제목이 들어 있으므로 Google로는 전송하지 않는다(로컬 전용).
 */
object ChangeLog {
    private const val MAX_KEEP = 200
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(context: Context): File = File(context.filesDir, "assistant/changelog.jsonl").also { it.parentFile?.mkdirs() }

    @Synchronized
    fun append(context: Context, kind: String, summary: String) {
        val f = file(context)
        val lines = if (f.exists()) f.readLines().takeLast(MAX_KEEP - 1) else emptyList()
        val entry = json.encodeToString(ChangeEntry(System.currentTimeMillis(), kind, summary))
        f.writeText((lines + entry).joinToString("\n") + "\n")
    }

    @Synchronized
    fun recent(context: Context, limit: Int = 50): List<ChangeEntry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { runCatching { json.decodeFromString<ChangeEntry>(it) }.getOrNull() }.takeLast(limit).reversed()
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }
}
