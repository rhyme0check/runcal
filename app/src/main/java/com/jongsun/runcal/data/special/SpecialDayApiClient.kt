package com.jongsun.runcal.data.special

import com.jongsun.runcal.BuildConfig
import com.jongsun.runcal.data.network.SharedHttpClient
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private const val BASE = "https://apis.data.go.kr/B090041/openapi/service"
private val LOCDATE_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

data class SpecialDayItem(val date: LocalDate, val name: String, val isHoliday: Boolean)
data class LunarDayItem(val date: LocalDate, val month: Int, val day: Int, val isLeap: Boolean)

/**
 * 공공데이터포털 한국천문연구원 특일 정보/음양력 정보 API. 블로킹 호출이므로 반드시 백그라운드(WorkManager)에서만
 * 쓴다 — 위젯/앱 렌더링 경로는 Room 캐시만 읽는다.
 */
class SpecialDayApiClient(private val client: OkHttpClient = SharedHttpClient.instance) {

    /** 그 해의 공휴일(대체공휴일·선거일 포함) 전체. */
    fun fetchHolidays(year: Int): List<SpecialDayItem> =
        fetchSpecial("SpcdeInfoService/getRestDeInfo", year)

    /** 그 해의 24절기. */
    fun fetchSolarTerms(year: Int): List<SpecialDayItem> =
        fetchSpecial("SpcdeInfoService/get24DivisionsInfo", year)

    /** 그 달 각 날짜의 음력 변환. */
    fun fetchLunarMonth(yearMonth: YearMonth): List<LunarDayItem> {
        val url = urlFor("LrsrCldInfoService/getLunCalInfo")
            .addQueryParameter("solYear", yearMonth.year.toString())
            .addQueryParameter("solMonth", "%02d".format(yearMonth.monthValue))
            .addQueryParameter("numOfRows", "40")
            .build()
        return items(url).mapNotNull { item ->
            val date = LocalDate.of(
                item.int("solYear") ?: return@mapNotNull null,
                item.int("solMonth") ?: return@mapNotNull null,
                item.int("solDay") ?: return@mapNotNull null,
            )
            LunarDayItem(
                date = date,
                month = item.int("lunMonth") ?: return@mapNotNull null,
                day = item.int("lunDay") ?: return@mapNotNull null,
                isLeap = item.string("lunLeapmonth") == "윤",
            )
        }
    }

    private fun fetchSpecial(path: String, year: Int): List<SpecialDayItem> {
        val url = urlFor(path)
            .addQueryParameter("solYear", year.toString())
            .addQueryParameter("numOfRows", "100")
            .build()
        return items(url).mapNotNull { item ->
            val locdate = item.string("locdate") ?: return@mapNotNull null
            SpecialDayItem(
                date = runCatching { LocalDate.parse(locdate, LOCDATE_FORMAT) }.getOrNull() ?: return@mapNotNull null,
                name = item.string("dateName") ?: return@mapNotNull null,
                isHoliday = item.string("isHoliday") == "Y",
            )
        }
    }

    private fun urlFor(path: String): HttpUrl.Builder {
        val key = BuildConfig.DATA_GO_KR_API_KEY
        if (key.isBlank()) throw IllegalStateException("DATA_GO_KR_API_KEY가 local.properties에 없습니다")
        return "$BASE/$path".toHttpUrl().newBuilder()
            .addQueryParameter("serviceKey", key)
            .addQueryParameter("_type", "json")
    }

    /**
     * 인증키가 URL 쿼리에 실리므로, 어떤 예외가 URL(=키)을 메시지에 담아 로그로 새지 않게 여기서 한 번 걸러
     * 예외 종류만 남긴다. 이 클래스는 요청 URL/응답 본문을 로그에 남기지 않는다.
     */
    private fun items(url: HttpUrl): List<JsonObject> = try {
        fetchItems(url)
    } catch (e: IllegalStateException) {
        throw e // 키 미설정 안내 메시지(값 없음)
    } catch (e: IOException) {
        // 우리가 만든 메시지("data.go.kr ...")는 키를 담지 않는다. 그 외 소켓/DNS 예외는 종류만 남긴다.
        throw IOException(if (e.message?.startsWith("data.go.kr") == true) e.message else "data.go.kr 요청 실패(${e::class.java.simpleName})")
    } catch (e: Exception) {
        throw IOException("data.go.kr 응답 처리 실패(${e::class.java.simpleName})")
    }

    private fun fetchItems(url: HttpUrl): List<JsonObject> {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("data.go.kr HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val root = Json.parseToJsonElement(body) as? JsonObject ?: throw IOException("data.go.kr: 예상 밖 응답")
            val resp = root["response"] as? JsonObject ?: throw IOException("data.go.kr: response 없음")
            val code = ((resp["header"] as? JsonObject)?.get("resultCode") as? JsonPrimitive)?.contentOrNull
            if (code != "00") throw IOException("data.go.kr resultCode=$code")
            // 결과가 0건이면 items가 객체가 아니라 빈 문자열("")로 온다.
            val itemsObj = (resp["body"] as? JsonObject)?.get("items") as? JsonObject ?: return emptyList()
            return when (val item: JsonElement? = itemsObj["item"]) {
                is JsonArray -> item.filterIsInstance<JsonObject>()
                is JsonObject -> listOf(item)
                else -> emptyList()
            }
        }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
}
