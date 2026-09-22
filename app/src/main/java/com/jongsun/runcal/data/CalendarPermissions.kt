package com.jongsun.runcal.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

val CALENDAR_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
)

fun hasCalendarPermissions(context: Context): Boolean =
    CALENDAR_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

fun hasCalendarReadPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

fun hasCalendarWritePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
