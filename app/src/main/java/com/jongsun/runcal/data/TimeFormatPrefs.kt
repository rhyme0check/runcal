package com.jongsun.runcal.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 시각 표기(24시간/12시간). 기본은 24시간. 위젯·알림도 바로 읽어야 해서 SharedPreferences에 두고, 화면이 값 변경을
 * 바로 다시 그리도록 Compose 상태로도 들고 있는다. 앱 시작 때 [load]한다.
 */
object TimeFormatPrefs {
    private const val PREFS = "display_prefs"
    private const val KEY_24H = "use_24h"

    var use24h by mutableStateOf(true)
        private set

    fun load(context: Context) {
        use24h = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_24H, true)
    }

    fun set(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_24H, value).apply()
        use24h = value
    }
}

/** 설정에 따른 시각 문자열. 24시간 "07:30", 12시간 "오전 7:30"·"오후 12:05". */
fun formatClock(hour: Int, minute: Int, use24h: Boolean = TimeFormatPrefs.use24h): String =
    if (use24h) {
        "%02d:%02d".format(hour, minute)
    } else {
        val period = if (hour < 12) "오전" else "오후"
        val h12 = (hour % 12).let { if (it == 0) 12 else it }
        "$period $h12:%02d".format(minute)
    }
