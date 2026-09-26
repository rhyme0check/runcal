package com.jongsun.runcal

import android.app.Application
import com.jongsun.runcal.ai.syncAssistantShortcut
import com.jongsun.runcal.notification.ensureReminderNotificationChannel
import com.jongsun.runcal.work.WorkScheduler

/**
 * 캘린더 ContentObserver는 여기서 등록하지 않는다.
 * Application.onCreate는 런타임 권한 요청보다 먼저 실행되므로, 이 시점에는 READ_CALENDAR 권한이
 * 없을 수 있어 registerContentObserver 호출 시 SecurityException으로 크래시가 발생할 수 있다.
 * 대신 [CalendarObserverManager.register]를 권한이 확인된 시점(권한 승인 콜백, MainActivity
 * onCreate에서 권한이 이미 있는 경우)에 호출한다.
 *
 * WorkManager는 캘린더 권한과 무관하므로(Notion 동기화는 READ_CALENDAR가 없어도 동작해야 한다)
 * onCreate에서 바로 예약한다. WorkManager는 androidx.startup이 기본 설정으로 초기화하므로 사용자 지정
 * WorkerFactory는 쓰이지 않는다 — 모든 Worker는 (Context, WorkerParameters) 생성자만 갖고 의존성은
 * 스스로 만들어야 한다.
 */
class RunCalApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ensureReminderNotificationChannel(this)
        syncAssistantShortcut(this)
        WorkScheduler.scheduleAll(this)
    }
}
