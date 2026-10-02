package com.jongsun.runcal.data

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 앱·위젯이 함께 쓰는 프리셋 목록과 앱의 활성 프리셋. */
data class PresetSnapshot(
    val presets: List<AppPreset>,
    val activePresetId: String,
    val linkEnabled: Boolean,
) {
    fun byId(id: String?): AppPreset? = id?.let { wanted -> presets.firstOrNull { it.id == wanted } }

    /** 없는 id(삭제된 프리셋)는 앱의 활성 프리셋 → 첫 프리셋 → "전체" 순으로 대체한다. */
    fun resolve(id: String?): AppPreset = byId(id) ?: byId(activePresetId) ?: presets.firstOrNull() ?: DEFAULT_APP_PRESET
}

/**
 * 프리셋 정의의 단일 저장소는 앱 설정(DataStore)이다. 위젯 렌더링 경로는 매번 DataStore를 읽지 않도록 이 객체의
 * 메모리 스냅샷만 읽는다(값이 바뀌는 쓰기 지점에서만 [invalidate]). 처음 한 번만 DataStore를 읽는다.
 */
object SharedPresets {
    @Volatile private var cached: PresetSnapshot? = null
    private val loadLock = Mutex()

    suspend fun snapshot(context: Context): PresetSnapshot {
        cached?.let { return it }
        return loadLock.withLock {
            cached ?: AppSettingsRepository(context.applicationContext).settings.first().let { s ->
                PresetSnapshot(s.presets, s.activePresetId, s.presetLinkEnabled)
            }.also { cached = it }
        }
    }

    fun invalidate() {
        cached = null
    }
}
