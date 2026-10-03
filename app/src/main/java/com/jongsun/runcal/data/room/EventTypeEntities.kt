package com.jongsun.runcal.data.room

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 일정그룹(업무·미팅·포인트훈련·이지훈련 …). 저장할 캘린더와 별개로 색을 정한다 — 그룹 색은 그때그때 적용돼서
 * 그룹 색을 바꾸면 이미 만든 일정도 같이 바뀐다(개별 일정 색보다 우선). [defaultCalendarId]/[defaultReminderMinutes]는
 * 새 일정에 그룹을 고를 때 미리 채우는 값일 뿐 강제하지 않는다. [defaultReminderMinutes]가 -1이면 "알림 없음", null이면
 * 앱의 기본 알림 설정을 따른다. [titleKeywords]는 쉼표로 구분한 제목 앞글자 규칙(예: "RP, RACE") — 직접 지정하지 않은
 * 일정(특히 Notion 항목)은 제목이 이 중 하나로 시작하면 이 그룹으로 본다.
 */
@Entity(tableName = "event_types")
data class EventTypeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Int,
    val defaultCalendarId: Long?,
    val defaultReminderMinutes: Int?,
    val sortOrder: Int,
    @ColumnInfo(defaultValue = "") val titleKeywords: String = "",
)

/** Notion 항목(페이지) → 그룹을 직접 정한 것. [typeId]가 빈 문자열이면 "그룹 없음"(제목 규칙도 적용하지 않음). 앱 안에서만 쓰고 Notion에는 쓰지 않는다. */
@Entity(tableName = "notion_group_assignments", primaryKeys = ["registrationId", "pageId"])
data class NotionGroupAssignmentEntity(
    val registrationId: String,
    val pageId: String,
    val typeId: String,
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

    @Query("SELECT * FROM event_type_assignments")
    suspend fun allAssignments(): List<EventTypeAssignmentEntity>

    @Query("SELECT * FROM notion_group_assignments")
    suspend fun allNotionAssignments(): List<NotionGroupAssignmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun assignNotion(assignment: NotionGroupAssignmentEntity)

    @Query("DELETE FROM notion_group_assignments WHERE registrationId = :registrationId AND pageId = :pageId")
    suspend fun deleteNotionAssignment(registrationId: String, pageId: String)

    @Query("DELETE FROM notion_group_assignments WHERE typeId = :typeId")
    suspend fun deleteNotionAssignmentsOfType(typeId: String)
}
