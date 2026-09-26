package com.jongsun.runcal.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import com.jongsun.runcal.data.AppSettingsRepository
import com.jongsun.runcal.data.hasCalendarReadPermission
import com.jongsun.runcal.data.room.RunCalDatabase
import com.jongsun.runcal.data.room.ScheduledReminderEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "RunCal"

/** 반복 일정 하나당 앞으로 몇 회차까지만 알람을 걸어둘지. 전부 걸면 알람 슬롯을 낭비한다. */
private const val MAX_OCCURRENCES_PER_EVENT = 3

/** 회차를 찾는 스캔 범위 — 매주/매월 반복이라도 이 안에서 다음 회차를 찾을 수 있게 넉넉히 잡는다. */
private const val SCAN_WINDOW_DAYS = 60L

/**
 * "지금 걸려 있어야 할 알람"을 다시 계산해 AlarmManager와 동기화한다. 캘린더 전체(내가 만든
 * 일정 + 다른 앱이 만든 일정 전부, Notion 제외)를 대상으로 하고, 반복 일정은 회차를
 * [MAX_OCCURRENCES_PER_EVENT]개까지만 유지한다. 위젯 렌더링 경로(RunCalWidgetRenderer)와는
 * 완전히 분리된 별도 진입점이라 이 함수가 오래 걸려도 위젯 200ms대 예산에는 영향이 없다 —
 * 항상 WorkManager 백그라운드 작업에서만 호출할 것(ReminderResyncWorker).
 */
object ReminderScheduler {

    suspend fun resync(context: Context) = withContext(Dispatchers.IO) {
        val remindersEnabled = AppSettingsRepository(context).settings.first().remindersEnabled
        val dao = RunCalDatabase.getInstance(context).scheduledReminderDao()
        val allTracked = dao.getAll()
        val currentlyScheduled = allTracked.filterNot { isSnoozeKey(it.key) }.associateBy { it.key }
        val snoozes = allTracked.filter { isSnoozeKey(it.key) }

        if (!remindersEnabled || !hasCalendarReadPermission(context) || !canScheduleExactAlarms(context)) {
            Log.w(
                TAG,
                "ReminderScheduler.resync: skipped(enabled=$remindersEnabled, " +
                    "calendarPermission=${hasCalendarReadPermission(context)}, exactAlarm=${canScheduleExactAlarms(context)}) " +
                    "- cancelling ${currentlyScheduled.size} existing alarm(s)",
            )
            allTracked.forEach { cancelAlarm(context, it) }
            dao.deleteAll()
            return@withContext
        }

        restoreSnoozes(context, snoozes)
        val desired = computeDesiredSchedule(context)

        // 더 이상 필요 없어진 알람(일정 삭제/시각 변경/알림 제거) 취소.
        currentlyScheduled.values.filter { it.key !in desired }.forEach { stale ->
            cancelAlarm(context, stale)
            dao.deleteByKey(stale.key)
        }
        // 새로 필요해졌거나 트리거 시각이 바뀐 알람만 (재)등록 — 동일하면 건드리지 않는다.
        desired.values.forEach { wanted ->
            val existing = currentlyScheduled[wanted.key]
            if (existing == null || existing.triggerAtMillis != wanted.triggerAtMillis || !isAlarmRegistered(context, wanted)) {
                scheduleAlarm(context, wanted)
                dao.upsert(wanted)
            }
        }
        Log.d(TAG, "ReminderScheduler.resync: ${desired.size} alarm(s) scheduled")
    }

    /**
     * 스누즈 알람을 장부에 기록하고 건다. 같은 회차·알림의 이전 스누즈는 같은 키라 덮어써진다.
     * 정식 알람과 달리 [resync]의 stale 취소 대상이 아니다(키 접두사로 구분).
     */
    suspend fun scheduleSnooze(context: Context, eventId: Long, occurrenceBeginMillis: Long, reminderMinutes: Int, triggerAtMillis: Long) =
        withContext(Dispatchers.IO) {
            val item = ScheduledReminderEntity(snoozeKey(eventId, occurrenceBeginMillis, reminderMinutes), eventId, occurrenceBeginMillis, reminderMinutes, triggerAtMillis)
            RunCalDatabase.getInstance(context).scheduledReminderDao().upsert(item)
            scheduleAlarm(context, item)
        }

    suspend fun clearSnooze(context: Context, eventId: Long, occurrenceBeginMillis: Long, reminderMinutes: Int) = withContext(Dispatchers.IO) {
        RunCalDatabase.getInstance(context).scheduledReminderDao().deleteByKey(snoozeKey(eventId, occurrenceBeginMillis, reminderMinutes))
    }

    /**
     * 재부팅 등으로 시스템 알람만 사라진 스누즈를 복원한다. 아직 안 지났으면 다시 걸고, 지난 지 얼마 안 됐으면
     * (재부팅 중에 울렸어야 할 것) 즉시 발송, 오래됐으면 이미 의미가 없으니 폐기한다.
     */
    private suspend fun restoreSnoozes(context: Context, snoozes: List<ScheduledReminderEntity>) {
        val dao = RunCalDatabase.getInstance(context).scheduledReminderDao()
        val now = System.currentTimeMillis()
        snoozes.forEach { snooze ->
            when {
                snooze.triggerAtMillis > now -> if (!isAlarmRegistered(context, snooze)) scheduleAlarm(context, snooze)
                now - snooze.triggerAtMillis <= SNOOZE_GRACE_MILLIS -> {
                    context.sendBroadcast(alarmIntent(context, snooze))
                    dao.deleteByKey(snooze.key)
                }
                else -> {
                    cancelAlarm(context, snooze)
                    dao.deleteByKey(snooze.key)
                }
            }
        }
    }

