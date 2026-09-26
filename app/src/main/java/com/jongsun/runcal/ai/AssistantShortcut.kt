package com.jongsun.runcal.ai

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.jongsun.runcal.MainActivity
import com.jongsun.runcal.R

private const val SHORTCUT_ID = "ai_command"

/**
 * 앱 아이콘 길게 누르기 바로가기("자연어 명령"). AI 기능이 켜져 있고 키가 있을 때만 등록하고, 끄면 제거한다
 * (꺼둔 상태에서 메뉴에 남아 있으면 눌러도 아무 일도 없어 혼란스럽다).
 */
fun syncAssistantShortcut(context: Context) {
    val app = context.applicationContext
    if (!AiPrefs.isAvailable(app)) {
        ShortcutManagerCompat.removeDynamicShortcuts(app, listOf(SHORTCUT_ID))
        return
    }
    val intent = Intent(app, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_OPEN_ASSISTANT, true)
    }
    val shortcut = ShortcutInfoCompat.Builder(app, SHORTCUT_ID)
        .setShortLabel("자연어 명령")
        .setLongLabel("AI 자연어 명령")
        .setIcon(IconCompat.createWithResource(app, R.drawable.ic_shortcut_ai))
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.setDynamicShortcuts(app, listOf(shortcut))
}
