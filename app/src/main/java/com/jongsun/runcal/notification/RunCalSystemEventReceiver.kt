package com.jongsun.runcal.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jongsun.runcal.work.WorkScheduler

/**
 * 재부팅/시간대 변경/날짜 변경/수동 시각 변경에 반응해 알람을 다시 계산한다. 재부팅은 걸어둔
 * 알람 자체가 시스템에서 전부 사라지므로 반드시 다시 걸어야 하고, 시간대·날짜·시각 변경은 이미
 * 걸어둔 알람의 트리거 시각(RTC_WAKEUP, 절대 시각 기준)이 사용자가 보는 "몇 시"와 어긋날 수
 * 있어 다시 계산해야 한다.
 */
class RunCalSystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_PROVIDER_CHANGED,
            -> WorkScheduler.triggerReminderResyncNow(context)
        }
    }
}
