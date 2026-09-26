package com.jongsun.runcal.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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

/** 새 일정을 만들 때 기본으로 깔아줄 알림 오프셋(분). null이면 알림 없이 시작. */
const val DEFAULT_REMINDER_MINUTES = 10

/**
 * 앱 화면 전용 표시 설정.
 * [visibleCalendarIds]가 null이면 전체 캘린더 표시(기본값)를 의미한다. 위젯별 설정과는 완전히 독립된 저장소를 쓴다.
 */
data class AppSettings(
    val visibleCalendarIds: Set<Long>? = null,
    // null=전체, 빈 집합(기본값)=없음 — calendarIds와 의도적으로 비대칭. 프리셋을 통해서만
    // 채워지므로 기본은 opt-in.
    val visibleNotionDatabaseIds: Set<String>? = emptySet(),
    val weekStartDay: DayOfWeek = DEFAULT_WEEK_START_DAY,
    val fontScaleStep: Int = DEFAULT_APP_FONT_SCALE_STEP,
    val presets: List<AppPreset> = listOf(DEFAULT_APP_PRESET),
    val activePresetId: String = DEFAULT_APP_PRESET_ID,
    val notionSyncIntervalHours: Int = DEFAULT_NOTION_SYNC_INTERVAL_HOURS,
    // null = Drive 미연결. 액세스 토큰 자체는 여기 저장하지 않는다(Play services가 캐시) —
    // 표시용 계정 라벨과, 재인증이 필요할 때 보여줄 마지막 오류만 들고 있는다.
    val driveAccountEmail: String? = null,
    val driveLastError: String? = null,
    // null = 성공한 Drive 백업이 한 번도 없음. Testing 상태 OAuth 동의 화면은 리프레시 토큰이
    // 7일 뒤 만료되므로, 월간(30일) 백업이 조용히 계속 건너뛰어져도 사용자가 알아챌 수 있게
    // 마지막 성공 시각을 별도로 남긴다(driveLastError만으로는 "성공한 적이 언제인지" 알 수 없음).
    val driveLastSuccessAtMillis: Long? = null,
    // 알림 발송 전체 on/off. 꺼져 있으면 ReminderScheduler가 재동기화 자체를 건너뛴다(이미 걸린
    // 알람도 정리한다) — 권한은 있어도 사용자가 끄고 싶을 수 있어 별도 스위치로 둔다.
    val remindersEnabled: Boolean = true,
    // 새 일정 생성 시 기본으로 추가할 알림 오프셋(분). null = 알림 없이 시작.
    val defaultReminderMinutes: Int? = DEFAULT_REMINDER_MINUTES,
)

class AppSettingsRepository(private val context: Context) {

    private object Keys {
        val VISIBLE_CALENDAR_IDS = stringSetPreferencesKey("visible_calendar_ids")
        val WEEK_START_DAY = stringPreferencesKey("week_start_day")
        val FONT_SCALE_STEP = intPreferencesKey("app_font_scale_step")
        val PRESETS = stringPreferencesKey("app_presets")
        val ACTIVE_PRESET_ID = stringPreferencesKey("active_app_preset_id")
        val NOTION_SYNC_INTERVAL_HOURS = intPreferencesKey("notion_sync_interval_hours")
        val VISIBLE_NOTION_DATABASE_IDS = stringSetPreferencesKey("visible_notion_database_ids")
        val DRIVE_ACCOUNT_EMAIL = stringPreferencesKey("drive_account_email")
        val DRIVE_LAST_ERROR = stringPreferencesKey("drive_last_error")
        val DRIVE_LAST_SUCCESS_AT_MILLIS = longPreferencesKey("drive_last_success_at_millis")
        val REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        val DEFAULT_REMINDER_MINUTES = intPreferencesKey("default_reminder_minutes")
        // DEFAULT_REMINDER_MINUTES가 "알림 없음"(null)인지 "저장된 적 없음"(기본값 적용)인지
        // 구분하기 위한 별도 플래그 — IntPreferencesKey는 null을 직접 저장할 수 없다.
        val DEFAULT_REMINDER_NONE = booleanPreferencesKey("default_reminder_none")
    }

