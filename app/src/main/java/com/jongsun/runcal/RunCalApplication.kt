package com.jongsun.runcal

import android.app.Application
import androidx.work.Configuration
import com.jongsun.runcal.work.WorkScheduler
import com.jongsun.runcal.work.RunCalWorkerFactory

/**
 * 캘린더 ContentObserver는 여기서 등록하지 않는다.
 * Application.onCreate는 런타임 권한 요청보다 먼저 실행되므로, 이 시점에는 READ_CALENDAR 권한이
 * 없을 수 있어 registerContentObserver 호출 시 SecurityException으로 크래시가 발생할 수 있다.
 * 대신 [CalendarObserverManager.register]를 권한이 확인된 시점(권한 승인 콜백, MainActivity
 * onCreate에서 권한이 이미 있는 경우)에 호출한다.
 *
 * WorkManager는 캘린더 권한과 무관하므로(Notion 동기화는 READ_CALENDAR가 없어도 동작해야 한다)
 * onCreate에서 바로 예약한다. [Configuration.Provider] 구현은 WorkManager의 기본 자동 초기화
 * (androidx-startup ContentProvider)가 리플렉션으로 감지해 그대로 써주므로 매니페스트 변경이 필요 없다.
 */
class RunCalApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(RunCalWorkerFactory())
            .build()

    override fun onCreate() {
        super.onCreate()
        WorkScheduler.scheduleAll(this)
    }
}
