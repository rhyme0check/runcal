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