    val settings: Flow<AppSettings> = context.appSettingsDataStore.data.map { prefs ->
        AppSettings(
            visibleCalendarIds = prefs[Keys.VISIBLE_CALENDAR_IDS]?.mapNotNull { it.toLongOrNull() }?.toSet(),
            visibleNotionDatabaseIds = prefs[Keys.VISIBLE_NOTION_DATABASE_IDS] ?: emptySet(),
            weekStartDay = prefs[Keys.WEEK_START_DAY]
                ?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }
                ?: DEFAULT_WEEK_START_DAY,
            fontScaleStep = prefs[Keys.FONT_SCALE_STEP] ?: DEFAULT_APP_FONT_SCALE_STEP,
            presets = prefs[Keys.PRESETS]?.let { json ->
                runCatching { Json.decodeFromString(appPresetListSerializer, json) }.getOrNull()
            }?.takeIf { it.isNotEmpty() } ?: listOf(DEFAULT_APP_PRESET),
            activePresetId = prefs[Keys.ACTIVE_PRESET_ID] ?: DEFAULT_APP_PRESET_ID,
            notionSyncIntervalHours = prefs[Keys.NOTION_SYNC_INTERVAL_HOURS] ?: DEFAULT_NOTION_SYNC_INTERVAL_HOURS,
            driveAccountEmail = prefs[Keys.DRIVE_ACCOUNT_EMAIL],
            driveLastError = prefs[Keys.DRIVE_LAST_ERROR],
            driveLastSuccessAtMillis = prefs[Keys.DRIVE_LAST_SUCCESS_AT_MILLIS],
            remindersEnabled = prefs[Keys.REMINDERS_ENABLED] ?: true,
            defaultReminderMinutes = if (prefs[Keys.DEFAULT_REMINDER_NONE] == true) {
                null
            } else {
                prefs[Keys.DEFAULT_REMINDER_MINUTES] ?: DEFAULT_REMINDER_MINUTES
            },
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

    suspend fun setVisibleNotionDatabaseIds(ids: Set<String>) {
        context.appSettingsDataStore.edit { prefs -> prefs[Keys.VISIBLE_NOTION_DATABASE_IDS] = ids }
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

    suspend fun setDriveAccountEmail(email: String?) {
        context.appSettingsDataStore.edit { prefs ->
            if (email == null) prefs.remove(Keys.DRIVE_ACCOUNT_EMAIL) else prefs[Keys.DRIVE_ACCOUNT_EMAIL] = email
        }
    }

    suspend fun setDriveLastError(message: String?) {
        context.appSettingsDataStore.edit { prefs ->
            if (message == null) prefs.remove(Keys.DRIVE_LAST_ERROR) else prefs[Keys.DRIVE_LAST_ERROR] = message
        }
    }

    suspend fun setDriveLastSuccessAtMillis(atMillis: Long) {
        context.appSettingsDataStore.edit { prefs -> prefs[Keys.DRIVE_LAST_SUCCESS_AT_MILLIS] = atMillis }
    }

    suspend fun setRemindersEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { prefs -> prefs[Keys.REMINDERS_ENABLED] = enabled }
    }

    /** null = "알림 없음"을 기본값으로 저장(0분 = 정시 알림과는 다름). */
    suspend fun setDefaultReminderMinutes(minutes: Int?) {
        context.appSettingsDataStore.edit { prefs ->
            if (minutes == null) {
                prefs[Keys.DEFAULT_REMINDER_NONE] = true
                prefs.remove(Keys.DEFAULT_REMINDER_MINUTES)
            } else {
                prefs[Keys.DEFAULT_REMINDER_NONE] = false
                prefs[Keys.DEFAULT_REMINDER_MINUTES] = minutes
            }
        }
    }
}
