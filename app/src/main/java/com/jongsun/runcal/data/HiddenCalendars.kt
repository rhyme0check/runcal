package com.jongsun.runcal.data

import android.content.Context
import kotlinx.coroutines.flow.first

/**
 * 목록에서 숨긴 캘린더 id의 메모리 캐시. 일정 조회([CalendarRepository.getEvents])마다 쓰므로(위젯 렌더링 포함)
 * DataStore는 처음 한 번만 읽고, 설정을 바꿀 때 [invalidate]한다.
 */
object HiddenCalendars {
    @Volatile private var cached: Set<Long>? = null

    suspend fun ids(context: Context): Set<Long> =
        cached ?: AppSettingsRepository(context.applicationContext).settings.first().hiddenCalendarIds.also { cached = it }

    fun invalidate() {
        cached = null
    }
}
