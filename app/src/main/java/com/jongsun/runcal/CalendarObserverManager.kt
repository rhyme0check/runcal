package com.jongsun.runcal

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.jongsun.runcal.data.hasCalendarReadPermission
import com.jongsun.runcal.widget.RunCalCalendarWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "RunCal"

/**
 * 시스템 캘린더 변경 감지용 ContentObserver를 READ_CALENDAR 권한 상태에 맞춰 등록/해제한다.
 * Application.onCreate처럼 런타임 권한 요청보다 먼저 실행되는 지점에서 호출하면 안 되고,
 * 권한이 확인된 시점(권한 승인 콜백, 이미 권한이 있는 상태의 화면 진입)에서만 register()를 호출해야 한다.
 */
object CalendarObserverManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observer: ContentObserver? = null

    fun register(context: Context) {
        if (observer != null) return
        if (!hasCalendarReadPermission(context)) {
            Log.w(TAG, "CalendarObserverManager.register: READ_CALENDAR permission not granted, skip")
            return
        }

        val appContext = context.applicationContext
        val newObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scope.launch {
                    try {
                        RunCalCalendarWidget().updateAll(appContext)
                    } catch (e: Exception) {
                        Log.e(TAG, "CalendarObserverManager: widget update failed", e)
                    }
                }
            }
        }

        try {
            appContext.contentResolver.registerContentObserver(
                CalendarContract.CONTENT_URI,
                true,
                newObserver,
            )
            observer = newObserver
        } catch (e: SecurityException) {
            Log.e(TAG, "CalendarObserverManager.register: registerContentObserver failed", e)
        }
    }

    fun unregister(context: Context) {
        val current = observer ?: return
        context.applicationContext.contentResolver.unregisterContentObserver(current)
        observer = null
    }
}
