package com.jongsun.runcal.data.backup.drive

import com.jongsun.runcal.data.network.SharedHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
private const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"

class DriveApiException(message: String, val statusCode: Int? = null) : Exception(message)

/**
 * Drive v3 REST 중 appDataFolder 범위의 4개 엔드포인트만 다룬다(files.list/create/get/delete).
 * 공식 Drive 클라이언트 라이브러리(com.google.api.services.drive)는 쓰지 않는다 — Guava/Apache
 * HttpClient 전이 의존성만 늘어나고, REST 4개 호출엔 OkHttp만으로 충분하다. [client]는 Notion과
 * 같은 [SharedHttpClient.instance]를 공유해 커넥션 풀을 재사용한다.
 */
class DriveApiClient(private val client: OkHttpClient = SharedHttpClient.instance) {

    private val json = Json { ignoreUnknownKeys = true }

    /** appDataFolder 안의 파일 목록. [pageToken]이 있으면 다음 페이지를 이어 받는다. */
    suspend fun listAppDataFiles(accessToken: String, pageToken: String? = null): DriveFileListResponse =
        withContext(Dispatchers.IO) {
            val urlBuilder = StringBuilder("$DRIVE_API_BASE/files?spaces=appDataFolder")
                .append("&fields=files(id,name,modifiedTime,size),nextPageToken")
                .append("&pageSize=100")
            if (pageToken != null) urlBuilder.append("&pageToken=").append(pageToken)
            val request = authorizedRequestBuilder(accessToken, urlBuilder.toString()).get().build()
            json.decodeFromString(DriveFileListResponse.serializer(), execute(request))
        }

    /** [fileName]으로 appDataFolder에 새 파일을 만들고 [content]를 올린다. 반환값은 새 파일의 Drive id. */
    suspend fun createAppDataFile(accessToken: String, fileName: String, content: String): String =
        withContext(Dispatchers.IO) {
            val metadata = buildJsonObject {
                put("name", fileName)
                putJsonArray("parents") { add("appDataFolder") }
            }.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
            val contentBody = content.toRequestBody("application/json".toMediaType())
            // Drive API의 멀티파트 업로드는 반드시 multipart/related여야 한다(mixed 아님) —
            // 메타데이터(JSON)와 실제 파일 내용을 한 요청에 담아 보낸다.
            val multipartBody = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metadata)
                .addPart(contentBody)
                .build()
            val request = authorizedRequestBuilder(accessToken, "$DRIVE_UPLOAD_BASE/files?uploadType=multipart")
                .post(multipartBody)
                .build()
            val body = execute(request)
            json.decodeFromString(DriveFile.serializer(), body).id
        }

    /** [fileId]의 실제 내용(백업 JSON)을 문자열로 받는다. */
    suspend fun getAppDataFileContent(accessToken: String, fileId: String): String = withContext(Dispatchers.IO) {
        val request = authorizedRequestBuilder(accessToken, "$DRIVE_API_BASE/files/$fileId?alt=media").get().build()
        execute(request)
    }

    suspend fun deleteAppDataFile(accessToken: String, fileId: String) = withContext(Dispatchers.IO) {
        val request = authorizedRequestBuilder(accessToken, "$DRIVE_API_BASE/files/$fileId").delete().build()
        execute(request)
        Unit
    }

    private fun authorizedRequestBuilder(accessToken: String, url: String): Request.Builder =
        Request.Builder().url(url).addHeader("Authorization", "Bearer $accessToken")

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // 액세스 토큰 등 인증 정보는 절대 로그·예외 메시지에 담지 않는다.
                throw DriveApiException("Drive API request failed with HTTP ${response.code}", response.code)
            }
            return body
        }
    }
}
