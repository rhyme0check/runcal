package com.jongsun.runcal.widget

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.jongsun.runcal.R
import com.jongsun.runcal.data.contrastingTextColorArgb

private val DAY_BASE_BACKGROUND_ARGB = 0xFFFFFFFF.toInt()
private val NIGHT_BASE_BACKGROUND_ARGB = 0xFF1C1B1F.toInt()

/** 위젯은 Compose가 아니라 RemoteViews라 isSystemInDarkTheme()를 못 쓰므로 Configuration을 직접 본다. */
fun isDarkMode(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** 시스템 다크/라이트 모드 기준 배경 베이스 색상에 [opacity](0~1)를 적용한 배경색을 만든다. */
@ColorInt
fun resolveBackgroundColorInt(context: Context, opacity: Float): Int {
    val base = if (isDarkMode(context)) NIGHT_BASE_BACKGROUND_ARGB else DAY_BASE_BACKGROUND_ARGB
    val alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
    return ColorUtils.setAlphaComponent(base, alpha)
}

@ColorInt
fun colorRes(context: Context, resId: Int): Int = ContextCompat.getColor(context, resId)

object RunCalWidgetColorRes {
    fun onBackground(context: Context) = colorRes(context, R.color.runcal_widget_on_background)
    fun onBackgroundDim(context: Context) = colorRes(context, R.color.runcal_widget_on_background_dim)
    fun sunday(context: Context) = colorRes(context, R.color.runcal_widget_sunday)
    fun sundayDim(context: Context) = colorRes(context, R.color.runcal_widget_sunday_dim)
    fun saturday(context: Context) = colorRes(context, R.color.runcal_widget_saturday)
    fun saturdayDim(context: Context) = colorRes(context, R.color.runcal_widget_saturday_dim)
    fun todayBackground(context: Context) = colorRes(context, R.color.runcal_widget_today_background)
    fun onTodayBackground(context: Context) = colorRes(context, R.color.runcal_widget_on_today_background)
    fun scheduleText(context: Context) = colorRes(context, R.color.runcal_widget_schedule_text)
    fun scheduleTextDim(context: Context) = colorRes(context, R.color.runcal_widget_schedule_text_dim)

    /** 헤더 2단에서 이번 달이 아닌 달을 보고 있을 때 연월 텍스트를 강조하는 색상. */
    fun accent(context: Context) = sunday(context)
}

/** 배경색 밝기에 따라 흰색/검정 중 대비가 더 큰 텍스트 색을 고른다(막대 위 제목 텍스트용). */
@ColorInt
fun contrastingTextColor(@ColorInt backgroundColor: Int): Int = contrastingTextColorArgb(backgroundColor)
