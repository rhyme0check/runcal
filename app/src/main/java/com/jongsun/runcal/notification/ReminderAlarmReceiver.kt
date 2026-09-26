package com.jongsun.runcal.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jongsun.runcal.MainActivity
import com.jongsun.runcal.R
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "RunCal"

/**
 * AlarmManager가 정확한 시각에 깨우는 리시버. 스케줄 시점이 아니라 "지금" 이벤트 제목/장소를 다시
 * 읽는다 — 알람을 건 뒤 사용자가 제목을 바꿨을 수도 있기 때문이다. goAsync()로 ContentResolver
 * 조회가 끝날 때까지 프로세스가 죽지 않게 보장한다.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val occurrenceBeginMillis = intent.getLongExtra(EXTRA_OCCURRENCE_BEGIN_MILLIS, -1L)
        val reminderMinutes = intent.getIntExtra(EXTRA_REMINDER_MINUTES, 0)
        if (eventId <= 0 || occurrenceBeginMillis <= 0) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                showNotification(context, eventId, occurrenceBeginMillis, reminderMinutes)
            } catch (e: Exception) {
                Log.e(TAG, "ReminderAlarmReceiver: failed to show notification for event=$eventId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    @SuppressLint("MissingPermission") // hasPostNotificationPermissionSafe()로 직접 확인 후에만 notify() 호출
    private fun showNotification(context: Context, eventId: Long, occurrenceBeginMillis: Long, reminderMinutes: Int) {
        val detail = queryEventDetail(context, eventId) ?: return
        ensureReminderNotificationChannel(context)

        val zone = ZoneId.systemDefault()
        val timeLabel = if (detail.allDay) {
            "종일"
        } else {
            Instant.ofEpochMilli(occurrenceBeginMillis).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
        }
        val bodyParts = listOfNotNull(timeLabel, detail.location.takeIf { it.isNotBlank() })
        val notificationId = notificationIdFor(eventId, occurrenceBeginMillis, reminderMinutes)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("runcal://reminder/open/$eventId/$occurrenceBeginMillis")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TARGET_EVENT_ID, eventId)
            putExtra(MainActivity.EXTRA_TARGET_DATE_EPOCH_DAY, Instant.ofEpochMilli(occurrenceBeginMillis).atZone(zone).toLocalDate().toEpochDay())
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(detail.title.ifBlank { "(제목 없음)" })
            .setContentText(bodyParts.joinToString(" · "))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(snoozeAction(context, eventId, occurrenceBeginMillis, reminderMinutes, notificationId, 5))
            .addAction(snoozeAction(context, eventId, occurrenceBeginMillis, reminderMinutes, notificationId, 10))
            .addAction(snoozeAction(context, eventId, occurrenceBeginMillis, reminderMinutes, notificationId, 30))
            .build()

        if (!hasPostNotificationPermissionSafe(context)) return
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun snoozeAction(
        context: Context,
        eventId: Long,
        occurrenceBeginMillis: Long,
        reminderMinutes: Int,
        notificationId: Int,
        snoozeMinutes: Int,
    ): NotificationCompat.Action {
        val intent = Intent(context, ReminderSnoozeReceiver::class.java).apply {
            data = Uri.parse("runcal://reminder/snooze/$eventId/$occurrenceBeginMillis/$reminderMinutes/$snoozeMinutes")
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_OCCURRENCE_BEGIN_MILLIS, occurrenceBeginMillis)
            putExtra(EXTRA_REMINDER_MINUTES, reminderMinutes)
            putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 100 + snoozeMinutes,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, "${snoozeMinutes}분 후 다시", pendingIntent).build()
    }

    private data class EventDetail(val title: String, val location: String, val allDay: Boolean)

    private fun queryEventDetail(context: Context, eventId: Long): EventDetail? {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val projection = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.ALL_DAY)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            return EventDetail(
                title = cursor.getString(0).orEmpty(),
                location = cursor.getString(1).orEmpty(),
                allDay = cursor.getInt(2) != 0,
            )
        }
        return null
    }
}

internal fun notificationIdFor(eventId: Long, occurrenceBeginMillis: Long, reminderMinutes: Int): Int =
    "$eventId:$occurrenceBeginMillis:$reminderMinutes".hashCode()

internal const val EXTRA_SNOOZE_MINUTES = "com.jongsun.runcal.EXTRA_SNOOZE_MINUTES"
internal const val EXTRA_NOTIFICATION_ID = "com.jongsun.runcal.EXTRA_NOTIFICATION_ID"

internal fun hasPostNotificationPermissionSafe(context: Context): Boolean = try {
    hasNotificationPermission(context)
} catch (e: Exception) {
    false
}
