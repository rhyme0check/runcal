package com.jongsun.runcal.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jongsun.runcal.data.AppSettingsRepository
import com.jongsun.runcal.data.backup.drive.DriveAuthManager
import com.jongsun.runcal.data.backup.drive.DriveBackupJob

private const val TAG = "RunCal"

/**
 * 30일 주기 Drive 백업. Drive가 연결돼 있지 않거나 재동의가 필요하면(Activity 없이는 동의 화면을
 * 띄울 수 없음) 조용히 건너뛰고 성공으로 끝낸다 — 재시도해도 인증 문제가 저절로 풀리지 않으므로,
 * driveLastError에 남겨 설정 화면이 "재연결 필요" 안내와 재연결 경로를 보여줄 수 있게 한다.
 * 네트워크 실패처럼 재시도로 나아질 수 있는 경우만 Result.retry()로 돌린다.
 */
class MonthlyBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settingsRepository = AppSettingsRepository(applicationContext)
        val accessToken = DriveAuthManager(applicationContext).silentAccessToken()
        if (accessToken == null) {
            Log.w(TAG, "MonthlyBackupWorker: Drive 미연결 또는 재인증 필요 — 이번 주기는 건너뜀")
            settingsRepository.setDriveLastError("Drive 인증이 만료됐습니다. 설정에서 다시 연결해주세요.")
            return Result.success()
        }
        return try {
            val file = DriveBackupJob.writeMonthlyBackup(applicationContext, accessToken)
            Log.d(TAG, "MonthlyBackupWorker: uploaded ${file.fileName}")
            settingsRepository.setDriveLastError(null)
            settingsRepository.setDriveLastSuccessAtMillis(System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "MonthlyBackupWorker: upload failed, will retry", e)
            Result.retry()
        }
    }
}
