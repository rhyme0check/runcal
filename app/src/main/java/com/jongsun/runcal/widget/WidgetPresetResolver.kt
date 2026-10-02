package com.jongsun.runcal.widget

import android.content.Context
import android.util.Log
import com.jongsun.runcal.data.AppPreset
import com.jongsun.runcal.data.AppSettingsRepository
import com.jongsun.runcal.data.SharedPresets
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "RunCal"
private val migrateLock = Mutex()

fun AppPreset.toWidgetPreset(): WidgetPreset = WidgetPreset(id, name, colorArgb, calendarIds, notionDatabaseIds)

/**
 * 이 위젯이 지금 보여줄 프리셋. 연동이 켜져 있고 위젯을 고정하지 않았으면 앱의 활성 프리셋, 아니면 위젯이 고른 프리셋.
 * 렌더링 경로에서 호출되므로 메모리 스냅샷([SharedPresets])만 읽는다(최초 1회 제외).
 */
suspend fun resolveWidgetPreset(context: Context, appWidgetId: Int, settings: WidgetFilterSettings): WidgetPreset {
    val migrated = migrateLegacyPresetsIfNeeded(context, appWidgetId, settings)
    val shared = SharedPresets.snapshot(context)
    val linked = shared.linkEnabled && !migrated.presetPinned
    return shared.resolve(if (linked) shared.activePresetId else migrated.presetId).toWidgetPreset()
}

/** 위젯에서 프리셋을 골랐을 때. 연동 중이면 앱의 활성 프리셋을 바꾸고(→ 연동된 위젯 전부 갱신), 아니면 이 위젯만 바꾼다. */
suspend fun selectWidgetPreset(context: Context, appWidgetId: Int, presetId: String) {
    val shared = SharedPresets.snapshot(context)
    val settings = loadWidgetFilterSettings(context, appWidgetId)
    if (shared.linkEnabled && !settings.presetPinned) {
        AppSettingsRepository(context).applyPreset(shared.resolve(presetId))
        RunCalWidgetRenderer.updateAllWidgets(context)
    } else {
        applyWidgetState(context, appWidgetId) { it.copy(presetId = presetId) }
    }
}

/**
 * 구버전의 위젯별 프리셋을 앱 프리셋 목록에 한 번 합친다. 캘린더·Notion 구성이 같은 앱 프리셋이 있으면 그것을 쓰고,
 * 없으면 새로 추가한다. 위젯이 보던 프리셋을 [WidgetFilterSettings.presetId]로 옮기고 위젯별 목록은 비운다.
 */
private suspend fun migrateLegacyPresetsIfNeeded(context: Context, appWidgetId: Int, settings: WidgetFilterSettings): WidgetFilterSettings {
    if (settings.legacyPresetsMigrated) return settings
    if (settings.presets.isEmpty()) {
        return settings.copy(legacyPresetsMigrated = true).also { persistWidgetFilterSettings(context, appWidgetId, it) }
    }
    return migrateLock.withLock {
        val latest = loadWidgetFilterSettings(context, appWidgetId)
        if (latest.legacyPresetsMigrated) return@withLock latest
        val repo = AppSettingsRepository(context)
        val list = repo.settings.first().presets.toMutableList()
        val idMap = HashMap<String, String>()
        latest.presets.forEach { wp ->
            val wpNotion = wp.notionDatabaseIds.orEmpty()
            val match = list.firstOrNull { it.calendarIds == wp.calendarIds && it.notionDatabaseIds.orEmpty() == wpNotion }
            if (match != null) {
                idMap[wp.id] = match.id
            } else {
                val newId = if (list.any { it.id == wp.id }) UUID.randomUUID().toString() else wp.id
                val name = if (list.any { it.name == wp.name }) "${wp.name} (위젯)" else wp.name
                list += AppPreset(newId, name, wp.colorArgb, wp.calendarIds, wp.notionDatabaseIds?.takeIf { it.isNotEmpty() })
                idMap[wp.id] = newId
            }
        }
        repo.setPresets(list)
        val chosen = latest.presets.getOrNull(latest.currentPresetIndex.coerceIn(latest.presets.indices))?.let { idMap[it.id] }
        val updated = latest.copy(presets = emptyList(), currentPresetIndex = 0, presetId = chosen, presetPinned = false, legacyPresetsMigrated = true)
        persistWidgetFilterSettings(context, appWidgetId, updated)
        Log.d(TAG, "migrateLegacyPresets: appWidgetId=$appWidgetId merged=${latest.presets.size} -> appPresets=${list.size}")
        updated
    }
}
