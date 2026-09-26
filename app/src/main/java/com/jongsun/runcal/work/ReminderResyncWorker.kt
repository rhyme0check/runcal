package com.jongsun.runcal.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jongsun.runcal.notification.ReminderScheduler

private const val TAG = "RunCal"

/**
 * 알람 재동기화 전용 워커. 일정 생성/수정/삭제, 타 앱의 캘린더 변경, 재부팅, 시간대/날짜 변경,
 * 주기적 보충(반복 일정 다음 회차 채우기) 등 여러 트리거가 전부 이 워커 하나를 예약한다 —
 * 실제 로직(ReminderScheduler.resync)은 위젯 렌더링 경로와 완전히 분리된 별도 진입점이다.
 */
class ReminderResyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            ReminderScheduler.resync(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "ReminderResyncWorker: resync failed", e)
            Result.retry()
        }
    }
}
