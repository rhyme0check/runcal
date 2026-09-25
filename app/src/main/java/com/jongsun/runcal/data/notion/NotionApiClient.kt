package com.jongsun.runcal.data.notion

import com.jongsun.runcal.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val NOTION_API_BASE = "https://api.notion.com/v1"
private const val NOTION_VERSION = "2022-06-28"

/** Notion API 호출 실패. [statusCode]가 401/403이면 통합(integration)이 해당 DB에 연결(공유)되지 않은 것. */
class NotionApiException(message: String, val statusCode: Int? = null) : Exception(message)

/**
 * Notion API 조회 전용 클라이언트. 쓰기 엔드포인트(PATCH/POST pages 등)는 이 클래스에
 * 존재하지 않는다 — 읽기 전용 원칙을 런타임 플래그가 아니라 구조적으로 지킨다.
 * 토큰은 Authorization 헤더에만 실려 나가고, 어떤 로그/예외 메시지에도 값 자체를 남기지 않는다.
 *
 * [client] 기본값은 프로세스 전체에서 공유하는 [sharedHttpClient]다 — 호출부마다
 * `NotionApiClient()`를 새로 만들어도 커넥션 풀(TCP/TLS 세션)은 재사용된다.
 * 실측 결과 스키마 조회(2364ms)가 페이지 조회(582ms, 데이터는 더 많음)보다 훨씬 느렸는데,
 * 이는 매번 새 OkHttpClient를 만들어 첫 요청마다 커넥션을 새로 맺었기 때문이었다 — 여러 DB를
 * 한 번에 동기화(syncAll)할 때나 다음 주기 동기화 때 이 비용을 반복하지 않으려면 클라이언트를
 * 공유해야 한다.
 */
class NotionApiClient(private val client: OkHttpClient = sharedHttpClient) {

    companion object {
        private val sharedHttpClient: OkHttpClient by lazy { OkHttpClient() }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun authorizedRequestBuilder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${BuildConfig.NOTION_API_TOKEN}")
            .addHeader("Notion-Version", NOTION_VERSION)

    suspend fun retrieveDatabase(databaseId: String): NotionDatabaseSchemaResponse = withContext(Dispatchers.IO) {
        val request = authorizedRequestBuilder("$NOTION_API_BASE/databases/$databaseId").get().build()
        json.decodeFromString(NotionDatabaseSchemaResponse.serializer(), execute(request))
    }

    suspend fun queryDatabase(
        databaseId: String,
        filter: JsonObject? = null,
        startCursor: String? = null,
        pageSize: Int = 100,
    ): NotionQueryResponse = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("page_size", pageSize)
            if (startCursor != null) put("start_cursor", startCursor)
            if (filter != null) put("filter", filter)
        }
        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = authorizedRequestBuilder("$NOTION_API_BASE/databases/$databaseId/query").post(requestBody).build()
        json.decodeFromString(NotionQueryResponse.serializer(), execute(request))
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // 요청/응답 헤더는 절대 로그·예외 메시지에 담지 않는다(Authorization 포함).
                throw NotionApiException("Notion API request failed with HTTP ${response.code}", response.code)
            }
            return body
        }
    }
}
