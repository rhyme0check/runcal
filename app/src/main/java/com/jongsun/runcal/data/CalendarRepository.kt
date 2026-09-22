package com.jongsun.runcal.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "RunCal"

/** CalendarContract 기반 캘린더/일정 접근 레이어. 모든 접근 지점은 권한 가드 + SecurityException 방어를 거친다. */
class CalendarRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    suspend fun getCalendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        if (!hasCalendarReadPermission(context)) {
            Log.e(TAG, "getCalendars: READ_CALENDAR permission not granted")
            return@withContext emptyList()
        }
        try {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_COLOR,
                CalendarContract.Calendars.VISIBLE,
            )
            val result = mutableListOf<CalendarInfo>()
            resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                val colorIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR)
                val visibleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)
                while (cursor.moveToNext()) {
                    result += CalendarInfo(
                        id = cursor.getLong(idIdx),
                        displayName = cursor.getString(nameIdx).orEmpty(),
                        accountName = cursor.getString(accountIdx).orEmpty(),
                        color = cursor.getInt(colorIdx),
                        visible = cursor.getInt(visibleIdx) != 0,
                    )
                }
            }
            result
        } catch (e: SecurityException) {
            Log.e(TAG, "getCalendars failed", e)
            emptyList()
        }
    }

    /** [CalendarContract.Instances]를 사용해 반복 일정을 회차별로 펼쳐서 조회한다. */
    suspend fun getEvents(
        startMillis: Long,
        endMillis: Long,
        calendarIds: List<Long>? = null,
    ): List<EventItem> = withContext(Dispatchers.IO) {
        if (!hasCalendarReadPermission(context)) {
            Log.e(TAG, "getEvents: READ_CALENDAR permission not granted")
            return@withContext emptyList()
        }
        try {
            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_COLOR,
            )
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
                ContentUris.appendId(this, startMillis)
                ContentUris.appendId(this, endMillis)
            }.build()

            val selection = if (!calendarIds.isNullOrEmpty()) {
                "${CalendarContract.Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",") { "?" }})"
            } else {
                null
            }
            val selectionArgs = calendarIds?.takeIf { it.isNotEmpty() }?.map { it.toString() }?.toTypedArray()

            val result = mutableListOf<EventItem>()
            resolver.query(uri, projection, selection, selectionArgs, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
                val eventIdIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val calendarIdIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
                val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                val calendarColorIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_COLOR)
                while (cursor.moveToNext()) {
                    result += EventItem(
                        id = cursor.getLong(eventIdIdx),
                        calendarId = cursor.getLong(calendarIdIdx),
                        title = cursor.getString(titleIdx).orEmpty(),
                        begin = cursor.getLong(beginIdx),
                        end = cursor.getLong(endIdx),
                        allDay = cursor.getInt(allDayIdx) != 0,
                        color = cursor.getInt(calendarColorIdx),
                    )
                }
            }
            result
        } catch (e: SecurityException) {
            Log.e(TAG, "getEvents failed", e)
            emptyList()
        }
    }

    suspend fun createEvent(
        calendarId: Long,
        title: String,
        startMillis: Long,
        endMillis: Long,
        allDay: Boolean = false,
        timeZoneId: String = TimeZone.getDefault().id,
    ): Long = withContext(Dispatchers.IO) {
        if (!hasCalendarWritePermission(context)) {
            Log.e(TAG, "createEvent: WRITE_CALENDAR permission not granted")
            return@withContext -1L
        }
        try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, timeZoneId)
            }
            resolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull() ?: -1L
        } catch (e: SecurityException) {
            Log.e(TAG, "createEvent failed", e)
            -1L
        }
    }

    suspend fun updateEvent(
        eventId: Long,
        title: String? = null,
        startMillis: Long? = null,
        endMillis: Long? = null,
    ): Int = withContext(Dispatchers.IO) {
        if (!hasCalendarWritePermission(context)) {
            Log.e(TAG, "updateEvent: WRITE_CALENDAR permission not granted")
            return@withContext 0
        }
        try {
            val values = ContentValues().apply {
                title?.let { put(CalendarContract.Events.TITLE, it) }
                startMillis?.let { put(CalendarContract.Events.DTSTART, it) }
                endMillis?.let { put(CalendarContract.Events.DTEND, it) }
            }
            if (values.size() == 0) return@withContext 0
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            resolver.update(uri, values, null, null)
        } catch (e: SecurityException) {
            Log.e(TAG, "updateEvent failed", e)
            0
        }
    }

    suspend fun deleteEvent(eventId: Long): Int = withContext(Dispatchers.IO) {
        if (!hasCalendarWritePermission(context)) {
            Log.e(TAG, "deleteEvent: WRITE_CALENDAR permission not granted")
            return@withContext 0
        }
        try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            resolver.delete(uri, null, null)
        } catch (e: SecurityException) {
            Log.e(TAG, "deleteEvent failed", e)
            0
        }
    }

    /** 구글 계정 없이도 테스트 가능한 ACCOUNT_TYPE_LOCAL 캘린더를 준비한다. 이미 있으면 기존 id를 반환. */
    suspend fun ensureLocalTestCalendar(): Long = withContext(Dispatchers.IO) {
        if (!hasCalendarReadPermission(context) || !hasCalendarWritePermission(context)) {
            Log.e(TAG, "ensureLocalTestCalendar: READ/WRITE_CALENDAR permission not granted")
            return@withContext -1L
        }
        try {
            findLocalTestCalendarId()?.let { return@withContext it }

            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, LOCAL_CALENDAR_NAME)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, LOCAL_CALENDAR_NAME)
                put(CalendarContract.Calendars.CALENDAR_COLOR, DEFAULT_CALENDAR_COLOR)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT_NAME)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            }
            val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build()
            resolver.insert(uri, values)?.lastPathSegment?.toLongOrNull() ?: -1L
        } catch (e: SecurityException) {
            Log.e(TAG, "ensureLocalTestCalendar failed", e)
            -1L
        }
    }

    private fun findLocalTestCalendarId(): Long? {
        if (!hasCalendarReadPermission(context)) {
            Log.e(TAG, "findLocalTestCalendarId: READ_CALENDAR permission not granted")
            return null
        }
        return try {
            val projection = arrayOf(CalendarContract.Calendars._ID)
            val selection = "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
                "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND " +
                "${CalendarContract.Calendars.NAME} = ?"
            val args = arrayOf(LOCAL_ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL, LOCAL_CALENDAR_NAME)
            resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0)
            }
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "findLocalTestCalendarId failed", e)
            null
        }
    }

    /** 이번 달에 걸쳐 더미 제목의 샘플 일정을 [count]건 추가한다. 데모/테스트 용도. */
    suspend fun addSampleEvents(calendarId: Long, count: Int = 10): Int = withContext(Dispatchers.IO) {
        if (!hasCalendarWritePermission(context)) {
            Log.e(TAG, "addSampleEvents: WRITE_CALENDAR permission not granted")
            return@withContext 0
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val titles = listOf(
            "팀 회의", "러닝", "병원 예약", "장보기", "프로젝트 마감",
            "회식", "생일 축하", "월간 정산", "세차", "가족 모임", "헬스장 PT", "영화 관람",
        )
        var inserted = 0
        repeat(count) { index ->
            val dayOfMonth = ((index * 3) % today.lengthOfMonth()) + 1
            val date = today.withDayOfMonth(dayOfMonth)
            val startHour = 9 + (index % 8)
            val start = date.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()
            val end = date.atTime(startHour + 1, 0).atZone(zone).toInstant().toEpochMilli()
            val id = createEvent(
                calendarId = calendarId,
                title = titles[index % titles.size],
                startMillis = start,
                endMillis = end,
            )
            if (id > 0) inserted++
        }
        inserted
    }

    companion object {
        const val LOCAL_ACCOUNT_NAME = "RunCal Local"
        const val LOCAL_CALENDAR_NAME = "RunCal 테스트"
        const val DEFAULT_CALENDAR_COLOR = 0xFF6650A4.toInt()
    }
}
