package com.jongsun.runcal.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.TimeUnit

/**
 * 알림의 "N분 후 다시" 액션. 원래 스케줄(ReminderScheduler가 관리하는 정식 알람)은 건드리지 않고,
 * 지금 뜬 알림만 지운 뒤 지금+N분에 딱 한 번 울릴 별도의 1회성 알람을 건다 — 다음 재동기화 때
 * 사라져도 상관없는 임시 알람이라 Room 장부에는 기록하지 않는다.
 */
class ReminderSnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val occurrenceBeginMillis = intent.getLongExtra(EXTRA_OCCURRENCE_BEGIN_MILLIS, -1L)
        val reminderMinutes = intent.getIntExtra(EXTRA_REMINDER_MINUTES, 0)
        val snoozeMinutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 5)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (eventId <= 0 || occurrenceBeginMillis <= 0) return

        NotificationManagerCompat.from(context).cancel(notificationId)

        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        if (!canScheduleExactAlarms(context)) return
        val triggerAtMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(snoozeMinutes.toLong())
        val snoozeIntent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            data = Uri.parse("runcal://reminder/snoozed/$eventId/$occurrenceBeginMillis/$reminderMinutes/$snoozeMinutes")
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_OCCURRENCE_BEGIN_MILLIS, occurrenceBeginMillis)
            putExtra(EXTRA_REMINDER_MINUTES, reminderMinutes)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } catch (e: SecurityException) {
            // 권한이 그 사이 철회됐다면 조용히 무시 — 다음 정식 재동기화가 상태를 바로잡는다.
        }
    }
}
