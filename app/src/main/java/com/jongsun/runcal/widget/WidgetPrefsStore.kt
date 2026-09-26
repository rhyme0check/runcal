package com.jongsun.runcal.widget

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json

private const val TAG = "RunCal"
private const val PREFS_NAME_PREFIX = "widget_settings_"

private object Keys {
    const val PRESETS_JSON = "widget_presets_json"
    const val CURRENT_PRESET_INDEX = "current_preset_index"
    const val FONT_SCALE_STEP = "font_scale_step"
    const val BACKGROUND_OPACITY = "background_opacity"
    const val VIEWING_YEAR_MONTH = "viewing_year_month"
    const val LAST_NAVIGATED_AT_MILLIS = "last_navigated_at_millis"
    const val SHOW_WEEK_NUMBER = "show_week_number"
}

private fun prefsFor(context: Context, appWidgetId: Int): SharedPreferences =
    context.getSharedPreferences(PREFS_NAME_PREFIX + appWidgetId, Context.MODE_PRIVATE)

/**
 * 인스턴스별 설정의 프로세스 메모리 캐시. presets 목록의 JSON 디코드가 렌더 1회마다(실측
 * 11~132ms, 변동 큼) 반복되고 있었다 — 값이 실제로 바뀌는 지점([persistWidgetFilterSettings]/
 * [persistNavigationState])에서만 갱신하고, 그 외에는 디스크 읽기·JSON 디코드 자체를 건너뛴다.
 */
private val settingsCache = ConcurrentHashMap<Int, WidgetFilterSettings>()

private fun SharedPreferences.toWidgetFilterSettings(): WidgetFilterSettings {
    val presets = getString(Keys.PRESETS_JSON, null)?.let { json ->
        try {
            Json.decodeFromString(presetListSerializer, json)
        } catch (e: Exception) {
            Log.e(TAG, "toWidgetFilterSettings: failed to parse presets json", e)
            emptyList()
        }
    } ?: emptyList()

    val viewingYearMonth = getString(Keys.VIEWING_YEAR_MONTH, null)?.let {
        try {
            YearMonth.parse(it)
        } catch (e: Exception) {
            null
        }
    }

    return WidgetFilterSettings(
        presets = presets,
        currentPresetIndex = getInt(Keys.CURRENT_PRESET_INDEX, 0),
        fontScaleStep = getInt(Keys.FONT_SCALE_STEP, DEFAULT_FONT_SCALE_STEP),
        backgroundOpacity = getFloat(Keys.BACKGROUND_OPACITY, DEFAULT_BACKGROUND_OPACITY),
        viewingYearMonth = viewingYearMonth,
        lastNavigatedAtMillis = getLong(Keys.LAST_NAVIGATED_AT_MILLIS, 0L),
        showWeekNumber = getBoolean(Keys.SHOW_WEEK_NUMBER, false),
    )
}

private fun SharedPreferences.Editor.applyWidgetFilterSettings(settings: WidgetFilterSettings): SharedPreferences.Editor {
    putString(Keys.PRESETS_JSON, Json.encodeToString(presetListSerializer, settings.presets))
    putInt(Keys.CURRENT_PRESET_INDEX, settings.currentPresetIndex)
    putInt(Keys.FONT_SCALE_STEP, settings.fontScaleStep)
    putFloat(Keys.BACKGROUND_OPACITY, settings.backgroundOpacity)
    if (settings.viewingYearMonth == null) {
        remove(Keys.VIEWING_YEAR_MONTH)
    } else {
        putString(Keys.VIEWING_YEAR_MONTH, settings.viewingYearMonth.toString())
    }
    putLong(Keys.LAST_NAVIGATED_AT_MILLIS, settings.lastNavigatedAtMillis)
    putBoolean(Keys.SHOW_WEEK_NUMBER, settings.showWeekNumber)
    return this
}

/** [appWidgetId] 위젯 인스턴스의 저장된 필터/표시 설정을 읽는다. 설정이 없으면 기본값을 반환한다. */
fun loadWidgetFilterSettings(context: Context, appWidgetId: Int): WidgetFilterSettings =
    settingsCache.getOrPut(appWidgetId) { prefsFor(context, appWidgetId).toWidgetFilterSettings() }

/**
 * ◀▶/오늘 탭마다 바뀌는 두 값(viewingYearMonth, lastNavigatedAtMillis)만 읽는다. 캐시에 이미
 * 올라와 있으면(대부분의 경우) 디스크/JSON 디코드 없이 바로 반환한다.
 */
private fun loadNavigationState(context: Context, appWidgetId: Int): Pair<YearMonth?, Long> {
    val settings = loadWidgetFilterSettings(context, appWidgetId)
    return settings.viewingYearMonth to settings.lastNavigatedAtMillis
}

