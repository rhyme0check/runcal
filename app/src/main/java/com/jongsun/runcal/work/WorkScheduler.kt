package com.jongsun.runcal.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private const val NOTION_SYNC_PERIODIC_NAME = "notion_sync_periodic"
private const val NOTION_SYNC_MANUAL_NAME = "notion_sync_manual"

/** 기본 동기화 주기. 설정 화면의 1/3/6/12시간 선택(3단계)이 이 값을 대체한다. */
const val DEFAULT_NOTION_SYNC_INTERVAL_HOURS = 3L

object WorkScheduler {

    /**
     * 앱 시작마다 무조건 호출된다. [ExistingPeriodicWorkPolicy.KEEP]을 쓰는 이유: REPLACE/UPDATE를
     * 쓰면 앱을 자주 여는 사용자는 주기 타이머가 매번 리셋되어 사실상 영원히 실행되지 않는다.
     */
    fun scheduleAll(context: Context, intervalHours: Long = DEFAULT_NOTION_SYNC_INTERVAL_HOURS) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<NotionSyncWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(NOTION_SYNC_PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** 설정에서 동기화 주기를 바꿨을 때(3단계 UI) 호출 — 이번엔 의도적으로 UPDATE. */
    fun reschedulePeriodic(context: Context, intervalHours: Long) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<NotionSyncWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(NOTION_SYNC_PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** 수동 새로고침 — 주 경로. expedited로 최대한 빨리 실행한다. */
    fun triggerManualSync(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<NotionSyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOTION_SYNC_MANUAL_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
