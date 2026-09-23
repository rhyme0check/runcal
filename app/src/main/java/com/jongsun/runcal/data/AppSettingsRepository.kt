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

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

const val MIN_APP_FONT_SCALE_STEP = 1
const val MAX_APP_FONT_SCALE_STEP = 4
const val DEFAULT_APP_FONT_SCALE_STEP = 2
val DEFAULT_WEEK_START_DAY: DayOfWeek = DayOfWeek.SUNDAY

/**
 * 앱 화면 전용 표시 설정.
 * [visibleCalendarIds]가 null이면 전체 캘린더 표시(기본값)를 의미한다. 위젯별 설정과는 완전히 독립된 저장소를 쓴다.
 */
data class AppSettings(
    val visibleCalendarIds: Set<Long>? = null,
    val weekStartDay: DayOfWeek = DEFAULT_WEEK_START_DAY,
    val fontScaleStep: Int = DEFAULT_APP_FONT_SCALE_STEP,
)

class AppSettingsRepository(private val context: Context) {

    private object Keys {
        val VISIBLE_CALENDAR_IDS = stringSetPreferencesKey("visible_calendar_ids")
        val WEEK_START_DAY = stringPreferencesKey("week_start_day")
        val FONT_SCALE_STEP = intPreferencesKey("app_font_scale_step")
    }

    val settings: Flow<AppSettings> = context.appSettingsDataStore.data.map { prefs ->
        AppSettings(
            visibleCalendarIds = prefs[Keys.VISIBLE_CALENDAR_IDS]?.mapNotNull { it.toLongOrNull() }?.toSet(),
            weekStartDay = prefs[Keys.WEEK_START_DAY]
                ?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }
                ?: DEFAULT_WEEK_START_DAY,
            fontScaleStep = prefs[Keys.FONT_SCALE_STEP] ?: DEFAULT_APP_FONT_SCALE_STEP,
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
}
