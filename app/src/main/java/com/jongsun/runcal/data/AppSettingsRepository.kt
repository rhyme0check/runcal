package com.jongsun.runcal.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.DayOfWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

const val MIN_APP_FONT_SCALE_STEP = 1
const val MAX_APP_FONT_SCALE_STEP = 4
const val DEFAULT_APP_FONT_SCALE_STEP = 2
val DEFAULT_WEEK_START_DAY: DayOfWeek = DayOfWeek.SUNDAY

/** Notion 동기화 주기로 고를 수 있는 값들(시간 단위). 선택 UI는 3단계에서 붙인다. */
val NOTION_SYNC_INTERVAL_HOUR_OPTIONS = listOf(1, 3, 6, 12)
const val DEFAULT_NOTION_SYNC_INTERVAL_HOURS = 3

/**
 * 앱 화면 전용 표시 설정.
 * [visibleCalendarIds]가 null이면 전체 캘린더 표시(기본값)를 의미한다. 위젯별 설정과는 완전히 독립된 저장소를 쓴다.
 */
data class AppSettings(
    val visibleCalendarIds: Set<Long>? = null,
    val weekStartDay: DayOfWeek = DEFAULT_WEEK_START_DAY,
    val fontScaleStep: Int = DEFAULT_APP_FONT_SCALE_STEP,
    val presets: List<AppPreset> = listOf(DEFAULT_APP_PRESET),
    val activePresetId: String = DEFAULT_APP_PRESET_ID,
    val notionSyncIntervalHours: Int = DEFAULT_NOTION_SYNC_INTERVAL_HOURS,
)

class AppSettingsRepository(private val context: Context) {

    private object Keys {
        val VISIBLE_CALENDAR_IDS = stringSetPreferencesKey("visible_calendar_ids")
        val WEEK_START_DAY = stringPreferencesKey("week_start_day")
        val FONT_SCALE_STEP = intPreferencesKey("app_font_scale_step")
        val PRESETS = stringPreferencesKey("app_presets")
        val ACTIVE_PRESET_ID = stringPreferencesKey("active_app_preset_id")
        val NOTION_SYNC_INTERVAL_HOURS = intPreferencesKey("notion_sync_interval_hours")
    }

    val settings: Flow<AppSettings> = context.appSettingsDataStore.data.map { prefs ->
        AppSettings(
            visibleCalendarIds = prefs[Keys.VISIBLE_CALENDAR_IDS]?.mapNotNull { it.toLongOrNull() }?.toSet(),
            weekStartDay = prefs[Keys.WEEK_START_DAY]
                ?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }
                ?: DEFAULT_WEEK_START_DAY,
            fontScaleStep = prefs[Keys.FONT_SCALE_STEP] ?: DEFAULT_APP_FONT_SCALE_STEP,
            presets = prefs[Keys.PRESETS]?.let { json ->
                runCatching { Json.decodeFromString(appPresetListSerializer, json) }.getOrNull()
            }?.takeIf { it.isNotEmpty() } ?: listOf(DEFAULT_APP_PRESET),
            activePresetId = prefs[Keys.ACTIVE_PRESET_ID] ?: DEFAULT_APP_PRESET_ID,
            notionSyncIntervalHours = prefs[Keys.NOTION_SYNC_INTERVAL_HOURS] ?: DEFAULT_NOTION_SYNC_INTERVAL_HOURS,
        )
    }

    suspend fun setVisibleCalendarIds(ids: Set<Long>?) {
        context.appSettingsDataStore.edit { prefs ->
            if (ids == null) {
                prefs.remove(Keys.VISIBLE_CALENDAR_IDS)
            } else {
                prefs[Keys.VISIBLE_CALENDAR_IDS] = ids.map { it.toString() }.toSet()
            }
        }
    }

    suspend fun setWeekStartDay(day: DayOfWeek) {
        context.appSettingsDataStore.edit { prefs -> prefs[Keys.WEEK_START_DAY] = day.name }
    }

    suspend fun setFontScaleStep(step: Int) {
        context.appSettingsDataStore.edit { prefs -> prefs[Keys.FONT_SCALE_STEP] = step }
    }

    suspend fun setPresets(presets: List<AppPreset>) {
        context.appSettingsDataStore.edit { prefs ->
            prefs[Keys.PRESETS] = Json.encodeToString(appPresetListSerializer, presets)
        }
    }

    suspend fun setActivePresetId(id: String) {
        context.appSettingsDataStore.edit { prefs -> prefs[Keys.ACTIVE_PRESET_ID] = id }
    }

    /** 저장만 한다 — 실제로 WorkManager 주기를 바꾸는 건 호출부(3단계 설정 UI)의 몫이다. */
    suspend fun setNotionSyncIntervalHours(hours: Int) {
        context.appSettingsDataStore.edit { prefs -> prefs[Keys.NOTION_SYNC_INTERVAL_HOURS] = hours }
    }
}
