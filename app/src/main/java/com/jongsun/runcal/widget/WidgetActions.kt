package com.jongsun.runcal.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "RunCal"

val NAV_DIRECTION_KEY = ActionParameters.Key<Int>("nav_direction")

/**
 * 콜백/액티비티 진입 시각을 위젯별로 기록해, provideGlance가 실제로 재구성을 시작할 때까지
 * 걸린 시간을 진단 로그로 남기기 위한 저장소. Glance의 SessionWorker(WorkManager)가 세션을
 * 재개하는 데 걸리는 지연을 "어떤 조작이 얼마나 늦게 반영되는지" 패턴으로 확인하는 용도다.
 */
object WidgetCallbackTiming {
    private val lastCallbackAtMillis = ConcurrentHashMap<Int, Long>()

    fun markCallback(appWidgetId: Int) {
        lastCallbackAtMillis[appWidgetId] = System.currentTimeMillis()
    }

    /** 마지막 markCallback 이후 경과 시간(ms). 콜백 없이 트리거된 갱신(주기 갱신 등)이면 null. */
    fun elapsedSinceCallback(appWidgetId: Int): Long? =
        lastCallbackAtMillis[appWidgetId]?.let { System.currentTimeMillis() - it }
}

/**
 * ◀▶ 연타 시 매번 applyWidgetStateByGlanceId(→ RunCalCalendarWidget().update())를 호출하면
 * 위젯별로 세션 재시작 요청이 겹쳐 Glance SessionWorker가 세션을 재구성 없이 닫아버리는
 * 현상(GlanceSessionManager "Closing session ... wasOpen=false")이 확인됐다. 마지막 탭 이후
 * 일정 시간 동안 추가 탭이 없을 때만 실제로 1회 반영해 세션 경합 자체를 없앤다.
 */
private object NavigateDebouncer {
    private const val DEBOUNCE_MILLIS = 200L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 위젯별로 "아직 반영되지 않은 최신 목표 연월". 디바운스 창 안의 연속 탭은 이 값을 이어서 누적한다.
    private val pendingYearMonth = ConcurrentHashMap<Int, YearMonth>()
    private val pendingJobs = ConcurrentHashMap<Int, Job>()

    fun bump(appWidgetId: Int, baseline: YearMonth, direction: Int, apply: suspend (YearMonth) -> Unit) {
        val base = pendingYearMonth[appWidgetId] ?: baseline
        val next = if (direction > 0) base.plusMonths(1) else base.minusMonths(1)
        pendingYearMonth[appWidgetId] = next
        Log.d(TAG, "NavigateDebouncer: id=$appWidgetId pending target now $next (debounced)")

        pendingJobs[appWidgetId]?.cancel()
        pendingJobs[appWidgetId] = scope.launch {
            delay(DEBOUNCE_MILLIS)
            val target = pendingYearMonth.remove(appWidgetId) ?: next
            pendingJobs.remove(appWidgetId)
            Log.d(TAG, "NavigateDebouncer: id=$appWidgetId debounce elapsed, applying target=$target")
            apply(target)
        }
    }
}

/** 헤더 2단 ◀/▶ — 표시 중인 연월을 전월/다음달로 옮긴다. 연타는 디바운스로 흡수해 1회만 반영한다. */
class NavigateMonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        // 콜백이 실제로 호출되는지부터 확인하기 위해 가장 먼저 찍는다.
        Log.d(TAG, "callback=NavigateMonthAction id=$appWidgetId")
        WidgetCallbackTiming.markCallback(appWidgetId)
        val direction = parameters[NAV_DIRECTION_KEY]
        if (direction == null) {
            Log.e(TAG, "callback=NavigateMonthAction id=$appWidgetId NAV_DIRECTION_KEY missing from parameters=$parameters")
            return
        }
        val baseline = loadWidgetFilterSettings(context, glanceId).viewingYearMonth ?: YearMonth.now()
        NavigateDebouncer.bump(appWidgetId, baseline, direction) { target ->
            applyWidgetStateByGlanceId(context, glanceId) { current ->
                Log.d(TAG, "arrow: id=$appWidgetId before=${current.viewingYearMonth} after=$target")
                current.copy(viewingYearMonth = target, lastNavigatedAtMillis = System.currentTimeMillis())
            }
        }
    }
}

/** 헤더 2단 중앙 연월 텍스트 탭 — 어떤 달을 보고 있든 즉시 "오늘이 있는 달"로 되돌아간다. */
class JumpToTodayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        Log.d(TAG, "callback=JumpToTodayAction id=$appWidgetId")
        WidgetCallbackTiming.markCallback(appWidgetId)
        applyWidgetStateByGlanceId(context, glanceId) { current ->
            Log.d(TAG, "jumpToToday: id=$appWidgetId before=${current.viewingYearMonth}")
            current.copy(viewingYearMonth = null, lastNavigatedAtMillis = System.currentTimeMillis())
        }
    }
}
