package com.jongsun.runcal.data.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Notion 페이지(=행) 하나를 캐시한 것. 위젯/앱은 이 테이블만 읽는다 — 렌더링 경로에서
 * Notion API를 절대 직접 호출하지 않는다(200ms 반응성 예산 유지).
 */
@Entity(
    tableName = "notion_events",
    primaryKeys = ["registrationId", "notionPageId"],
    foreignKeys = [
        ForeignKey(
            entity = NotionDatabaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["registrationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["registrationId", "startMillis", "endMillis"])],
)
data class NotionEventEntity(
    val registrationId: String,
    val notionPageId: String,
    val title: String,
    val subtitle: String?,
    val statusRaw: String?,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val notionUrl: String,
    val lastEditedTimeIso: String,
    val fetchedAtMillis: Long,
)

@Dao
interface NotionEventDao {
    @Query(
        "SELECT * FROM notion_events WHERE registrationId IN (:registrationIds) " +
            "AND startMillis < :endMillis AND endMillis > :startMillis",
    )
    suspend fun getEventsInRange(registrationIds: List<String>, startMillis: Long, endMillis: Long): List<NotionEventEntity>

    @Query("SELECT * FROM notion_events WHERE startMillis < :endMillis AND endMillis > :startMillis")
    suspend fun getEventsInRangeAllDbs(startMillis: Long, endMillis: Long): List<NotionEventEntity>

    @Query("DELETE FROM notion_events WHERE registrationId = :registrationId")
    suspend fun deleteAllForDatabase(registrationId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<NotionEventEntity>)

    /** 한 DB의 캐시를 통째로 갈아끼운다 — 동기화 도중 죽어도 다른 DB 캐시는 영향받지 않는다. */
    @Transaction
    suspend fun replaceForDatabase(registrationId: String, events: List<NotionEventEntity>) {
        deleteAllForDatabase(registrationId)
        insertAll(events)
    }
}
