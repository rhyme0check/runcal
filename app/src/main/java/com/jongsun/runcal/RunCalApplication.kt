package com.jongsun.runcal

import android.app.Application
import com.jongsun.runcal.ai.syncAssistantShortcut
import com.jongsun.runcal.data.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        // 오류로 꺼질 때 원인을 남기고(앱 안에만), 지난 기록을 정리한다.
        com.jongsun.runcal.data.UsageLog.installCrashHandler(this)
        com.jongsun.runcal.data.UsageLog.onAppStart(this)
        // 시각 표기(24/12시간)는 위젯·알림·화면이 동기로 읽으므로 가장 먼저 읽어 둔다.
        com.jongsun.runcal.data.TimeFormatPrefs.load(this)
        ensureReminderNotificationChannel(this)
        syncAssistantShortcut(this)
        WorkScheduler.scheduleAll(this)
        // 저장된 Notion 동기화 주기를 실제 예약에 반영한다(DataStore 읽기가 suspend라 백그라운드에서).
        appScope.launch {
            val hours = AppSettingsRepository(this@RunCalApplication).settings.first().notionSyncIntervalHours
            WorkScheduler.applySavedIntervalIfChanged(this@RunCalApplication, hours.toLong())
        }
        // 빌드에 넣어 둔 Notion DB(달리기·글적긁적)를 처음 실행 때 등록한다.
        appScope.launch { runCatching { com.jongsun.runcal.data.notion.NotionSeed.applyIfNeeded(this@RunCalApplication) } }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
