package com.jongsun.runcal.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

private const val TAG = "RunCal"

/**
 * 등록된 Notion DB가 없으면 [NotionSyncJob.syncAll]이 빈 리스트를 순회하고 바로 끝난다 —
 * 별도의 "등록 없음" 분기 없이 자연히 inert하게 동작한다.
 */
class NotionSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val syncJob: NotionSyncJob,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
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
