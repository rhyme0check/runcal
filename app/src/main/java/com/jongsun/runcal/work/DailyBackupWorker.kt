package com.jongsun.runcal.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jongsun.runcal.data.backup.BackupJob

private const val TAG = "RunCal"

/**
 * 24시간마다 로컬 전용 자동 백업 한 장을 쓴다(Drive 없음, 6단계 A 범위). 의존성이 Context뿐이라
 * (BackupJob이 그 자리에서 Room/DataStore/CalendarContract를 직접 연다) 커스텀
 * WorkerFactory 등록 없이 기본 (Context, WorkerParameters) 생성자로 충분하다.
 */
class DailyBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val file = BackupJob.writeDailyBackup(applicationContext)
            Log.d(TAG, "DailyBackupWorker: wrote ${file.name}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "DailyBackupWorker: backup failed, will retry", e)
            Result.retry()
        }
    }
}
