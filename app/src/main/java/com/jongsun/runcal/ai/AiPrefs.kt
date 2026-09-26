package com.jongsun.runcal.ai

import android.content.Context
import com.jongsun.runcal.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val MODEL_FLASH_LITE = "gemini-3.5-flash-lite"
const val MODEL_FLASH = "gemini-3.8-flash"
val AI_MODEL_OPTIONS = listOf(
    MODEL_FLASH_LITE to "빠름·기본 (분당 15회)",
    MODEL_FLASH to "정확도 우선 (분당 5회)",
)

/** AI 기능 설정. 기본값은 꺼짐 — 사용자가 켜야 동작한다. */
data class AiSettings(val enabled: Boolean = false, val model: String = MODEL_FLASH_LITE)

fun aiKeyPresent(): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

/** 대화 기록은 여기에 저장하지 않는다(메모리에만, 백업 대상 아님). 이 객체는 켜기/끄기·모델 선택만 보관한다. */
object AiPrefs {
    private const val NAME = "ai_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODEL = "model"

    private val _state = MutableStateFlow<AiSettings?>(null)

    fun state(context: Context): StateFlow<AiSettings> {
        ensureLoaded(context)
        @Suppress("UNCHECKED_CAST")
        return _state.asStateFlow() as StateFlow<AiSettings>
    }

    fun current(context: Context): AiSettings {
        ensureLoaded(context)
        return _state.value!!
    }

    /** 키가 있고 사용자가 켰을 때만 true — ✨ 아이콘/화면 노출 기준. */
    fun isAvailable(context: Context): Boolean = aiKeyPresent() && current(context).enabled

    fun setEnabled(context: Context, enabled: Boolean) {
        update(context) { it.copy(enabled = enabled) }
        syncAssistantShortcut(context)
    }
    fun setModel(context: Context, model: String) = update(context) { it.copy(model = model) }

    private fun update(context: Context, transform: (AiSettings) -> AiSettings) {
        val next = transform(current(context))
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putString(KEY_MODEL, next.model)
            .apply()
        _state.value = next
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (_state.value != null) return
        val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val model = prefs.getString(KEY_MODEL, MODEL_FLASH_LITE)?.takeIf { m -> AI_MODEL_OPTIONS.any { it.first == m } } ?: MODEL_FLASH_LITE
        _state.value = AiSettings(enabled = prefs.getBoolean(KEY_ENABLED, false), model = model)
    }
}
