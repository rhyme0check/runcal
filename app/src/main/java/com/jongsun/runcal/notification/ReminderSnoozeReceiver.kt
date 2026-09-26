package com.jongsun.runcal.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 알림의 "N분 후 다시" 액션. 정식 스케줄은 건드리지 않고, 지금 뜬 알림을 지운 뒤 지금+N분에 한 번
 * 울릴 스누즈 알람을 건다. 스누즈는 Room 장부에 기록되어 재부팅 후에도 복원된다(ReminderScheduler.resync).
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

        if (!canScheduleExactAlarms(context)) return
        val triggerAtMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(snoozeMinutes.toLong())

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ReminderScheduler.scheduleSnooze(context, eventId, occurrenceBeginMillis, reminderMinutes, triggerAtMillis)
            } catch (e: Exception) {
                Log.e("RunCal", "ReminderSnoozeReceiver: failed to schedule snooze for event=$eventId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
