package com.jongsun.runcal.data.notion

import android.content.Context
import com.jongsun.runcal.BuildConfig
import com.jongsun.runcal.data.room.NotionDatabaseEntity
import com.jongsun.runcal.data.room.RunCalDatabase
import com.jongsun.runcal.work.WorkScheduler
import java.util.UUID

/** local.properties의 NOTION_SEED_DBS 한 항목. 형식: 이름|DB id|색(ARGB hex)|날짜|제목|부제|상태 (빈칸=없음), 항목끼리는 ';'. */
data class NotionSeedEntry(
    val displayName: String,
    val notionDatabaseId: String,
    val colorArgb: Int,
    val dateProperty: String,
    val titleProperty: String,
    val subtitleProperty: String?,
    val statusProperty: String?,
)

fun parseNotionSeeds(raw: String): List<NotionSeedEntry> =
    raw.split(';').mapNotNull { line ->
        val f = line.split('|').map { it.trim() }
        if (f.size < 5) return@mapNotNull null
        val dbId = parseNotionDatabaseId(f[1]) ?: return@mapNotNull null
        val color = f[2].toLongOrNull(16)?.toInt() ?: 0xFF1A73E8.toInt()
        if (f[0].isEmpty() || f[3].isEmpty() || f[4].isEmpty()) return@mapNotNull null
        NotionSeedEntry(
            displayName = f[0],
            notionDatabaseId = dbId,
            colorArgb = color,
            dateProperty = f[3],
            titleProperty = f[4],
            subtitleProperty = f.getOrNull(5)?.takeIf { it.isNotEmpty() },
            statusProperty = f.getOrNull(6)?.takeIf { it.isNotEmpty() },
        )
    }

/**
 * 빌드에 들어 있는 Notion DB를 처음 실행 때 한 번만 등록한다. 한 번 넣은 DB는 기록해 두어,
 * 사용자가 나중에 지워도 다시 생기지 않는다. 이미 같은 DB가 등록돼 있으면 건드리지 않는다.
 */
object NotionSeed {
    private const val PREFS = "notion_seed"
    private const val KEY_SEEDED = "seeded_db_ids"

    suspend fun applyIfNeeded(context: Context) {
        val seeds = parseNotionSeeds(BuildConfig.NOTION_SEED_DBS)
        if (seeds.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seeded = prefs.getStringSet(KEY_SEEDED, emptySet()).orEmpty()
        val dao = RunCalDatabase.getInstance(context).notionDatabaseDao()
        val existing = dao.getAll().map { it.notionDatabaseId }.toSet()
        val now = System.currentTimeMillis()
        var added = false
        for (seed in seeds) {
            if (seed.notionDatabaseId in seeded || seed.notionDatabaseId in existing) continue
            dao.upsert(
                NotionDatabaseEntity(
                    id = UUID.randomUUID().toString(),
                    notionDatabaseId = seed.notionDatabaseId,
                    displayName = seed.displayName,
                    colorArgb = seed.colorArgb,
                    iconEmojiOrUrl = null,
                    dateProperty = seed.dateProperty,
                    titleProperty = seed.titleProperty,
                    subtitleProperty = seed.subtitleProperty,
                    statusProperty = seed.statusProperty,
                    schemaJson = "",
                    lastSchemaCheckedAtMillis = now,
                    lastSyncedAtMillis = 0L,
                    lastSyncStatus = "PENDING",
                    lastSyncError = null,
                    createdAtMillis = now,
                ),
            )
            added = true
        }
        prefs.edit().putStringSet(KEY_SEEDED, seeded + seeds.map { it.notionDatabaseId }).apply()
        if (added) WorkScheduler.triggerManualSync(context)
    }
}
