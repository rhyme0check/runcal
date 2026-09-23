package com.jongsun.runcal.widget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition

private const val TAG = "RunCal"

/** [glanceId] 위젯 인스턴스의 저장된 필터/표시 설정을 읽는다. 설정이 없으면 기본값을 반환한다. */
suspend fun loadWidgetFilterSettings(context: Context, glanceId: GlanceId): WidgetFilterSettings =
    getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId).toWidgetFilterSettings()

/**
 * [glanceId] 위젯 인스턴스의 Glance 상태(PreferencesGlanceStateDefinition, 인스턴스별 전용 DataStore)를
 * [transform]으로 갱신한 뒤, 상태 쓰기가 완전히 끝난 다음에만 RunCalCalendarWidget().update()를 호출한다.
 * 이 순서를 지키지 않고 동일 위젯에 대해 update()를 중복/경합 호출하면(예: 브로드캐스트를 겸용) Glance
 * SessionManager가 세션을 재구성 없이 닫아버리는("Closing session ... wasOpen=false") 현상이 로그로
 * 확인되어, 이 함수 하나만을 모든 상태 변경의 단일 경로로 삼는다.
 */
private suspend fun writeWidgetStateAndRefresh(
    context: Context,
    glanceId: GlanceId,
    transform: (WidgetFilterSettings) -> WidgetFilterSettings,
) {
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs: Preferences ->
        val updated = transform(prefs.toWidgetFilterSettings())
        prefs.toMutablePreferences().apply { applyWidgetFilterSettings(updated) }
    }
    Log.d(TAG, "writeWidgetStateAndRefresh: appWidgetId=$appWidgetId state committed, calling update()")
    RunCalCalendarWidget().update(context, glanceId)
    Log.d(TAG, "writeWidgetStateAndRefresh: appWidgetId=$appWidgetId update() returned")
}

/**
 * 위젯의 "표시 상태"(보고 있는 연월, 프리셋 인덱스 등)를 바꾸는 모든 경로가 공유하는 단일 진입점.
 * 월 이동(◀▶), 오늘로 점프, 연월 선택 팝업, 프리셋 순환이 전부 이 함수를 거친다.
 * ActionCallback처럼 이미 GlanceId를 가진 호출부는 appWidgetId로 굳이 왕복하지 않도록
 * [applyWidgetStateByGlanceId]를 대신 쓴다.
 */
suspend fun applyWidgetState(
    context: Context,
    appWidgetId: Int,
    transform: (WidgetFilterSettings) -> WidgetFilterSettings,
) {
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
    writeWidgetStateAndRefresh(context, glanceId, transform)
}

/** [applyWidgetState]와 동일하지만 GlanceId를 이미 가진 호출부(ActionCallback 등)를 위한 변형. */
suspend fun applyWidgetStateByGlanceId(
    context: Context,
    glanceId: GlanceId,
    transform: (WidgetFilterSettings) -> WidgetFilterSettings,
) {
    writeWidgetStateAndRefresh(context, glanceId, transform)
}

/**
 * 설정 화면(RunCalWidgetConfigActivity)의 저장 버튼에서 호출하는 전체 설정 저장.
 * 저장 직후 실제로 기록됐는지 다시 읽어 검증하고, 불일치 시 로그로 남긴다.
 * 헤더 조작으로만 바뀌는 viewingYearMonth/lastNavigatedAtMillis는 건드리지 않고 보존한다.
 */
suspend fun saveWidgetFilterSettings(
    context: Context,
    glanceId: GlanceId,
    presets: List<WidgetPreset>,
    currentPresetIndex: Int,
    fontScaleStep: Int,
    backgroundOpacity: Float,
) {
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
    Log.d(
        TAG,
        "saveWidgetFilterSettings: appWidgetId=$appWidgetId writing presets=${presets.size} " +
            "currentPresetIndex=$currentPresetIndex fontScaleStep=$fontScaleStep backgroundOpacity=$backgroundOpacity",
    )

    writeWidgetStateAndRefresh(context, glanceId) { current ->
        current.copy(
            presets = presets,
            currentPresetIndex = currentPresetIndex,
            fontScaleStep = fontScaleStep,
            backgroundOpacity = backgroundOpacity,
        )
    }

    val verified = loadWidgetFilterSettings(context, glanceId)
    val mismatch = verified.presets != presets ||
        verified.currentPresetIndex != currentPresetIndex ||
        verified.fontScaleStep != fontScaleStep ||
        verified.backgroundOpacity != backgroundOpacity
    if (mismatch) {
        Log.e(TAG, "saveWidgetFilterSettings: appWidgetId=$appWidgetId VERIFICATION MISMATCH readBack=$verified")
    } else {
        Log.d(TAG, "saveWidgetFilterSettings: appWidgetId=$appWidgetId write verified OK")
    }
}
