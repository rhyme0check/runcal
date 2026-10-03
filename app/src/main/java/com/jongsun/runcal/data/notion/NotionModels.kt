package com.jongsun.runcal.data.notion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** GET /v1/databases/{id} 응답 중 이 앱이 쓰는 부분만. 읽기 전용. */
@Serializable
data class NotionDatabaseSchemaResponse(
    val id: String,
    val title: List<NotionRichText> = emptyList(),
    val properties: Map<String, NotionPropertySchema> = emptyMap(),
) {
    val titleText: String get() = title.joinToString("") { it.plainText }
}

@Serializable
data class NotionPropertySchema(
    val id: String,
    val name: String,
    val type: String,
    // 상태/선택 속성의 옵션 목록(P8: 앱에서 상태를 바꿀 때 고를 수 있는 값). 다른 타입이면 null.
    val status: NotionOptionList? = null,
    val select: NotionOptionList? = null,
) {
    val optionNames: List<String> get() = (status ?: select)?.options?.map { it.name }.orEmpty()
}

@Serializable
data class NotionOptionList(val options: List<NotionOption> = emptyList())

@Serializable
data class NotionOption(val name: String)

/** [com.jongsun.runcal.data.room.NotionDatabaseEntity.schemaJson]에 저장하는 스키마 요약. */
@Serializable
data class NotionSchemaSummary(
    /** 매핑된 상태 속성의 옵션 이름들(Notion 순서 그대로). */
    val statusOptions: List<String> = emptyList(),
    /** 매핑된 상태 속성의 타입("status" | "select"). */
    val statusType: String? = null,
    /** 매핑된 날짜 속성 이름이 현재 스키마에서 date 타입인지. */
    val dateIsDate: Boolean = true,
)

private val summaryJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

fun parseSchemaSummary(raw: String?): NotionSchemaSummary =
    raw?.takeIf { it.startsWith("{") }?.let { runCatching { summaryJson.decodeFromString(NotionSchemaSummary.serializer(), it) }.getOrNull() }
        ?: NotionSchemaSummary()

fun NotionSchemaSummary.encode(): String = summaryJson.encodeToString(NotionSchemaSummary.serializer(), this)

@Serializable
data class NotionRichText(
    @SerialName("plain_text") val plainText: String = "",
)

/** POST /v1/databases/{id}/query 응답. */
@Serializable
data class NotionQueryResponse(
    val results: List<NotionPage> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/**
 * 페이지(=행) 하나. [properties]는 속성별 원본 JSON을 그대로 들고 있다가
 * [NotionPropertyMapper]가 매핑된 속성 이름으로 필요한 값만 꺼내 쓴다 — Notion 속성 타입이
 * DB마다 제각각이라 미리 고정된 스키마로 파싱할 수 없기 때문이다.
 */
@Serializable
data class NotionPage(
    val id: String,
    val url: String = "",
    @SerialName("last_edited_time") val lastEditedTime: String = "",
    val properties: Map<String, JsonObject> = emptyMap(),
)

/**
 * 사용자가 붙여넣은 Notion DB URL 또는 순수 ID에서 32자리 hex id를 뽑아 표준 UUID
 * 형식(8-4-4-4-12)으로 정규화한다. URL의 `?v=...`(뷰 id)는 붙어 있어도 무시된다 — hex 32자를
 * 못 찾으면 null.
 */
fun parseNotionDatabaseId(input: String): String? {
    val withoutQuery = input.substringBefore("?").replace("-", "")
    val hex = Regex("[0-9a-fA-F]{32}").find(withoutQuery)?.value ?: return null
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}
