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
private const val DAILY_BACKUP_PERIODIC_NAME = "daily_backup_periodic"
private const val DAILY_BACKUP_INTERVAL_HOURS = 24L
private const val MONTHLY_BACKUP_PERIODIC_NAME = "monthly_backup_periodic"
private const val MONTHLY_BACKUP_INTERVAL_DAYS = 30L
private const val REMINDER_RESYNC_PERIODIC_NAME = "reminder_resync_periodic"
private const val REMINDER_RESYNC_MANUAL_NAME = "reminder_resync_manual"
private const val REMINDER_RESYNC_INTERVAL_HOURS = 6L

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
        scheduleDailyBackup(context)
        scheduleMonthlyBackup(context)
        scheduleReminderResync(context)
    }

    /** 일일 로컬 백업. 네트워크는 필요 없고, 배터리가 부족할 땐 미룬다. */
    fun scheduleDailyBackup(context: Context) {
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
        val request = PeriodicWorkRequestBuilder<DailyBackupWorker>(DAILY_BACKUP_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(DAILY_BACKUP_PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** 30일 주기 Drive 백업. 네트워크 + 배터리 제약 — Drive 미연결이면 Worker 안에서 조용히 건너뛴다. */
    fun scheduleMonthlyBackup(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<MonthlyBackupWorker>(MONTHLY_BACKUP_INTERVAL_DAYS, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(MONTHLY_BACKUP_PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
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

    /**
     * 알람 재동기화 주기 작업 — 반복 일정의 다음 회차를 주기적으로 보충하고, 두 번째 이후
     * 재동기화부터는 "그 사이 다른 앱이 캘린더를 바꿨는데 옵저버를 놓친" 경우의 안전망 역할도 한다.
     */
    fun scheduleReminderResync(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderResyncWorker>(REMINDER_RESYNC_INTERVAL_HOURS, TimeUnit.HOURS).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(REMINDER_RESYNC_PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * 일정 생성/수정/삭제, 캘린더 옵저버, 재부팅, 시간대/날짜 변경 등에서 즉시 재동기화가 필요할 때
     * 호출한다. expedited로 최대한 빨리 실행하되, 같은 이름으로 중복 예약되면 최신 요청 하나로
     * 합친다(REPLACE) — 짧은 시간에 여러 변경이 몰려도 알람 재계산은 마지막 상태 기준 한 번이면 된다.
     */
    fun triggerReminderResyncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReminderResyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(REMINDER_RESYNC_MANUAL_NAME, ExistingWorkPolicy.REPLACE, request)
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
