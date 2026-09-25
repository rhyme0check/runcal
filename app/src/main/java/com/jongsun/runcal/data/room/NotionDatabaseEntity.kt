package com.jongsun.runcal.data.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 사용자가 등록한 Notion DB 하나와 그 속성 매핑. [id]는 우리 내부 UUID(프리셋/SourceRef가
 * 참조)이고 [notionDatabaseId]는 Notion 쪽 실제 DB id다. 스키마 재검증에 대비해
 * 마지막으로 확인한 속성 이름/타입([schemaJson])과 동기화 상태를 함께 들고 있는다.
 */
@Entity(tableName = "notion_databases")
data class NotionDatabaseEntity(
    @PrimaryKey val id: String,
    val notionDatabaseId: String,
    val displayName: String,
    val colorArgb: Int,
    val iconEmojiOrUrl: String?,
    val dateProperty: String,
    val titleProperty: String,
    val subtitleProperty: String?,
    val statusProperty: String?,
    val schemaJson: String,
    val lastSchemaCheckedAtMillis: Long,
    val lastSyncedAtMillis: Long,
    /** "OK" | "ERROR" | "SCHEMA_INVALID" | "PENDING"(등록 직후, 아직 한 번도 동기화 안 됨) */
    val lastSyncStatus: String,
    val lastSyncError: String?,
    val createdAtMillis: Long,
)

@Dao
interface NotionDatabaseDao {
    @Query("SELECT * FROM notion_databases")
    suspend fun getAll(): List<NotionDatabaseEntity>

    @Query("SELECT * FROM notion_databases WHERE id = :id")
    suspend fun getById(id: String): NotionDatabaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NotionDatabaseEntity)

    @Query("DELETE FROM notion_databases WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        "UPDATE notion_databases SET lastSyncedAtMillis = :atMillis, lastSyncStatus = :status, lastSyncError = :error WHERE id = :id",
    )
    suspend fun updateSyncResult(id: String, atMillis: Long, status: String, error: String?)
}
