package com.jongsun.runcal.ai

import android.util.Log
import com.jongsun.runcal.BuildConfig
import com.jongsun.runcal.data.network.SharedHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "RunCal"
private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

/** 사용자에게 그대로 보여줄 수 있는 문구를 가진 오류. 요청/응답 본문이나 키는 메시지에 절대 담지 않는다. */
class AssistantException(val userMessage: String) : Exception(userMessage)

data class FunctionCall(val name: String, val args: JsonObject, val id: String?)

/**
 * [modelContent]는 응답의 model 턴 원문({"role":"model","parts":[...]}). thoughtSignature가 들어 있어
 * 다음 요청의 히스토리에 그대로 되돌려 보내야 한다.
 */
data class GeminiResponse(val modelContent: JsonObject, val text: String, val calls: List<FunctionCall>)

/**
 * Gemini generateContent REST 클라이언트. 인증은 x-goog-api-key 헤더(URL에 키가 없다)이고, 요청/응답
 * 본문은 어떤 경우에도 로그에 남기지 않는다(일정 정보·키 보호). 오류는 [AssistantException]으로 정리해 던진다.
 */
class GeminiClient {
    private val http = SharedHttpClient.instance.newBuilder().readTimeout(40, TimeUnit.SECONDS).callTimeout(50, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generate(model: String, systemInstruction: String, contents: List<JsonObject>, tools: JsonArray): GeminiResponse =
        withContext(Dispatchers.IO) {
            if (BuildConfig.GEMINI_API_KEY.isBlank()) {
                throw AssistantException("Gemini API 키가 없습니다. local.properties의 GEMINI_API_KEY를 확인하세요.")
            }
            val body = buildJsonObject {
                putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", systemInstruction) }) } }
                put("contents", JsonArray(contents))
                put("tools", tools)
                putJsonObject("toolConfig") { putJsonObject("functionCallingConfig") { put("mode", "AUTO") } }
            }.toString()

            var attempt = 0
            while (true) {
                attempt++
                val (code, text) = post(model, body)
                Log.d(TAG, "GeminiClient: model=$model HTTP $code")
                when {
                    code == 200 -> return@withContext parse(text)
                    (code == 503 || code == 500) && attempt < 2 -> delay(1500)
                    else -> throw errorFor(code, text)
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    private fun post(model: String, body: String): Pair<Int, String> {
        val request = Request.Builder()
            .url("$ENDPOINT/$model:generateContent")
            .addHeader("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { return it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            // 예외 종류만 남긴다(메시지에는 요청 정보가 섞일 수 있어 남기지 않는다).
            Log.w(TAG, "GeminiClient: network error ${e::class.java.simpleName}")
            throw AssistantException("인터넷에 연결할 수 없거나 응답이 늦습니다. 연결을 확인하고 다시 시도하세요.")
        }
    }

    private fun parse(text: String): GeminiResponse {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw AssistantException("모델 응답을 해석하지 못했습니다.")
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw AssistantException("모델이 응답하지 못했습니다(안전 필터 등). 표현을 바꿔 다시 시도하세요.")
        val content = candidate["content"]?.jsonObject
            ?: throw AssistantException("모델이 빈 응답을 돌려줬습니다. 다시 시도하세요.")
        val parts = content["parts"]?.jsonArray ?: JsonArray(emptyList())
        val calls = ArrayList<FunctionCall>()
        val sb = StringBuilder()
        for (part in parts) {
            val obj = part as? JsonObject ?: continue
            val fc = obj["functionCall"] as? JsonObject
            if (fc != null) {
                calls += FunctionCall(
                    name = (fc["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    args = fc["args"] as? JsonObject ?: JsonObject(emptyMap()),
                    id = (fc["id"] as? JsonPrimitive)?.contentOrNull,
                )
            } else if (obj["thought"]?.let { (it as? JsonPrimitive)?.contentOrNull } != "true") {
                (obj["text"] as? JsonPrimitive)?.contentOrNull?.let { sb.append(it) }
            }
        }
        val modelContent = buildJsonObject {
            put("role", "model")
            put("parts", parts)
        }
        return GeminiResponse(modelContent, sb.toString().trim(), calls)
    }

    private fun errorFor(code: Int, text: String): AssistantException = when (code) {
        429 -> {
            val info = runCatching { json.parseToJsonElement(text) }.getOrNull()
            val raw = info?.toString().orEmpty()
            val daily = raw.contains("PerDay", ignoreCase = true)
            val retrySeconds = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
            if (daily) {
                AssistantException("오늘 무료 사용량을 모두 썼습니다. 내일(미국 태평양 시간 자정 기준 초기화) 다시 시도하세요.")
            } else {
                AssistantException("요청이 너무 잦습니다. ${retrySeconds?.let { "약 ${it}초 뒤" } ?: "잠시 뒤"}에 다시 시도하세요.")
            }
        }
        400, 401, 403 -> {
            val raw = text.lowercase()
            if (raw.contains("api key") || raw.contains("api_key") || code == 401 || code == 403) {
                AssistantException("Gemini API 키가 올바르지 않거나 권한이 없습니다.")
            } else {
                AssistantException("요청을 처리하지 못했습니다(HTTP $code).")
            }
        }
        500, 503 -> AssistantException("Gemini 서버가 일시적으로 바쁩니다. 잠시 뒤에 다시 시도하세요.")
        else -> AssistantException("요청을 처리하지 못했습니다(HTTP $code).")
    }
}

internal fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
