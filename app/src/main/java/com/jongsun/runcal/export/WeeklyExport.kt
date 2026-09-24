package com.jongsun.runcal.export

import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.dateRange
import com.jongsun.runcal.ui.calendar.koreanShortLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ExportRangeType { THIS_WEEK, NEXT_WEEK, CUSTOM }

enum class ExportFormat { MARKDOWN, CSV }

private val EXPORT_COLUMNS = listOf("id", "날짜", "요일", "종류", "세션", "거리", "상태", "일지")

/**
 * 주간표 내보내기 한 행. Notion 훈련일지 DB 스키마(종류/세션/거리/상태/일지)를 그대로 따르되,
 * 아직 Notion 연동 전이라 캘린더 이벤트에서 뽑을 수 있는 값만 채운다:
 * - id: 향후 Notion 레코드와 매칭할 수 있도록 캘린더 이벤트 ID를 임시로 넣어둔다.
 * - 종류: 캘린더 이벤트 제목을 그대로 쓴다(운동 종류를 나타내는 유일한 텍스트 필드이므로).
 * - 상태: 종료 시각이 이미 지났으면 "완료", 아니면 공란(Notion 쪽 상태값 체계를 모르므로 추측하지 않는다).
 * - 세션/거리/일지: 캘린더 이벤트에 대응하는 필드가 없어 항상 공란.
 */
data class ExportRow(
    val id: String,
    val date: LocalDate,
    val dayOfWeekLabel: String,
    val kind: String,
    val session: String = "",
    val distance: String = "",
    val status: String,
    val journal: String = "",
)

fun buildExportRows(events: List<EventItem>, zone: ZoneId = ZoneId.systemDefault()): List<ExportRow> {
    val now = Instant.now()
    return events.sortedBy { it.begin }.map { event ->
        val date = event.dateRange(zone).start
        ExportRow(
            id = event.id.toString(),
            date = date,
            dayOfWeekLabel = date.dayOfWeek.koreanShortLabel(),
            kind = event.title,
            status = if (Instant.ofEpochMilli(event.end) <= now) "완료" else "",
        )
    }
}

private fun ExportRow.toCells(): List<String> = listOf(id, date.toString(), dayOfWeekLabel, kind, session, distance, status, journal)

private fun String.escapeMarkdownCell(): String = replace("|", "\\|").replace("\n", " ")

fun renderMarkdownTable(rows: List<ExportRow>): String = buildString {
    append("| ").append(EXPORT_COLUMNS.joinToString(" | ")).append(" |\n")
    append("|").append(EXPORT_COLUMNS.joinToString("|") { "---" }).append("|\n")
    rows.forEach { row ->
        append("| ").append(row.toCells().joinToString(" | ") { it.escapeMarkdownCell() }).append(" |\n")
    }
}

private fun String.escapeCsvCell(): String =
    if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${replace("\"", "\"\"")}\""
    } else {
        this
    }

fun renderCsv(rows: List<ExportRow>): String = buildString {
    append(EXPORT_COLUMNS.joinToString(",") { it.escapeCsvCell() }).append("\r\n")
    rows.forEach { row ->
        append(row.toCells().joinToString(",") { it.escapeCsvCell() }).append("\r\n")
    }
}
