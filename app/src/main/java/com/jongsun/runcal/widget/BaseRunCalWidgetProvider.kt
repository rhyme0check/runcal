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

/**
 * 위젯 6종이 공유하는 AppWidgetProvider 로직. 실제 위젯 종류 구분은 각 하위 클래스가 서로 다른
 * 컴포넌트로 등록되는 것 자체로 이뤄지고(→ [WidgetKind.forAppWidgetId]가 이 컴포넌트 이름으로
 * 역조회), 이 베이스 클래스 자체는 어떤 종류인지 몰라도 된다 — [RunCalWidgetRenderer.updateWidget]
 * 안에서 알아서 분기하기 때문이다.
 */
abstract class BaseRunCalWidgetProvider : AppWidgetProvider() {

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
