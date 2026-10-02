package com.jongsun.runcal.data.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 일정 유형(업무·미팅·휴가·러닝 …). 저장할 캘린더와 색을 분리하기 위한 것 — 같은 캘린더에 저장해도 유형마다 다른 색을 쓴다.
 * [defaultCalendarId]/[defaultReminderMinutes]는 새 일정에 유형을 고를 때 미리 채우는 값일 뿐 강제하지 않는다.
 * [defaultReminderMinutes]가 -1이면 "알림 없음", null이면 앱의 기본 알림 설정을 따른다.
 */
@Entity(tableName = "event_types")
data class EventTypeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Int,
    val defaultCalendarId: Long?,
    val defaultReminderMinutes: Int?,
    val sortOrder: Int,
)

/** 일정(CalendarContract 이벤트 id) → 유형. 색 자체는 일정에 저장되고, 이 표는 "어느 유형으로 만들었는지" 표시용이다. */
@Entity(tableName = "event_type_assignments")
data class EventTypeAssignmentEntity(
    @PrimaryKey val eventId: Long,
    val typeId: String,
)

@Dao
interface EventTypeDao {
    @Query("SELECT * FROM event_types ORDER BY sortOrder, name")
    suspend fun getAll(): List<EventTypeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(type: EventTypeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(types: List<EventTypeEntity>)

    @Query("DELETE FROM event_types WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM event_types")
    suspend fun deleteAllTypes()

    @Query("DELETE FROM event_type_assignments WHERE typeId = :typeId")
    suspend fun deleteAssignmentsOfType(typeId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun assign(assignment: EventTypeAssignmentEntity)

    @Query("DELETE FROM event_type_assignments WHERE eventId = :eventId")
    suspend fun deleteAssignment(eventId: Long)

    @Query("SELECT typeId FROM event_type_assignments WHERE eventId = :eventId")
    suspend fun typeIdFor(eventId: Long): String?
}
