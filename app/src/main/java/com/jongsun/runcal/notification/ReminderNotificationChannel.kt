package com.jongsun.runcal.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

const val REMINDER_CHANNEL_ID = "runcal_event_reminders"

/** "일정 알림" 채널 하나만 쓴다(중요도 HIGH — 헤드업 팝업으로 뜨게). 이미 있으면 아무 일도 안 한다. */
fun ensureReminderNotificationChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(REMINDER_CHANNEL_ID) != null) return
    val channel = NotificationChannel(
        REMINDER_CHANNEL_ID,
        "일정 알림",
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = "일정 시작 전에 설정한 시간에 알려줍니다."
        enableVibration(true)
    }
    manager.createNotificationChannel(channel)
}
