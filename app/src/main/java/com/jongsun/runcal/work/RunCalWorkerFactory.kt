package com.jongsun.runcal.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.jongsun.runcal.data.notion.NotionApiClient
import com.jongsun.runcal.data.room.RunCalDatabase

/** 이 앱의 나머지 코드와 같은 컨벤션(DI 없이 생성자에서 직접 조립)으로 Worker 의존성을 만든다. */
class RunCalWorkerFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        NotionSyncWorker::class.java.name -> {
            val db = RunCalDatabase.getInstance(appContext)
            val syncJob = NotionSyncJob(db.notionDatabaseDao(), db.notionEventDao(), NotionApiClient())
            NotionSyncWorker(appContext, workerParameters, syncJob)
        }
        else -> null
    }
}
