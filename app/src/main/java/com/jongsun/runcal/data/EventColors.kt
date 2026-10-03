package com.jongsun.runcal.data

import android.content.Context
import android.provider.CalendarContract
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import java.util.concurrent.ConcurrentHashMap

/** 구글 계정이 허용하는 일정 색 하나(Colors 테이블의 TYPE_EVENT 항목). [key]를 EVENT_COLOR_KEY로 저장한다. */
data class AccountEventColor(val key: String, @ColorInt val argb: Int)

private const val RECOMMENDED_COUNT = 10

/**
 * 추천색 후보. 흰/검정 글자가 모두 읽히는 중간 명도에서 색상환을 촘촘히(15°) 돌고, 채도·명도를 두 단계씩 섞었다.
 * 여기서 [recommendColors]가 기존 색들과 가장 멀리 떨어진 것부터 고른다.
 */
private val CANDIDATES: List<Int> = buildList {
    // 형광색(고채도·고명도)과 탁한 색을 피하려고 채도 0.55~0.65, 명도 0.40~0.52 안에서만 고른다.
    for (hue in 0 until 360 step 12) {
        for ((s, l) in listOf(0.62f to 0.47f, 0.55f to 0.40f, 0.65f to 0.52f)) {
            add(ColorUtils.HSLToColor(floatArrayOf(hue.toFloat(), s, l)))
        }
    }
}

private fun lab(@ColorInt argb: Int): DoubleArray = DoubleArray(3).also { ColorUtils.colorToLAB(argb, it) }

/** CIELAB 거리(ΔE76). 사람 눈이 느끼는 색 차이에 가깝다. */
fun colorDistance(@ColorInt a: Int, @ColorInt b: Int): Double = ColorUtils.distanceEuclidean(lab(a), lab(b))

/**
 * 추천색 고를 때 쓰는 거리. 밝기 차이는 덜 치고(0.4배) 색상·채도 차이를 주로 본다 — 같은 빨강 계열의 어두운 색(버건디)이
 * 공휴일 빨강과 "멀다"고 잡히지 않게 한다. 다크 모드에서는 어두운 색이 밝게 보정돼 실제로도 더 비슷해 보인다.
 */
private fun recommendDistance(@ColorInt a: Int, @ColorInt b: Int): Double {
    val x = lab(a)
    val y = lab(b)
    val dl = (x[0] - y[0]) * 0.4
    val da = x[1] - y[1]
    val db = x[2] - y[2]
    return kotlin.math.sqrt(dl * dl + da * da + db * db)
}

/**
 * [existing](이미 쓰이는 캘린더·Notion·유형 색)과 최대한 구분되는 추천색 [count]개. 기존 색과의 최소 거리가 가장 큰 후보를
 * 하나씩 고르고, 고른 색도 "기존"에 넣어 다음 후보를 고른다(최원점 탐색) — 추천색끼리도 서로 멀어진다.
 */
fun recommendColors(existing: Collection<Int>, count: Int = RECOMMENDED_COUNT): List<Int> {
    val taken = existing.map { it or 0xFF000000.toInt() }.toMutableList()
    val pool = CANDIDATES.toMutableList()
    val picked = ArrayList<Int>()
    while (picked.size < count && pool.isNotEmpty()) {
        val best = if (taken.isEmpty()) pool.first() else pool.maxBy { c -> taken.minOf { recommendDistance(c, it) } }
        picked += best
        taken += best
        pool.remove(best)
    }
    return picked
}

/** [colors] 중 [target]과 가장 가까운 것. 구글 캘린더처럼 정해진 색만 쓸 수 있을 때 자유색을 맞춘다. */
fun nearestColor(@ColorInt target: Int, colors: List<AccountEventColor>): AccountEventColor? =
    colors.minByOrNull { colorDistance(target, it.argb) }

/** 소스 색 스타일의 paletteKey에 자유색을 담는 형식: "#AARRGGBB". 기존 팔레트 이름(RED 등)과 섞여 저장된다. */
fun customColorKey(@ColorInt argb: Int): String = "#%08X".format(argb or 0xFF000000.toInt())

@ColorInt
fun parseCustomColorKey(key: String?): Int? =
    key?.takeIf { it.startsWith("#") && it.length == 9 }?.substring(1)?.toLongOrNull(16)?.toInt()

/** 이 캘린더가 허용하는 일정 색이 정해져 있는지(구글 계정 등 동기화 계정). 로컬 캘린더는 자유색. */
fun CalendarInfo.hasRestrictedEventColors(): Boolean = accountType != CalendarContract.ACCOUNT_TYPE_LOCAL

/**
 * 계정별 허용 일정 색(Colors 테이블). 동기화 어댑터가 채워 두는 값이라 자주 바뀌지 않아 프로세스 동안 캐시한다.
 * 비어 있으면(테이블 없음) 그 계정은 일정 색을 지정하지 않는다.
 */
object AccountEventColors {
    private val cache = ConcurrentHashMap<String, List<AccountEventColor>>()

    fun forAccount(context: Context, accountName: String, accountType: String): List<AccountEventColor> =
        cache.getOrPut("$accountType/$accountName") { query(context, accountName, accountType) }

    private fun query(context: Context, accountName: String, accountType: String): List<AccountEventColor> = try {
        val result = ArrayList<AccountEventColor>()
        context.contentResolver.query(
            CalendarContract.Colors.CONTENT_URI,
            arrayOf(CalendarContract.Colors.COLOR_KEY, CalendarContract.Colors.COLOR),
            "${CalendarContract.Colors.ACCOUNT_NAME}=? AND ${CalendarContract.Colors.ACCOUNT_TYPE}=? AND ${CalendarContract.Colors.COLOR_TYPE}=?",
            arrayOf(accountName, accountType, CalendarContract.Colors.TYPE_EVENT.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) result += AccountEventColor(c.getString(0), c.getInt(1))
        }
        result.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
    } catch (e: SecurityException) {
        emptyList()
    }
}

/** 일정 하나에 지정하는 색·유형. eventColor=null이면 캘린더 색을 따른다. */
data class EventStyleChoice(@ColorInt val eventColor: Int?, val typeId: String?)
