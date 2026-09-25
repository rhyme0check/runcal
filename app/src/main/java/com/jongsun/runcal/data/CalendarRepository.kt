package com.jongsun.runcal.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import com.jongsun.runcal.data.room.LocalEventProvenanceEntity
import com.jongsun.runcal.data.room.RunCalDatabase
import com.jongsun.runcal.data.source.EventSource
import com.jongsun.runcal.data.source.EventSourceKind
import com.jongsun.runcal.data.source.SourceRef
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "RunCal"

// RFC5545 DURATION 문자열(예: "PT3600S", "P1D") 파서 — 반복 일정은 DTEND 대신 DURATION을 쓴다.
private val DURATION_REGEX = Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")

private fun parseDurationMillis(duration: String?): Long {
    if (duration.isNullOrBlank()) return 0L
    val m = DURATION_REGEX.matchEntire(duration) ?: return 0L
    val sign = if (m.groupValues[1] == "-") -1L else 1L
    val weeks = m.groupValues[2].toLongOrNull() ?: 0L
    val days = m.groupValues[3].toLongOrNull() ?: 0L
    val hours = m.groupValues[4].toLongOrNull() ?: 0L
    val minutes = m.groupValues[5].toLongOrNull() ?: 0L
    val seconds = m.groupValues[6].toLongOrNull() ?: 0L
    val totalSeconds = weeks * 7 * 86400 + days * 86400 + hours * 3600 + minutes * 60 + seconds
    return sign * totalSeconds * 1000
}

private fun formatDuration(durationMillis: Long, allDay: Boolean): String {
    val totalSeconds = (durationMillis / 1000).coerceAtLeast(if (allDay) 86400L else 1L)
    return if (allDay) "P${totalSeconds / 86400}D" else "PT${totalSeconds}S"
}

/** CalendarContract 기반 캘린더/일정 접근 레이어. 모든 접근 지점은 권한 가드 + SecurityException 방어를 거친다. */
class CalendarRepository(private val context: Context) : EventSource {

    override val kind: EventSourceKind = EventSourceKind.CALENDAR

    /** [EventSource] 어댑터. 기존 [getEvents] 호출부는 전혀 바뀌지 않고, 이 오버로드만 [SourceRef]를 풀어 위임한다. */
    override suspend fun getEvents(startMillis: Long, endMillis: Long, refs: Set<SourceRef>?): List<EventItem> {
        val calendarIds = refs?.filterIsInstance<SourceRef.Calendar>()?.map { it.calendarId }
        return getEvents(startMillis, endMillis, calendarIds)
    }

