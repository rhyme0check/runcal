package com.jongsun.runcal.data.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 소스(캘린더/Notion DB) 하나에 대한 색상 스타일 오버라이드. [sourceKey]는
 * "calendar:<calendarId>" 또는 "notion:<registrationId>" 형식(data.EventColorStyle.kt 참고).
 * 앱(DataStore)과 위젯(SharedPreferences)이 서로 다른 저장소를 쓰는 것과 달리, 이 매핑만은
 * 둘 다 이미 공유하는 Room에 둬서 앱/위젯이 항상 같은 색을 그리도록 한다.
 */
@Entity(tableName = "event_color_styles")
data class EventColorStyleEntity(
    @PrimaryKey val sourceKey: String,
    /** null = 시스템 기본(원본 색 + 다크모드 자동 보정). 그 외엔 EventColorPaletteKey.name. */
    val paletteKey: String?,
    val bold: Boolean = false,
)

@Dao
interface EventColorStyleDao {
    @Query("SELECT * FROM event_color_styles")
    suspend fun getAll(): List<EventColorStyleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EventColorStyleEntity)

    @Query("DELETE FROM event_color_styles WHERE sourceKey = :sourceKey")
    suspend fun deleteBySourceKey(sourceKey: String)
}
