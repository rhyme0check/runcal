package com.jongsun.runcal.data.special

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.jongsun.runcal.data.room.LunarDayEntity
import com.jongsun.runcal.data.room.RunCalDatabase
import com.jongsun.runcal.data.room.SPECIAL_KIND_HOLIDAY
import com.jongsun.runcal.data.room.SPECIAL_KIND_SOLAR_TERM
import com.jongsun.runcal.data.room.SpecialDayEntity
import com.jongsun.runcal.data.room.SpecialFetchStatusEntity
import com.jongsun.runcal.work.SpecialDaySyncWorker
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

private const val TAG = "RunCal"

/** 공휴일/절기는 임시공휴일 지정 등으로 나중에 바뀔 수 있어 이 기간이 지나면 다시 받는다(음력은 불변). */
private const val REFRESH_AFTER_MILLIS = 30L * 24 * 60 * 60 * 1000

const val SPECIAL_SYNC_KEY_INPUT = "key"

/**
 * 공휴일/절기/음력 캐시의 메모리 스냅샷과 "받아와야 할 것" 판단. 렌더링 경로는 [snapshot]만 부르고, 네트워크는
 * 절대 여기서 직접 하지 않는다 — 없는 연/월은 WorkManager에 맡기고 그 사이엔 빈 값으로 그린다.
 */
object SpecialDayStore {

    /** 캐시가 바뀔 때마다 증가한다. 앱 화면이 이걸 보고 스냅샷을 다시 읽는다. */
    val version = MutableStateFlow(0)

    @Volatile private var cached: SpecialDaySnapshot? = null
    private val requested = ConcurrentHashMap.newKeySet<String>()

    suspend fun snapshot(context: Context): SpecialDaySnapshot {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            val dao = RunCalDatabase.getInstance(context).specialDayDao()
            val holidays = HashMap<LocalDate, MutableList<String>>()
            val terms = HashMap<LocalDate, String>()
            dao.getAllSpecialDays().forEach { row ->
                val date = LocalDate.ofEpochDay(row.epochDay)
                if (row.kind == SPECIAL_KIND_HOLIDAY) {
                    if (row.isHoliday) holidays.getOrPut(date) { mutableListOf() } += row.name
                } else {
                    terms[date] = row.name
                }
            }
            val lunar = HashMap<LocalDate, LunarDate>()
            dao.getAllLunarDays().forEach { row ->
                lunar[LocalDate.ofEpochDay(row.epochDay)] = LunarDate(row.lunarMonth, row.lunarDay, row.isLeap)
            }
            val fetched = dao.getAllStatus().associate { it.key to it.fetchedAtMillis }
            SpecialDaySnapshot(holidays, terms, lunar, fetched).also { cached = it }
        }
    }

    fun invalidate() {
        cached = null
        version.update { it + 1 }
    }

    /** [start, endExclusive) 그리드를 그리는 데 필요한, 켜져 있는 항목의 캐시 키들. */
    fun requiredKeys(start: LocalDate, endExclusive: LocalDate, flags: SpecialDayFlags): List<String> {
        val keys = LinkedHashSet<String>()
        var date = start
        while (date < endExclusive) {
            if (flags.showHolidays) keys += holidayKey(date.year)
            if (flags.showSolarTerms) keys += termKey(date.year)
            if (flags.showLunar) keys += lunarKey(YearMonth.from(date))
            date = date.plusMonths(1).withDayOfMonth(1).takeIf { it <= endExclusive.minusDays(1) } ?: break
        }
        // 위 루프는 월 단위로 건너뛰므로 마지막 날이 속한 달/해도 확실히 포함한다.
        val last = endExclusive.minusDays(1)
        if (flags.showHolidays) keys += holidayKey(last.year)
        if (flags.showSolarTerms) keys += termKey(last.year)
        if (flags.showLunar) {
            keys += lunarKey(YearMonth.from(last))
            // 그믐 판정에 마지막 칸의 "다음 날" 음력이 필요하다(그리드 끝이 월말이면 다음 달 데이터).
            keys += lunarKey(YearMonth.from(endExclusive))
        }
        return keys.toList()
    }

    /**
     * 그리드 범위에 아직 못 받은 게 있으면 백그라운드 조회를 예약한다(같은 키는 프로세스당 한 번만). 이 함수 자체는
     * 메모리 비교뿐이라 렌더링 경로에서 불러도 싸다.
     */
    fun requestMissing(context: Context, snapshot: SpecialDaySnapshot, start: LocalDate, endExclusive: LocalDate, flags: SpecialDayFlags) {
        requiredKeys(start, endExclusive, flags)
            .filter { it !in snapshot.fetched && requested.add(it) }
            .forEach { enqueueKey(context, it) }
    }

    private fun enqueueKey(context: Context, key: String) {
        val request = OneTimeWorkRequestBuilder<SpecialDaySyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(SPECIAL_SYNC_KEY_INPUT to key))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("special_day_fetch_$key", ExistingWorkPolicy.KEEP, request)
    }

    /** 오류나 실패 후 다음 렌더에서 다시 예약될 수 있게 요청 기록에서 뺀다. */
    internal fun forgetRequest(key: String) {
        requested.remove(key)
    }
}

