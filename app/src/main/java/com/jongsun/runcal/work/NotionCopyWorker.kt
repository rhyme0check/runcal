package com.jongsun.runcal.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jongsun.runcal.data.AppSettingsRepository
import com.jongsun.runcal.data.NotionCalendarCopyJob
import com.jongsun.runcal.data.UsageLog
import com.jongsun.runcal.data.notion.NotionApiClient
import com.jongsun.runcal.data.room.RunCalDatabase
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first

private const val TAG = "RunCal"
private const val PREFS = "notion_copy"
private const val KEY_LAST_DAY = "last_copy_day"
private const val KEY_LAST_RESULT = "last_copy_result"

/**
 * 매월 15일과 말일에 Notion 항목을 폰 캘린더로 복사한다(하루 두 번 깨어나 오늘이 그날인지 본다).
 * 그날 폰이 꺼져 있었으면 다음에 깨어났을 때 16일 넘게 안 했으면 바로 한다. 복사 전에 Notion을 한 번 새로 받는다.
 */
class NotionCopyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (AppSettingsRepository(app).settings.first().copyCalendarId == null) return Result.success()
        val today = LocalDate.now()
        if (!isCopyDue(app, today)) return Result.success()
        runCatching {
            val db = RunCalDatabase.getInstance(app)
            NotionSyncJob(db.notionDatabaseDao(), db.notionEventDao(), NotionApiClient()).syncAll()
        }.onFailure { Log.w(TAG, "NotionCopyWorker: sync before copy failed, copying cached data", it) }
        val result = NotionCalendarCopyJob(app).run()
        recordCopy(app, today, result.summary)
        if (result.error != null) UsageLog.error(app, "notion_copy", result.error) else UsageLog.track(app, "notion_copy_auto")
        return Result.success()
    }

    companion object {
        fun isCopyDue(context: Context, today: LocalDate): Boolean {
            val last = lastCopyDay(context)
            if (last == today) return false
            val scheduledDay = today.dayOfMonth == 15 || today.dayOfMonth == today.lengthOfMonth()
            val overdue = last != null && ChronoUnit.DAYS.between(last, today) > 16
            return scheduledDay || overdue || last == null
        }

        fun lastCopyDay(context: Context): LocalDate? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_DAY, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        fun lastResult(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_RESULT, null)

        fun recordCopy(context: Context, day: LocalDate, summary: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_DAY, day.toString())
                .putString(KEY_LAST_RESULT, summary)
                .apply()
        }
    }
}
