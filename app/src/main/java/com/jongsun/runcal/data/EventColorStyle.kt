package com.jongsun.runcal.data

import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.jongsun.runcal.data.room.EventColorStyleEntity
import com.jongsun.runcal.data.source.EventSourceKind

/**
 * 소스(캘린더/Notion DB)별 색상 팔레트. 라이트/다크 모드마다 별도 색상값을 갖는다.
 * 이름은 Room에 [EventColorStyleEntity.paletteKey]로 저장된다.
 */
enum class EventColorPaletteKey(val displayName: String, @ColorInt val lightArgb: Int, @ColorInt val darkArgb: Int) {
    RED("레드", 0xFFD93025.toInt(), 0xFFF28B82.toInt()),
    ORANGE("오렌지", 0xFFE8710A.toInt(), 0xFFFCAD70.toInt()),
    AMBER("앰버", 0xFFB8860B.toInt(), 0xFFFDD663.toInt()),
    GREEN("그린", 0xFF188038.toInt(), 0xFF81C995.toInt()),
    TEAL("틸", 0xFF12857A.toInt(), 0xFF78D9CE.toInt()),
    BLUE("블루", 0xFF1A73E8.toInt(), 0xFF8AB4F8.toInt()),
    INDIGO("인디고", 0xFF3F51B5.toInt(), 0xFF9FA8DA.toInt()),
    PURPLE("퍼플", 0xFF8430CE.toInt(), 0xFFC58AF9.toInt()),
    PINK("핑크", 0xFFC5176F.toInt(), 0xFFFF8BCB.toInt()),
    BROWN("브라운", 0xFF795548.toInt(), 0xFFBCAAA4.toInt()),
    ;

    @ColorInt
    fun argbFor(isDarkTheme: Boolean): Int = if (isDarkTheme) darkArgb else lightArgb
}

/** 화면에 실제로 그릴 값 — 배경(막대/점), 그 위에 얹을 텍스트 색, 제목 굵기. */
data class ResolvedEventColor(@ColorInt val backgroundArgb: Int, @ColorInt val textArgb: Int, val bold: Boolean)

/** [EventColorStyleEntity.sourceKey] 형식 — 캘린더는 캘린더 id, Notion은 등록(내부 UUID) id 기준. */
fun sourceKeyFor(event: EventItem): String = when (event.sourceKind) {
    EventSourceKind.CALENDAR -> "calendar:${event.calendarId}"
    EventSourceKind.NOTION -> "notion:${event.notionDatabaseId}"
}

fun sourceKeyForCalendar(calendarId: Long): String = "calendar:$calendarId"

fun sourceKeyForNotionDatabase(registrationId: String): String = "notion:$registrationId"

/**
 * "시스템 기본"(팔레트 오버라이드 없음)일 때 원본 캘린더/Notion 색을 다크 모드에서 자동으로
 * 보정한다. 라이트 모드는 원본 그대로 쓰고, 다크 모드는 배경(#1C1B1F)에 묻히지 않도록 명도를
 * 최소 0.62까지 끌어올린다.
 */
@ColorInt
fun adjustColorForTheme(@ColorInt argb: Int, isDarkTheme: Boolean): Int {
    if (!isDarkTheme) return argb
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(argb, hsl)
    val boostedLightness = hsl[2].coerceAtLeast(0.62f)
    return ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], boostedLightness))
}

/** 배경색 밝기에 따라 흰색/검정 중 대비가 더 큰 텍스트 색을 고른다(막대 위 제목 텍스트용). */
@ColorInt
fun contrastingTextColorArgb(@ColorInt backgroundColor: Int): Int {
    val luminance = ColorUtils.calculateLuminance(backgroundColor)
    return if (luminance > 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
}

/** [rawColor]에 소스별 스타일 오버라이드([style], 없으면 null=시스템 기본)를 적용한 배경색. */
@ColorInt
fun resolveBackgroundColor(@ColorInt rawColor: Int, style: EventColorStyleEntity?, isDarkTheme: Boolean): Int {
    val paletteKey = style?.paletteKey?.let { key -> runCatching { EventColorPaletteKey.valueOf(key) }.getOrNull() }
    return paletteKey?.argbFor(isDarkTheme) ?: adjustColorForTheme(rawColor, isDarkTheme)
}

/** [event]를 [styleMap](sourceKey → 스타일)과 현재 테마에 따라 실제로 그릴 색/굵기로 해석한다. */
fun resolveEventColor(
    event: EventItem,
    styleMap: Map<String, EventColorStyleEntity>,
    isDarkTheme: Boolean,
): ResolvedEventColor {
    val style = styleMap[sourceKeyFor(event)]
    val background = resolveBackgroundColor(event.color, style, isDarkTheme)
    return ResolvedEventColor(background, contrastingTextColorArgb(background), style?.bold ?: false)
}
