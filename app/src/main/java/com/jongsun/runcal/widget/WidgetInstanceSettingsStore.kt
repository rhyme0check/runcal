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
 * [glanceId] 위젯 인스턴스에 필터/표시 설정을 저장하고 해당 인스턴스만 즉시 갱신한다.
 * 저장 직후 실제로 기록됐는지 다시 읽어 검증하고, 불일치 시 로그로 남긴다.
 */
suspend fun saveWidgetFilterSettings(context: Context, glanceId: GlanceId, settings: WidgetFilterSettings) {
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
    Log.d(
        TAG,
        "saveWidgetFilterSettings: appWidgetId=$appWidgetId writing " +
            "selectedCalendarIds=${settings.selectedCalendarIds} fontScaleStep=${settings.fontScaleStep} " +
            "backgroundOpacity=${settings.backgroundOpacity}",
    )

    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs: Preferences ->
        prefs.toMutablePreferences().apply { applyWidgetFilterSettings(settings) }
    }

    val verified = loadWidgetFilterSettings(context, glanceId)
    if (verified != settings) {
        Log.e(
            TAG,
            "saveWidgetFilterSettings: appWidgetId=$appWidgetId VERIFICATION MISMATCH " +
                "wrote=$settings readBack=$verified",
        )
    } else {
        Log.d(TAG, "saveWidgetFilterSettings: appWidgetId=$appWidgetId write verified OK")
    }

    RunCalCalendarWidget().update(context, glanceId)
    Log.d(TAG, "saveWidgetFilterSettings: appWidgetId=$appWidgetId update() triggered")
}
