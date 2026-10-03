package com.jongsun.runcal.data.share

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 공유받은 .ics에서 읽어낸 일정 하나. 종일은 캘린더 규칙대로 UTC 자정, [endMillis]는 배타적. */
data class IcsEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String = "",
    val description: String = "",
    val rrule: String? = null,
)

/**
 * 최소한의 iCalendar(RFC 5545) 읽기. VEVENT의 SUMMARY/DTSTART/DTEND/DURATION(일·시·분)/LOCATION/DESCRIPTION/RRULE만 본다.
 * 시각은 UTC("Z"), TZID 지정, 시간대 없음(기기 시간대로 해석) 세 형식을 지원한다. 읽을 수 없는 VEVENT는 건너뛴다.
 */
object IcsParser {
    private const val MAX_EVENTS = 50
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun looksLikeIcs(text: String): Boolean = text.contains("BEGIN:VEVENT")

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): List<IcsEvent> {
        val lines = unfold(text)
        val events = ArrayList<IcsEvent>()
        var props: MutableMap<String, Pair<Map<String, String>, String>>? = null
        for (line in lines) {
            when {
                line.equals("BEGIN:VEVENT", ignoreCase = true) -> props = LinkedHashMap()
                line.equals("END:VEVENT", ignoreCase = true) -> {
                    props?.let { p -> toEvent(p, zone)?.let { events += it } }
                    props = null
                    if (events.size >= MAX_EVENTS) break
                }
                props != null -> {
                    val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
                    val head = line.substring(0, colon).split(';')
                    val name = head.first().uppercase()
                    val params = head.drop(1).mapNotNull { p ->
                        val eq = p.indexOf('=')
                        if (eq <= 0) null else p.substring(0, eq).uppercase() to p.substring(eq + 1).trim('"')
                    }.toMap()
                    // 같은 속성이 여러 번 나오면 첫 값을 쓴다.
                    props.putIfAbsent(name, params to line.substring(colon + 1))
                }
            }
        }
        return events
    }

    /** 줄 접기(다음 줄이 공백/탭으로 시작) 해제. */
    private fun unfold(text: String): List<String> {
        val out = ArrayList<String>()
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { raw ->
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + raw.substring(1)
            } else if (raw.isNotBlank()) {
                out += raw.trimEnd()
            }
        }
        return out
    }

    private fun toEvent(p: Map<String, Pair<Map<String, String>, String>>, zone: ZoneId): IcsEvent? {
        val (startParams, startValue) = p["DTSTART"] ?: return null
        val allDay = startParams["VALUE"].equals("DATE", ignoreCase = true) || startValue.trim().length == 8
        val start = parseTime(startParams, startValue, zone, allDay) ?: return null
        val end = p["DTEND"]?.let { (params, value) -> parseTime(params, value, zone, allDay) }
            ?: p["DURATION"]?.second?.let { d -> parseDuration(d)?.let { start + it } }
            ?: if (allDay) start + DAY_MILLIS else start + HOUR_MILLIS
        return IcsEvent(
            title = unescape(p["SUMMARY"]?.second.orEmpty()).ifBlank { "(제목 없음)" },
            startMillis = start,
            endMillis = maxOf(end, start),
            allDay = allDay,
            location = unescape(p["LOCATION"]?.second.orEmpty()),
            description = unescape(p["DESCRIPTION"]?.second.orEmpty()),
            rrule = p["RRULE"]?.second?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseTime(params: Map<String, String>, value: String, zone: ZoneId, allDay: Boolean): Long? = runCatching {
        val v = value.trim()
        if (allDay) {
            LocalDate.parse(v.take(8), DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            val local = LocalDateTime.parse(v.removeSuffix("Z").take(15), DATE_TIME)
            val z = when {
                v.endsWith("Z") -> ZoneOffset.UTC
                params["TZID"] != null -> runCatching { ZoneId.of(params["TZID"]) }.getOrDefault(zone)
                else -> zone
            }
            local.atZone(z).toInstant().toEpochMilli()
        }
    }.getOrNull()

    /** "PT1H30M", "P1D" 같은 기간. 주(W)·일·시·분만 지원. */
    private fun parseDuration(text: String): Long? {
        val m = Regex("^P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").find(text.trim()) ?: return null
        fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
        return g(1) * 7 * DAY_MILLIS + g(2) * DAY_MILLIS + g(3) * HOUR_MILLIS + g(4) * 60_000L + g(5) * 1000L
    }

    private fun unescape(s: String): String =
        s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim()

    private const val HOUR_MILLIS = 60 * 60 * 1000L
    private const val DAY_MILLIS = 24 * HOUR_MILLIS
}
