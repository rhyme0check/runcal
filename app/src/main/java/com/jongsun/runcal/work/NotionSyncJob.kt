package com.jongsun.runcal.work

import android.util.Log
import com.jongsun.runcal.data.notion.NotionApiClient
import com.jongsun.runcal.data.notion.NotionApiException
import com.jongsun.runcal.data.notion.NotionPropertyMapper
import com.jongsun.runcal.data.room.NotionDatabaseDao
import com.jongsun.runcal.data.room.NotionDatabaseEntity
import com.jongsun.runcal.data.room.NotionEventDao
import com.jongsun.runcal.data.room.NotionEventEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "RunCal"

/** 등록된 DB 몇 개를 과거 3개월~미래 12개월 범위로 동기화한다. */
private val SYNC_WINDOW_PAST_MONTHS = 3L
private val SYNC_WINDOW_FUTURE_MONTHS = 12L

data class NotionSyncResult(val registrationId: String, val eventCount: Int, val status: String, val error: String? = null)

/**
 * 실제 동기화 로직. Worker 본체와 분리해둬서 [com.jongsun.runcal.work.NotionSyncWorker]와
 * 디버그 훅(설정 화면의 임시 버튼) 양쪽에서 똑같은 코드로 테스트/실행할 수 있게 한다.
 */
class NotionSyncJob(
    private val notionDatabaseDao: NotionDatabaseDao,
    private val notionEventDao: NotionEventDao,
    private val apiClient: NotionApiClient,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun syncAll(): List<NotionSyncResult> = notionDatabaseDao.getAll().map { syncOne(it) }

    suspend fun syncOne(registration: NotionDatabaseEntity): NotionSyncResult {
        val nowMillis = System.currentTimeMillis()
        var schemaMs = 0L
        var queryMs = 0L
        var pageCount = 0
        var roomMs = 0L

        // 1. 스키마 재검증 — 매핑된 속성이 사라지거나 이름이 바뀌었으면 페이지 조회 없이
        //    기존 캐시를 그대로 두고 SCHEMA_INVALID만 남긴다(비우는 것보다 낡은 게 낫다).
        val schemaStart = System.currentTimeMillis()
        val schema = try {
            apiClient.retrieveDatabase(registration.notionDatabaseId).also { schemaMs = System.currentTimeMillis() - schemaStart }
        } catch (e: NotionApiException) {
            Log.e(TAG, "NotionSyncJob: retrieveDatabase failed for ${registration.id}, HTTP ${e.statusCode}")
            val status = if (e.statusCode == 401 || e.statusCode == 403) "ERROR" else "ERROR"
            notionDatabaseDao.updateSyncResult(registration.id, nowMillis, status, "HTTP ${e.statusCode}")
            return NotionSyncResult(registration.id, 0, status, "HTTP ${e.statusCode}")
        }

        val propertyNames = schema.properties.keys
        val requiredPropertiesPresent = registration.dateProperty in propertyNames && registration.titleProperty in propertyNames
        if (!requiredPropertiesPresent) {
            Log.e(TAG, "NotionSyncJob: mapped properties missing for ${registration.id} — schema changed")
            notionDatabaseDao.updateSyncResult(registration.id, nowMillis, "SCHEMA_INVALID", "매핑된 날짜/제목 속성이 스키마에 없음")
            return NotionSyncResult(registration.id, 0, "SCHEMA_INVALID", "매핑된 날짜/제목 속성이 스키마에 없음")
        }

        // 2. 날짜 속성 기준 필터로 동기화 윈도우만 페이지네이션 조회
        val today = LocalDate.now()
        val filter = buildJsonObject {
            put(
                "and",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("property", registration.dateProperty)
                            put("date", buildJsonObject { put("on_or_after", today.minusMonths(SYNC_WINDOW_PAST_MONTHS).format(DateTimeFormatter.ISO_LOCAL_DATE)) })
                        },
                    )
                    add(
                        buildJsonObject {
                            put("property", registration.dateProperty)
                            put("date", buildJsonObject { put("on_or_before", today.plusMonths(SYNC_WINDOW_FUTURE_MONTHS).format(DateTimeFormatter.ISO_LOCAL_DATE)) })
                        },
                    )
                },
            )
        }

        val events = mutableListOf<NotionEventEntity>()
        var cursor: String? = null
        try {
            do {
                val queryStart = System.currentTimeMillis()
                val page = apiClient.queryDatabase(registration.notionDatabaseId, filter = filter, startCursor = cursor)
                queryMs += System.currentTimeMillis() - queryStart
                pageCount++
                for (notionPage in page.results) {
                    val dateRange = NotionPropertyMapper.extractDateRange(notionPage.properties, registration.dateProperty) ?: continue
                    val (startMillis, endMillis, allDay) = NotionPropertyMapper.toEpochMillisRange(dateRange.first, dateRange.second, zone)
                    events += NotionEventEntity(
                        registrationId = registration.id,
                        notionPageId = notionPage.id,
                        title = NotionPropertyMapper.extractTitle(notionPage.properties, registration.titleProperty),
                        subtitle = NotionPropertyMapper.extractDisplayText(notionPage.properties, registration.subtitleProperty),
                        statusRaw = NotionPropertyMapper.extractDisplayText(notionPage.properties, registration.statusProperty),
                        startMillis = startMillis,
                        endMillis = endMillis,
                        allDay = allDay,
                        notionUrl = notionPage.url,
                        lastEditedTimeIso = notionPage.lastEditedTime,
                        fetchedAtMillis = nowMillis,
                    )
                }
                cursor = page.nextCursor.takeIf { page.hasMore }
            } while (cursor != null)
        } catch (e: NotionApiException) {
            Log.e(TAG, "NotionSyncJob: queryDatabase failed for ${registration.id}, HTTP ${e.statusCode}")
            notionDatabaseDao.updateSyncResult(registration.id, nowMillis, "ERROR", "HTTP ${e.statusCode}")
            return NotionSyncResult(registration.id, 0, "ERROR", "HTTP ${e.statusCode}")
        }

        val roomStart = System.currentTimeMillis()
        notionEventDao.replaceForDatabase(registration.id, events)
        notionDatabaseDao.updateSyncResult(registration.id, nowMillis, "OK", null)
        roomMs = System.currentTimeMillis() - roomStart
        Log.d(
            TAG,
            "NotionSyncJob timing: schema=${schemaMs}ms query=${queryMs}ms(${pageCount}p) room=${roomMs}ms " +
                "total=${System.currentTimeMillis() - nowMillis}ms events=${events.size}",
        )
        return NotionSyncResult(registration.id, events.size, "OK")
    }
}
