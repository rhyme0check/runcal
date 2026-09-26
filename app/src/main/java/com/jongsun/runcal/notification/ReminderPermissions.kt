package com.jongsun.runcal.notification

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** API 33 미만은 알림 권한 자체가 없어 항상 "허용됨"으로 취급한다. */
fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/**
 * Android 12(S) 미만은 정확한 알람이 기본 허용이라 체크가 필요 없다. 12 이상은
 * [AlarmManager.canScheduleExactAlarms]로 실제 허용 여부를 확인해야 한다 — 특히 14 이상은
 * 사용자가 설정에서 직접 켜지 않으면 기본이 거부라, 이걸 무시하면 알림이 "조용히" 안 울린다.
 */
fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
    return alarmManager.canScheduleExactAlarms()
}

/** 설정 앱의 이 앱 전용 알림 설정 화면으로 이동한다(알림 권한이 거부된 경우 안내용). */
fun notificationSettingsIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }

/** 설정 앱의 "정확한 알람" 허용 화면으로 이동한다. */
fun exactAlarmSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }

/** 배터리 최적화 예외 대상인지(One UI의 "절전 예외 앱" 포함) — 꺼져 있으면 Doze 중 알람이 늦게 울릴 수 있다. */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

/** 배터리 최적화 예외를 바로 요청하는 시스템 다이얼로그 — 제조사 커스텀 절전 설정까지는 열어주지 못한다. */
fun ignoreBatteryOptimizationsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
