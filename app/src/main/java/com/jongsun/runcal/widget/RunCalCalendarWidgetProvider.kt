package com.jongsun.runcal.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "RunCal"

/** 고전 AppWidgetProvider. Glance의 GlanceAppWidgetReceiver를 대체한다. */
class RunCalCalendarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(5_000) {
                    appWidgetIds.forEach { appWidgetId ->
                        Log.d(TAG, "onUpdate: appWidgetId=$appWidgetId")
                        RunCalWidgetRenderer.updateWidget(context, appWidgetId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onUpdate failed, keeping previous view", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(5_000) {
                    RunCalWidgetRenderer.updateWidget(context, appWidgetId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "onAppWidgetOptionsChanged failed, keeping previous view", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            Log.d(TAG, "onDeleted: appWidgetId=$appWidgetId, clearing stored settings")
            deleteWidgetFilterSettings(context, appWidgetId)
        }
    }
}