/** 네트워크 → Room. 반드시 백그라운드에서만 호출한다. */
object SpecialDaySync {
    private val api by lazy { SpecialDayApiClient() }

    /** 올해+내년의 공휴일·절기·음력 전체(앱 최초 실행 선반입). 이미 받은 건 건너뛴다. 하나라도 실패하면 false. */
    suspend fun prefetch(context: Context, today: LocalDate = LocalDate.now()): Boolean {
        val snapshot = SpecialDayStore.snapshot(context)
        val now = System.currentTimeMillis()
        val keys = ArrayList<String>()
        for (year in listOf(today.year, today.year + 1)) {
            for (key in listOf(holidayKey(year), termKey(year))) {
                val fetchedAt = snapshot.fetched[key]
                if (fetchedAt == null || now - fetchedAt > REFRESH_AFTER_MILLIS) keys += key
            }
            for (month in 1..12) {
                val key = lunarKey(YearMonth.of(year, month))
                if (key !in snapshot.fetched) keys += key
            }
        }
        var allOk = true
        for (key in keys) {
            if (!fetchKeySafely(context, key)) allOk = false
        }
        return allOk
    }

    suspend fun fetchKeySafely(context: Context, key: String): Boolean = try {
        fetchKey(context, key)
        true
    } catch (e: Exception) {
        Log.w(TAG, "SpecialDaySync: fetch failed key=$key: ${e.message}")
        false
    }

    suspend fun fetchKey(context: Context, key: String) = withContext(Dispatchers.IO) {
        val dao = RunCalDatabase.getInstance(context).specialDayDao()
        val now = System.currentTimeMillis()
        val status = SpecialFetchStatusEntity(key, now)
        val (type, arg) = key.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        when (type) {
            "holiday", "term" -> {
                val year = arg.toInt()
                val isHolidayKind = type == "holiday"
                val items = if (isHolidayKind) api.fetchHolidays(year) else api.fetchSolarTerms(year)
                val kind = if (isHolidayKind) SPECIAL_KIND_HOLIDAY else SPECIAL_KIND_SOLAR_TERM
                val rows = items.map { SpecialDayEntity(it.date.toEpochDay(), kind, it.name, it.isHoliday) }
                dao.replaceYear(kind, LocalDate.of(year, 1, 1).toEpochDay(), LocalDate.of(year, 12, 31).toEpochDay(), rows, status)
                Log.d(TAG, "SpecialDaySync: $key fetched ${rows.size} row(s)")
            }
            "lunar" -> {
                val items = api.fetchLunarMonth(YearMonth.parse(arg))
                val rows = items.map { LunarDayEntity(it.date.toEpochDay(), it.month, it.day, it.isLeap) }
                dao.putLunarMonth(rows, status)
                Log.d(TAG, "SpecialDaySync: $key fetched ${rows.size} row(s)")
            }
            else -> throw IllegalArgumentException("unknown special-day key: $key")
        }
        SpecialDayStore.invalidate()
    }
}