    private val resolver get() = context.contentResolver
    private val provenanceDao by lazy { RunCalDatabase.getInstance(context).localEventProvenanceDao() }

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
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            )
            val result = mutableListOf<CalendarInfo>()
            resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                val colorIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR)
                val visibleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)
                val accessLevelIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                while (cursor.moveToNext()) {
                    result += CalendarInfo(
                        id = cursor.getLong(idIdx),
                        displayName = cursor.getString(nameIdx).orEmpty(),
                        accountName = cursor.getString(accountIdx).orEmpty(),
                        color = cursor.getInt(colorIdx),
                        visible = cursor.getInt(visibleIdx) != 0,
                        isWritable = cursor.getInt(accessLevelIdx) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
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
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.RRULE,
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
                val locationIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
                val descriptionIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)
                val rruleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.RRULE)
                while (cursor.moveToNext()) {
                    result += EventItem(
                        id = cursor.getLong(eventIdIdx),
                        calendarId = cursor.getLong(calendarIdIdx),
                        title = cursor.getString(titleIdx).orEmpty(),
                        begin = cursor.getLong(beginIdx),
                        end = cursor.getLong(endIdx),
                        allDay = cursor.getInt(allDayIdx) != 0,
                        color = cursor.getInt(calendarColorIdx),
                        location = cursor.getString(locationIdx).orEmpty(),
                        description = cursor.getString(descriptionIdx).orEmpty(),
                        rrule = cursor.getString(rruleIdx)?.takeIf { it.isNotBlank() },
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
        location: String = "",
        description: String = "",
        reminderMinutes: List<Int> = emptyList(),
        timeZoneId: String = TimeZone.getDefault().id,
        rrule: String? = null,
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
                // 반복 일정은 CalendarContract 요구사항상 DTEND 대신 DURATION+RRULE을 써야 한다.
                if (rrule.isNullOrBlank()) {
                    put(CalendarContract.Events.DTEND, endMillis)
                } else {
                    put(CalendarContract.Events.DURATION, formatDuration(endMillis - startMillis, allDay))
                    put(CalendarContract.Events.RRULE, rrule)
                }
                put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, timeZoneId)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.DESCRIPTION, description)
                if (reminderMinutes.isNotEmpty()) put(CalendarContract.Events.HAS_ALARM, 1)
            }
            val id = resolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull() ?: -1L
            // "RunCal이 만든 일정"이라는 출처 표시 — 백업이 이 표시로 로컬 일정만 골라 다시 읽는다.
            if (id > 0) {
                provenanceDao.insert(LocalEventProvenanceEntity(id, calendarId, System.currentTimeMillis()))
                replaceReminders(id, reminderMinutes)
            }
            id
        } catch (e: SecurityException) {
            Log.e(TAG, "createEvent failed", e)
            -1L
        }
    }

    /** 반복 일정 전개 없이 [eventId] 자체(하나의 Events 행)를 직접 읽는다 — 백업이 로컬 일정 필드를 다시 읽을 때 쓴다. */
    suspend fun getEventById(eventId: Long): EventItem? = withContext(Dispatchers.IO) {
        if (!hasCalendarReadPermission(context)) {
            Log.e(TAG, "getEventById: READ_CALENDAR permission not granted")
            return@withContext null
        }
        try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.DURATION,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.CALENDAR_COLOR,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.RRULE,
            )
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
                val durationIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DURATION)
                val startMillis = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART))
                // 반복 일정은 DTEND 대신 DURATION을 쓴다 — 이 함수는 반복 규칙 복원을 위해 마스터
                // 행(회차가 아닌 원본 Events 행)을 직접 읽으므로 두 경우를 모두 다뤄야 한다.
                val endMillis = if (!cursor.isNull(endIdx)) {
                    cursor.getLong(endIdx)
                } else {
                    startMillis + parseDurationMillis(cursor.getString(durationIdx))
                }
                return@withContext EventItem(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)),
                    calendarId = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)),
                    title = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)).orEmpty(),
                    begin = startMillis,
                    end = endMillis,
                    allDay = cursor.getInt(cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)) != 0,
                    color = cursor.getInt(cursor.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_COLOR)),
                    location = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)).orEmpty(),
                    description = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)).orEmpty(),
                    rrule = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.RRULE))?.takeIf { it.isNotBlank() },
                )
            }
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "getEventById failed", e)
            null
        }
    }

    suspend fun updateEvent(
        eventId: Long,
        title: String? = null,
        startMillis: Long? = null,
        endMillis: Long? = null,
        allDay: Boolean? = null,
        location: String? = null,
        description: String? = null,
        // null = 알림을 건드리지 않음. 빈 리스트를 포함해 non-null이면 전체를 이 값으로 교체한다.
        reminderMinutes: List<Int>? = null,
        // 반복 규칙의 전체 원하는 상태(null/빈 문자열 = 반복 아님). startMillis/endMillis와 함께
        // 넘어올 때만 반영한다 — 편집 화면은 항상 이 셋을 함께 넘기므로 부분 갱신 신경 쓸 필요가 없다.
        rrule: String? = null,
    ): Int = withContext(Dispatchers.IO) {
        if (!hasCalendarWritePermission(context)) {
            Log.e(TAG, "updateEvent: WRITE_CALENDAR permission not granted")
            return@withContext 0
        }
        try {
            val values = ContentValues().apply {
                title?.let { put(CalendarContract.Events.TITLE, it) }
                if (startMillis != null && endMillis != null) {
                    put(CalendarContract.Events.DTSTART, startMillis)
                    if (rrule.isNullOrBlank()) {
                        put(CalendarContract.Events.DTEND, endMillis)
                        putNull(CalendarContract.Events.DURATION)
                        putNull(CalendarContract.Events.RRULE)
                    } else {
                        putNull(CalendarContract.Events.DTEND)
                        put(CalendarContract.Events.DURATION, formatDuration(endMillis - startMillis, allDay ?: false))
                        put(CalendarContract.Events.RRULE, rrule)
                    }
                }
                allDay?.let { put(CalendarContract.Events.ALL_DAY, if (it) 1 else 0) }
                location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                reminderMinutes?.let { put(CalendarContract.Events.HAS_ALARM, if (it.isNotEmpty()) 1 else 0) }
            }
            val updated = if (values.size() == 0) {
                1 // 필드는 안 바뀌고 알림만 바뀌는 경우도 있어, 0으로 취급해 호출부가 실패로 오인하지 않게 한다.
            } else {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                resolver.update(uri, values, null, null)
            }
            if (reminderMinutes != null) replaceReminders(eventId, reminderMinutes)
            updated
        } catch (e: SecurityException) {
            Log.e(TAG, "updateEvent failed", e)
            0
        }
    }

    /** 알림은 최대 5개까지만 저장한다(발송 자체는 P4). 전체 삭제 후 다시 넣는 방식이라 항상 요청한 목록과 정확히 일치한다. */
    private fun replaceReminders(eventId: Long, minutes: List<Int>) {
        resolver.delete(CalendarContract.Reminders.CONTENT_URI, "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        minutes.take(MAX_REMINDERS).forEach { minute ->
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minute)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            resolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
        }
    }

    /** [eventId]에 걸린 알림들을 "몇 분 전"인지로 반환한다 — 편집 화면이 기존 값을 불러올 때 쓴다. */
    suspend fun getReminders(eventId: Long): List<Int> = withContext(Dispatchers.IO) {
        if (!hasCalendarReadPermission(context)) return@withContext emptyList()
        try {
            val projection = arrayOf(CalendarContract.Reminders.MINUTES)
            val selection = "${CalendarContract.Reminders.EVENT_ID} = ?"
            val result = mutableListOf<Int>()
            resolver.query(CalendarContract.Reminders.CONTENT_URI, projection, selection, arrayOf(eventId.toString()), null)?.use { cursor ->
                val minutesIdx = cursor.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES)
                while (cursor.moveToNext()) result += cursor.getInt(minutesIdx)
            }
            result
        } catch (e: SecurityException) {
            Log.e(TAG, "getReminders failed", e)
            emptyList()
        }
    }

    suspend fun deleteEvent(eventId: Long): Int = withContext(Dispatchers.IO) {
        if (!hasCalendarWritePermission(context)) {
            Log.e(TAG, "deleteEvent: WRITE_CALENDAR permission not granted")
            return@withContext 0
        }
        try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val deleted = resolver.delete(uri, null, null)
            if (deleted > 0) provenanceDao.deleteByCalendarEventId(eventId)
            deleted
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
        const val MAX_REMINDERS = 5
    }
}
