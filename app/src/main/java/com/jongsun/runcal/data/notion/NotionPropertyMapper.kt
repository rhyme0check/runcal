package com.jongsun.runcal.data.notion

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Notion 페이지의 [NotionPage.properties](속성 이름 → 원본 JSON)에서, 등록 시 사용자가 지정한
 * 매핑(어떤 속성 이름이 날짜/제목/부제/상태인지)에 따라 값을 뽑아낸다.
 * DB마다 속성 이름/구성이 달라 고정 스키마로 파싱할 수 없으므로 매번 이름으로 찾아 읽는다.
 */
object NotionPropertyMapper {

    /** 날짜 속성에서 [시작, 종료(nullable)]를 ISO-8601 문자열 그대로 꺼낸다. 없으면 null. */
    fun extractDateRange(properties: Map<String, JsonObject>, dateProperty: String): Pair<String, String?>? {
        val dateObj = properties[dateProperty]?.get("date")?.jsonObject ?: return null
        val start = dateObj["start"]?.jsonPrimitive?.contentOrNull ?: return null
        val end = dateObj["end"]?.jsonPrimitive?.contentOrNull
        return start to end
    }

    /** title 타입 속성의 rich text 배열을 이어붙인 평문. */
    fun extractTitle(properties: Map<String, JsonObject>, titleProperty: String): String {
        val titleArray = properties[titleProperty]?.get("title")?.jsonArray ?: return ""
        return titleArray.joinToString("") { it.jsonObject["plain_text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    /** rich_text/select/status/number 중 어떤 타입이든 표시용 문자열 하나로 뽑는다(부제/상태 공용). */
    fun extractDisplayText(properties: Map<String, JsonObject>, propertyName: String?): String? {
        if (propertyName == null) return null
        val prop = properties[propertyName] ?: return null
        return when (prop["type"]?.jsonPrimitive?.contentOrNull) {
            "rich_text" -> prop["rich_text"]?.jsonArray
                ?.joinToString("") { it.jsonObject["plain_text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                ?.takeIf { it.isNotEmpty() }
            "select" -> prop["select"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            "status" -> prop["status"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            "number" -> prop["number"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }

    private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE
    private val ISO_DATE_TIME_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /**
     * Notion의 date.start/end 문자열(날짜만 있는 "2026-01-15" 또는 오프셋 포함
     * "2026-01-15T09:00:00.000+09:00")을 epoch millis [시작, 종료)로 정규화한다.
     * end가 없으면 allDay는 start 당일 하루, timed는 start와 같은 시각(0분짜리)으로 둔다.
     */
    fun toEpochMillisRange(start: String, end: String?, zone: ZoneId): Triple<Long, Long, Boolean> {
        val allDay = start.length <= 10 // "yyyy-MM-dd"만 있으면 시간 정보 없는 날짜 전용 속성
        return if (allDay) {
            val startDate = LocalDate.parse(start, ISO_DATE)
            val endDate = end?.let { LocalDate.parse(it, ISO_DATE) } ?: startDate
            val startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
            // Notion의 date.end는 exclusive가 아니라 "마지막 날"을 가리키므로 +1일 해서 exclusive로 맞춘다.
            val endMillis = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            Triple(startMillis, endMillis, true)
        } else {
            val startInstant = java.time.OffsetDateTime.parse(start, ISO_DATE_TIME_OFFSET).toInstant()
            val endInstant = end?.let { java.time.OffsetDateTime.parse(it, ISO_DATE_TIME_OFFSET).toInstant() } ?: startInstant
            Triple(startInstant.toEpochMilli(), endInstant.toEpochMilli(), false)
        }
    }
}
