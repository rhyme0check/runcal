package com.jongsun.runcal.data.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * "지금 AlarmManager에 실제로 걸려 있는 알람 하나"를 추적한다. AlarmManager는 "지금 내가 건 알람
 * 목록"을 조회하는 API가 없어서, 재동기화(리마인더 리스케줄) 때 무엇을 취소하고 무엇을 새로
 * 걸어야 할지 스스로 기억해둬야 한다 — 이 테이블이 그 장부다.
 *
 * [key]는 "eventId:occurrenceBeginMillis:reminderMinutes" 형식의 안정적인 문자열이라, 같은
 * 회차·같은 알림 오프셋을 다시 계산해도 항상 같은 행을 가리킨다(중복 스케줄 방지).
 */
@Entity(tableName = "scheduled_reminders")
data class ScheduledReminderEntity(
    @PrimaryKey val key: String,
    val eventId: Long,
    val occurrenceBeginMillis: Long,
    val reminderMinutes: Int,
    val triggerAtMillis: Long,
)

@Dao
interface ScheduledReminderDao {
    @Query("SELECT * FROM scheduled_reminders")
    suspend fun getAll(): List<ScheduledReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScheduledReminderEntity)

    @Query("DELETE FROM scheduled_reminders WHERE key = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM scheduled_reminders")
    suspend fun deleteAll()
}
