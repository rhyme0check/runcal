package com.jongsun.runcal.data.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * "이 CalendarContract 이벤트는 RunCal이 만든 것이다"라는 출처 표시만 담는 슬림한 테이블.
 * 로컬 일정 자체는 Room으로 옮기지 않고 CalendarContract에 그대로 둔다 — 백업/복원 시점에
 * 실제 필드(제목/시간 등)는 이 테이블의 [calendarEventId]로 CalendarContract에서 다시 읽는다
 * (사용자가 시스템 캘린더 앱으로 나중에 수정해도 백업이 낡은 사본을 들고 있지 않도록).
 */
@Entity(tableName = "local_event_provenance")
data class LocalEventProvenanceEntity(
    @PrimaryKey val calendarEventId: Long,
    val calendarId: Long,
    val createdAtMillis: Long,
)

@Dao
interface LocalEventProvenanceDao {
    @Query("SELECT * FROM local_event_provenance")
    suspend fun getAll(): List<LocalEventProvenanceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LocalEventProvenanceEntity)

    @Query("DELETE FROM local_event_provenance WHERE calendarEventId = :calendarEventId")
    suspend fun deleteByCalendarEventId(calendarEventId: Long)
}
