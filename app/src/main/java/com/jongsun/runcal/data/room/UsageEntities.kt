package com.jongsun.runcal.data.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/** 기능별 하루 사용 횟수(앱 안에만 저장). 일정 제목·내용은 담지 않는다. [day]는 "yyyy-MM-dd". */
@Entity(tableName = "usage_counts", primaryKeys = ["day", "name"])
data class UsageCountEntity(val day: String, val name: String, val count: Int)

/** 오류 기록(앱 꺼짐, 동기화·Notion 실패, 위젯 지연 등). 앱 안에만 저장. */
@Entity(tableName = "app_errors")
data class AppErrorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    val kind: String,
    val message: String,
)

/** Notion 항목 → 폰 캘린더(My calendar 등)에 만든 복사본. 다음 복사 때 같은 복사본을 고치거나 지우는 데 쓴다. */
@Entity(tableName = "notion_copies", primaryKeys = ["registrationId", "pageId"])
data class NotionCopyEntity(
    val registrationId: String,
    val pageId: String,
    val eventId: Long,
    val startMillis: Long,
)

data class UsageTotal(val name: String, val count: Int)

@Dao
interface UsageDao {
    @Query("SELECT count FROM usage_counts WHERE day = :day AND name = :name")
    suspend fun countOf(day: String, name: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCount(entity: UsageCountEntity)

    @Transaction
    suspend fun increment(day: String, name: String) {
        putCount(UsageCountEntity(day, name, (countOf(day, name) ?: 0) + 1))
    }

    @Query("SELECT name, SUM(count) AS count FROM usage_counts WHERE day >= :fromDay GROUP BY name ORDER BY count DESC")
    suspend fun totalsSince(fromDay: String): List<UsageTotal>

    @Query("SELECT * FROM usage_counts ORDER BY day, name")
    suspend fun allCounts(): List<UsageCountEntity>

    @Insert
    suspend fun insertError(error: AppErrorEntity)

    @Query("SELECT * FROM app_errors ORDER BY atMillis DESC LIMIT :limit")
    suspend fun recentErrors(limit: Int): List<AppErrorEntity>

    @Query("SELECT COUNT(*) FROM app_errors WHERE atMillis >= :fromMillis")
    suspend fun errorCountSince(fromMillis: Long): Int

    @Query("DELETE FROM usage_counts WHERE day < :day")
    suspend fun pruneCounts(day: String)

    @Query("DELETE FROM app_errors WHERE atMillis < :atMillis")
    suspend fun pruneErrors(atMillis: Long)

    @Query("DELETE FROM usage_counts")
    suspend fun clearCounts()

    @Query("DELETE FROM app_errors")
    suspend fun clearErrors()

    @Query("SELECT * FROM notion_copies")
    suspend fun allCopies(): List<NotionCopyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCopy(copy: NotionCopyEntity)

    @Query("DELETE FROM notion_copies WHERE registrationId = :registrationId AND pageId = :pageId")
    suspend fun deleteCopy(registrationId: String, pageId: String)
}
