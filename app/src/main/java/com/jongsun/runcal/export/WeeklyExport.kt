package com.jongsun.runcal.export

import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.dateRange
import com.jongsun.runcal.data.room.NotionDatabaseEntity
import com.jongsun.runcal.data.room.NotionEventEntity
import com.jongsun.runcal.ui.calendar.koreanShortLabel
import com.jongsun.runcal.ui.calendar.toTimeLabel
import java.time.Instant
import java.time.ZoneId

enum class ExportRangeType { THIS_WEEK, NEXT_WEEK, CUSTOM }

enum class ExportFormat { MARKDOWN, CSV }

/** 소스에 따라 컬럼 구성이 달라지므로 (헤더 목록, 행 목록)을 그대로 들고 다니는 얇은 구조. */
data class ExportTable(val columns: List<String>, val rows: List<List<String>>)

private val CALENDAR_EXPORT_COLUMNS = listOf("id", "날짜", "요일", "시간", "제목", "캘린더", "장소")

/** 캘린더 소스 전용 컬럼(id·날짜·요일·시간·제목·캘린더·장소) — Notion 매핑과 무관하게 고정. */
fun buildCalendarExportTable(
    events: List<EventItem>,
    calendarNameById: Map<Long, String>,
    zone: ZoneId = ZoneId.systemDefault(),
): ExportTable {
    val rows = events.sortedBy { it.begin }.map { event ->
        val date = event.dateRange(zone).start
        listOf(
            event.id.toString(),
            date.toString(),
            date.dayOfWeek.koreanShortLabel(),
            formatEventTimeForExport(event, zone),
            event.title,
            calendarNameById[event.calendarId].orEmpty(),
            event.location,
        )
    }
    return ExportTable(CALENDAR_EXPORT_COLUMNS, rows)
}

private fun formatEventTimeForExport(event: EventItem, zone: ZoneId): String {
    if (event.allDay) return "종일"
    val start = Instant.ofEpochMilli(event.begin).atZone(zone).toLocalTime()
    val end = Instant.ofEpochMilli(event.end).atZone(zone).toLocalTime()
    return "${start.toTimeLabel()}~${end.toTimeLabel()}"
}

/**
 * Notion 소스 전용 컬럼. id는 Notion 페이지 ID, 나머지는 그 DB의 매핑 속성 이름을 그대로
 * 컬럼명으로 쓴다(제목 속성은 항상 포함, 부제/상태는 매핑 안 됐으면 컬럼 자체를 생략).
 * [rows]는 Room 캐시(NotionEventDao)에서 읽은 것만 받는다 — 이 함수 자체는 네트워크를 모른다.
 */
fun buildNotionExportTable(
    rows: List<NotionEventEntity>,
    registration: NotionDatabaseEntity,
    zone: ZoneId = ZoneId.systemDefault(),
): ExportTable {
    val columns = buildList {
        add("id")
        add("날짜")
        add("요일")
        add(registration.titleProperty)
        registration.subtitleProperty?.let { add(it) }
        registration.statusProperty?.let { add(it) }
    }
    val tableRows = rows.sortedBy { it.startMillis }.map { row ->
        val date = Instant.ofEpochMilli(row.startMillis).atZone(zone).toLocalDate()
        buildList {
            add(row.notionPageId)
            add(date.toString())
            add(date.dayOfWeek.koreanShortLabel())
            add(row.title)
            registration.subtitleProperty?.let { add(row.subtitle.orEmpty()) }
            registration.statusProperty?.let { add(row.statusRaw.orEmpty()) }
        }
    }
    return ExportTable(columns, tableRows)
}

private fun String.escapeMarkdownCell(): String = replace("|", "\\|").replace("\n", " ")

fun renderMarkdownTable(table: ExportTable): String = buildString {
    append("| ").append(table.columns.joinToString(" | ")).append(" |\n")
    append("|").append(table.columns.joinToString("|") { "---" }).append("|\n")
    table.rows.forEach { row ->
        append("| ").append(row.joinToString(" | ") { it.escapeMarkdownCell() }).append(" |\n")
    }
}

private fun String.escapeCsvCell(): String =
    if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${replace("\"", "\"\"")}\""
    } else {
        this
    }

fun renderCsv(table: ExportTable): String = buildString {
    append(table.columns.joinToString(",") { it.escapeCsvCell() }).append("\r\n")
    table.rows.forEach { row ->
        append(row.joinToString(",") { it.escapeCsvCell() }).append("\r\n")
    }
}
