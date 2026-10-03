package com.jongsun.runcal.data.notion

import com.jongsun.runcal.BuildConfig
import com.jongsun.runcal.data.network.SharedHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val NOTION_API_BASE = "https://api.notion.com/v1"
private const val NOTION_VERSION = "2022-06-28"

/** Notion 권장 한도(평균 초당 3회)를 지키기 위한 요청 간 최소 간격. 앱 전체에서 공유한다. */
private const val MIN_REQUEST_INTERVAL_MS = 350L

/**
 * Notion 페이지 "속성 수정" 전용 클라이언트(P8). 할 수 있는 일은 두 가지뿐이다:
 * 페이지 하나 다시 읽기(GET /pages/{id})와 기존 페이지의 속성 값 바꾸기(PATCH /pages/{id}의 properties).
 * 페이지 생성·삭제(archived)·본문(블록) 수정 엔드포인트는 이 클래스에 존재하지 않는다 — 통합에도
 * "콘텐츠 업데이트" 권한만 주고 "콘텐츠 삽입"은 주지 않는 것을 전제로 한다.
 * 토큰은 Authorization 헤더에만 실리고 로그·예외 메시지에 남지 않는다.
 */
class NotionPageWriter(private val client: OkHttpClient = SharedHttpClient.instance) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun retrievePage(pageId: String): NotionPage = paced {
        val request = builder("$NOTION_API_BASE/pages/$pageId").get().build()
        json.decodeFromString(NotionPage.serializer(), execute(request))
    }

    /** [properties]는 속성 이름 → Notion 쓰기 형식 값. 요청 본문에는 properties 키 하나만 들어간다. */
    suspend fun updateProperties(pageId: String, properties: JsonObject): NotionPage = paced {
        val payload = buildJsonObject { put("properties", properties) }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = builder("$NOTION_API_BASE/pages/$pageId").patch(body).build()
        json.decodeFromString(NotionPage.serializer(), execute(request))
    }

    private fun builder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${BuildConfig.NOTION_API_TOKEN}")
            .addHeader("Notion-Version", NOTION_VERSION)

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Notion 오류 응답의 code/message만 남긴다(예: restricted_resource). 요청 헤더·토큰은 남기지 않는다.
                val detail = runCatching {
                    val obj = json.parseToJsonElement(body) as JsonObject
                    "${obj["code"]}: ${obj["message"]}"
                }.getOrDefault("")
                android.util.Log.w("RunCal", "NotionPageWriter: ${request.method} HTTP ${response.code} $detail")
                throw NotionApiException("Notion API request failed with HTTP ${response.code}", response.code)
            }
            return body
        }
    }

    private suspend fun <T> paced(block: () -> T): T = gate.withLock {
        val wait = lastRequestAt + MIN_REQUEST_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private companion object {
        val gate = Mutex()

        @Volatile var lastRequestAt = 0L
    }
}
