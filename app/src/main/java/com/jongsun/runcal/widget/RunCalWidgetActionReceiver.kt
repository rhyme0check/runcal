package com.jongsun.runcal.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "RunCal"

/**
 * 헤더 ◀▶ / 중앙 연월(오늘로 이동) 탭을 처리한다. Glance의 actionRunCallback을 대체하지만,
 * 이번에는 WorkManager를 전혀 거치지 않고 이 리시버 안에서 상태 저장 + RemoteViews 갱신을 직접 끝낸다.
 */
class RunCalWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val action = intent.action
        Log.d(TAG, "callback=RunCalWidgetActionReceiver action=$action id=$appWidgetId")
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || action == null) return

        val tapAtMillis = System.currentTimeMillis()
        // goAsync()로 브로드캐스트 처리 시간을 연장하되, 시스템이 허용하는 한도(~10초)를 넘기지 않도록
        // withTimeout으로 한 번 더 못박는다. 어떤 경로로 빠져나가든 finish()는 반드시 호출한다.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "dispatch: appWidgetId=$appWidgetId coroutine started after ${System.currentTimeMillis() - tapAtMillis}ms")
                withTimeout(5_000) {
                    when (action) {
                        WidgetActionContract.ACTION_NAV_PREV -> shiftMonth(context, appWidgetId, -1)
                        WidgetActionContract.ACTION_NAV_NEXT -> shiftMonth(context, appWidgetId, 1)
                        WidgetActionContract.ACTION_JUMP_TODAY -> jumpToday(context, appWidgetId)
                        else -> Log.e(TAG, "RunCalWidgetActionReceiver: unknown action=$action id=$appWidgetId")
                    }
                }
                Log.d(TAG, "click→update elapsed=${System.currentTimeMillis() - tapAtMillis}ms")
            } catch (e: Exception) {
                // 실패 시 위젯에 이미 떠 있는 이전 RemoteViews를 그대로 두고(재시도하지 않음) 로그만 남긴다.
                Log.e(
                    TAG,
                    "RunCalWidgetActionReceiver: action=$action id=$appWidgetId failed after " +
                        "${System.currentTimeMillis() - tapAtMillis}ms, keeping previous view",
                    e,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun shiftMonth(context: Context, appWidgetId: Int, direction: Int) {
        applyNavigationState(context, appWidgetId) { currentViewing ->
            val base = currentViewing ?: YearMonth.now()
            val next = if (direction > 0) base.plusMonths(1) else base.minusMonths(1)
            Log.d(TAG, "arrow: id=$appWidgetId before=$currentViewing after=$next")
            next to System.currentTimeMillis()
        }
    }

    private suspend fun jumpToday(context: Context, appWidgetId: Int) {
        applyNavigationState(context, appWidgetId) { currentViewing ->
            Log.d(TAG, "jumpToToday: id=$appWidgetId before=$currentViewing")
            null to System.currentTimeMillis()
        }
    }
}