    /** 알림 전체 끄기/권한 철회 시 전부 취소 — [resync]의 "끔" 분기와 별개로 명시적으로도 호출 가능. */
    suspend fun cancelAll(context: Context) = withContext(Dispatchers.IO) {
        val dao = RunCalDatabase.getInstance(context).scheduledReminderDao()
        dao.getAll().forEach { cancelAlarm(context, it) }
        dao.deleteAll()
    }

    private fun computeDesiredSchedule(context: Context): Map<String, ScheduledReminderEntity> {
        val resolver = context.contentResolver
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val windowEnd = LocalDate.now(zone).plusDays(SCAN_WINDOW_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, now)
            ContentUris.appendId(this, windowEnd)
        }.build()
        val projection = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END)

        // event_id -> 남은 채울 수 있는 회차 개수(회차별 begin 오름차순으로 앞에서부터 채운다).
        val occurrencesByEvent = LinkedHashMap<Long, MutableList<Pair<Long, Long>>>()
        resolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            val eventIdIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            while (cursor.moveToNext()) {
                if (cursor.getLong(beginIdx) <= now) continue // 이미 시작한 회차는 슬롯을 차지하지 않게 제외
                val eventId = cursor.getLong(eventIdIdx)
                val list = occurrencesByEvent.getOrPut(eventId) { mutableListOf() }
                if (list.size >= MAX_OCCURRENCES_PER_EVENT) continue
                list += cursor.getLong(beginIdx) to cursor.getLong(endIdx)
            }
        }
        if (occurrencesByEvent.isEmpty()) return emptyMap()

        val desired = LinkedHashMap<String, ScheduledReminderEntity>()
        occurrencesByEvent.forEach { (eventId, occurrences) ->
            val reminderMinutesList = queryReminderMinutes(resolver, eventId)
            if (reminderMinutesList.isEmpty()) return@forEach
            occurrences.forEach { (beginMillis, _) ->
                reminderMinutesList.forEach { minutes ->
                    val triggerAtMillis = beginMillis - minutes * 60_000L
                    if (triggerAtMillis <= now) return@forEach
                    val key = reminderKey(eventId, beginMillis, minutes)
                    desired[key] = ScheduledReminderEntity(key, eventId, beginMillis, minutes, triggerAtMillis)
                }
            }
        }
        return desired
    }

    private fun queryReminderMinutes(resolver: android.content.ContentResolver, eventId: Long): List<Int> {
        val result = mutableListOf<Int>()
        resolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { cursor ->
            val minutesIdx = cursor.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES)
            while (cursor.moveToNext()) result += cursor.getInt(minutesIdx)
        }
        return result.take(5)
    }

    private fun scheduleAlarm(context: Context, item: ScheduledReminderEntity) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAtMillis, alarmPendingIntent(context, item))
        } catch (e: SecurityException) {
            Log.e(TAG, "scheduleAlarm failed for ${item.key}", e)
        }
    }

    private fun cancelAlarm(context: Context, item: ScheduledReminderEntity) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(alarmPendingIntent(context, item))
    }

    // 재부팅/강제 종료로 시스템 쪽 알람만 사라지고 Room 장부는 남는 경우를 잡기 위한 실제 등록 여부 확인.
    private fun isAlarmRegistered(context: Context, item: ScheduledReminderEntity): Boolean =
        PendingIntent.getBroadcast(context, 0, alarmIntent(context, item), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null

    private fun alarmIntent(context: Context, item: ScheduledReminderEntity): Intent =
        Intent(context, ReminderAlarmReceiver::class.java).apply {
            val prefix = if (isSnoozeKey(item.key)) "runcal://reminder/snoozed" else "runcal://reminder"
            data = Uri.parse("$prefix/${item.eventId}/${item.occurrenceBeginMillis}/${item.reminderMinutes}")
            putExtra(EXTRA_EVENT_ID, item.eventId)
            putExtra(EXTRA_OCCURRENCE_BEGIN_MILLIS, item.occurrenceBeginMillis)
            putExtra(EXTRA_REMINDER_MINUTES, item.reminderMinutes)
        }

    private fun alarmPendingIntent(context: Context, item: ScheduledReminderEntity): PendingIntent =
        PendingIntent.getBroadcast(context, 0, alarmIntent(context, item), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

private fun reminderKey(eventId: Long, occurrenceBeginMillis: Long, reminderMinutes: Int): String =
    "$eventId:$occurrenceBeginMillis:$reminderMinutes"

private const val SNOOZE_KEY_PREFIX = "snooze:"

/** 재부팅 등으로 놓친 스누즈를 즉시 발송해 줄 최대 지연(이보다 오래되면 폐기). */
private const val SNOOZE_GRACE_MILLIS = 60 * 60_000L

private fun snoozeKey(eventId: Long, occurrenceBeginMillis: Long, reminderMinutes: Int): String =
    SNOOZE_KEY_PREFIX + reminderKey(eventId, occurrenceBeginMillis, reminderMinutes)

private fun isSnoozeKey(key: String): Boolean = key.startsWith(SNOOZE_KEY_PREFIX)

const val EXTRA_EVENT_ID = "com.jongsun.runcal.EXTRA_EVENT_ID"
const val EXTRA_OCCURRENCE_BEGIN_MILLIS = "com.jongsun.runcal.EXTRA_OCCURRENCE_BEGIN_MILLIS"
const val EXTRA_REMINDER_MINUTES = "com.jongsun.runcal.EXTRA_REMINDER_MINUTES"
