package com.jongsun.runcal.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jongsun.runcal.data.notion.NotionApiClient
import com.jongsun.runcal.data.room.RunCalDatabase

private const val TAG = "RunCal"

/**
 * 등록된 Notion DB가 없으면 [NotionSyncJob.syncAll]이 빈 리스트를 순회하고 바로 끝난다 —
 * 별도의 "등록 없음" 분기 없이 자연히 inert하게 동작한다.
 */
class NotionSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // 의존성을 여기서 직접 만든다. WorkManager가 기본 설정으로 초기화되어(androidx.startup) 앱이 제공한
            // 사용자 지정 WorkerFactory는 쓰이지 않으므로, 생성자에 의존성을 받으면 워커 자체를 만들지 못한다.
            val db = RunCalDatabase.getInstance(applicationContext)
            val syncJob = NotionSyncJob(db.notionDatabaseDao(), db.notionEventDao(), NotionApiClient())
            val results = syncJob.syncAll()
            val totalEvents = results.sumOf { it.eventCount }
            Log.d(TAG, "NotionSyncWorker: synced ${results.size} db(s), $totalEvents event(s) cached")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "NotionSyncWorker: sync failed, will retry", e)
            Result.retry()
        }
    }
}