/** [loadNavigationState]의 반대 — 이 두 값만 디스크에 쓴다(presets 등 나머지 필드는 건드리지 않음, JSON 인코딩 없음). */
fun persistNavigationState(context: Context, appWidgetId: Int, viewingYearMonth: YearMonth?, lastNavigatedAtMillis: Long) {
    prefsFor(context, appWidgetId).edit().apply {
        if (viewingYearMonth == null) remove(Keys.VIEWING_YEAR_MONTH) else putString(Keys.VIEWING_YEAR_MONTH, viewingYearMonth.toString())
        putLong(Keys.LAST_NAVIGATED_AT_MILLIS, lastNavigatedAtMillis)
    }.apply()
    val current = settingsCache[appWidgetId] ?: loadWidgetFilterSettings(context, appWidgetId)
    settingsCache[appWidgetId] = current.copy(viewingYearMonth = viewingYearMonth, lastNavigatedAtMillis = lastNavigatedAtMillis)
}

/** ◀▶/오늘 탭 전용 — 전체 설정을 거치지 않고 탐색 상태 두 값만 갱신한 뒤 바로 다시 그린다. */
suspend fun applyNavigationState(
    context: Context,
    appWidgetId: Int,
    transform: (viewingYearMonth: YearMonth?) -> Pair<YearMonth?, Long>,
) {
    val t0 = System.currentTimeMillis()
    val (currentViewing, _) = loadNavigationState(context, appWidgetId)
    val (newViewing, newLastNavigated) = transform(currentViewing)
    val t1 = System.currentTimeMillis()
    persistNavigationState(context, appWidgetId, newViewing, newLastNavigated)
    val t2 = System.currentTimeMillis()
    Log.d(TAG, "applyNavigationState: appWidgetId=$appWidgetId load=${t1 - t0}ms persist=${t2 - t1}ms")
    RunCalWidgetRenderer.updateWidget(context, appWidgetId)
}

/**
 * 렌더링을 트리거하지 않고 상태만 동기 저장한다. 렌더러 안(resolveAutoReturn 등)에서 "지금 만들고 있는
 * RemoteViews에 반영될 값"을 먼저 저장해둘 때 쓴다 — [applyWidgetState]를 쓰면 렌더 함수가 자기 자신을
 * 다시 호출하는 재귀가 생긴다.
 */
fun persistWidgetFilterSettings(context: Context, appWidgetId: Int, settings: WidgetFilterSettings) {
    prefsFor(context, appWidgetId).edit().applyWidgetFilterSettings(settings).apply()
    settingsCache[appWidgetId] = settings
}

/**
 * [appWidgetId] 위젯 인스턴스의 설정을 읽어와 [transform]으로 원하는 필드만 바꾼 뒤 SharedPreferences에
 * 동기 저장하고, 곧바로 RemoteViews를 다시 그려 AppWidgetManager에 반영한다. WorkManager를 전혀 거치지
 * 않으므로 이 함수가 반환하는 시점에는 위젯 화면이 이미 갱신된 상태다.
 */
suspend fun applyWidgetState(
    context: Context,
    appWidgetId: Int,
    transform: (WidgetFilterSettings) -> WidgetFilterSettings,
) {
    val t0 = System.currentTimeMillis()
    val updated = transform(loadWidgetFilterSettings(context, appWidgetId))
    val t1 = System.currentTimeMillis()
    persistWidgetFilterSettings(context, appWidgetId, updated)
    val t2 = System.currentTimeMillis()
    Log.d(TAG, "applyWidgetState: appWidgetId=$appWidgetId load=${t1 - t0}ms persist=${t2 - t1}ms")
    RunCalWidgetRenderer.updateWidget(context, appWidgetId)
}

/**
 * 설정 화면(RunCalWidgetConfigActivity)의 저장 버튼에서 호출하는 전체 설정 저장.
 * 헤더 조작으로만 바뀌는 viewingYearMonth/lastNavigatedAtMillis는 건드리지 않고 보존한다.
 */
suspend fun saveWidgetFilterSettings(
    context: Context,
    appWidgetId: Int,
    presets: List<WidgetPreset>,
    currentPresetIndex: Int,
    fontScaleStep: Int,
    backgroundOpacity: Float,
    showWeekNumber: Boolean,
) {
    Log.d(
        TAG,
        "saveWidgetFilterSettings: appWidgetId=$appWidgetId writing presets=${presets.size} " +
            "currentPresetIndex=$currentPresetIndex fontScaleStep=$fontScaleStep backgroundOpacity=$backgroundOpacity " +
            "showWeekNumber=$showWeekNumber",
    )
    applyWidgetState(context, appWidgetId) { current ->
        current.copy(
            presets = presets,
            currentPresetIndex = currentPresetIndex,
            fontScaleStep = fontScaleStep,
            backgroundOpacity = backgroundOpacity,
            showWeekNumber = showWeekNumber,
        )
    }
}

/** 위젯 인스턴스가 삭제될 때 해당 SharedPreferences 파일과 메모리 캐시를 정리한다. */
fun deleteWidgetFilterSettings(context: Context, appWidgetId: Int) {
    context.deleteSharedPreferences(PREFS_NAME_PREFIX + appWidgetId)
    settingsCache.remove(appWidgetId)
}
