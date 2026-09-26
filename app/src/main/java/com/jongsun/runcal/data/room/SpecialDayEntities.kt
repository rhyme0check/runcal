package com.jongsun.runcal.data.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

const val SPECIAL_KIND_HOLIDAY = 0
const val SPECIAL_KIND_SOLAR_TERM = 1

/** 공휴일(대체공휴일 포함) 또는 24절기 하루. 위젯/앱은 이 테이블만 읽는다(네트워크 조회 없음). */
@Entity(tableName = "special_days", primaryKeys = ["epochDay", "kind", "name"])
data class SpecialDayEntity(
    val epochDay: Long,
    val kind: Int,
    val name: String,
    val isHoliday: Boolean,
)

/** 양력 하루에 대응하는 음력 날짜. */
@Entity(tableName = "lunar_days")
data class LunarDayEntity(
    @PrimaryKey val epochDay: Long,
    val lunarMonth: Int,
    val lunarDay: Int,
    val isLeap: Boolean,
)

/**
 * "이 연/월은 이미 받아왔다"는 장부. 결과가 0건인 해(아직 미공시)와 "안 받아본 해"를 구분하려면 데이터 행만으로는
 * 알 수 없어 따로 둔다. 키: "holiday:2026", "term:2026", "lunar:2026-09".
 */
@Entity(tableName = "special_fetch_status")
data class SpecialFetchStatusEntity(
    @PrimaryKey val key: String,
    val fetchedAtMillis: Long,
)

@Dao
abstract class SpecialDayDao {
    @Query("SELECT * FROM special_days")
    abstract suspend fun getAllSpecialDays(): List<SpecialDayEntity>

    @Query("SELECT * FROM lunar_days")
    abstract suspend fun getAllLunarDays(): List<LunarDayEntity>

    @Query("SELECT * FROM special_fetch_status")
    abstract suspend fun getAllStatus(): List<SpecialFetchStatusEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSpecialDays(rows: List<SpecialDayEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertLunarDays(rows: List<LunarDayEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertStatus(status: SpecialFetchStatusEntity)

    @Query("DELETE FROM special_days WHERE kind = :kind AND epochDay BETWEEN :fromEpochDay AND :toEpochDay")
    abstract suspend fun deleteSpecialDaysInRange(kind: Int, fromEpochDay: Long, toEpochDay: Long)

    /** 한 해치를 통째로 교체한다. 도중에 죽어도 이전 데이터가 그대로 남도록 하나의 트랜잭션으로 묶는다. */
    @Transaction
    open suspend fun replaceYear(kind: Int, fromEpochDay: Long, toEpochDay: Long, rows: List<SpecialDayEntity>, status: SpecialFetchStatusEntity) {
        deleteSpecialDaysInRange(kind, fromEpochDay, toEpochDay)
        insertSpecialDays(rows)
        upsertStatus(status)
    }

    @Transaction
    open suspend fun putLunarMonth(rows: List<LunarDayEntity>, status: SpecialFetchStatusEntity) {
        insertLunarDays(rows)
        upsertStatus(status)
    }
}
