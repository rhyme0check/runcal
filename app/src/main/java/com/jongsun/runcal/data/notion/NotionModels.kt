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
)

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
