package com.jongsun.runcal.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jongsun.runcal.data.AppPreset
import com.jongsun.runcal.data.AppSettingsRepository
import com.jongsun.runcal.data.CalendarInfo
import com.jongsun.runcal.data.CalendarRepository
import com.jongsun.runcal.data.DEFAULT_APP_FONT_SCALE_STEP
import com.jongsun.runcal.data.DEFAULT_APP_PRESET
import com.jongsun.runcal.data.DEFAULT_APP_PRESET_ID
import com.jongsun.runcal.data.DEFAULT_NOTION_SYNC_INTERVAL_HOURS
import com.jongsun.runcal.data.DEFAULT_WEEK_START_DAY
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.notion.NotionApiClient
import com.jongsun.runcal.data.notion.NotionDatabaseSchemaResponse
import com.jongsun.runcal.data.occursOn
import com.jongsun.runcal.data.room.NotionDatabaseEntity
import com.jongsun.runcal.data.room.RunCalDatabase
import com.jongsun.runcal.data.notion.NotionEventSource
import com.jongsun.runcal.data.source.EventRepository
import com.jongsun.runcal.data.source.SourceSelection
import com.jongsun.runcal.widget.RunCalWidgetRenderer
import com.jongsun.runcal.work.NotionSyncJob
import com.jongsun.runcal.work.NotionSyncResult
import com.jongsun.runcal.work.WorkScheduler
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MONTH_ANCHOR_PAGE = 1200
const val MONTH_PAGE_COUNT = 2400
private const val DAY_ANCHOR_PAGE = 50000
const val DAY_PAGE_COUNT = 100000

/** 월간/일간 화면이 공유하는 상태와 캐시. 화면 전환 시 재조회를 피하기 위해 월 단위로 이벤트를 캐시한다. */
class CalendarViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CalendarRepository(application)
    private val appSettingsRepository = AppSettingsRepository(application)
    private val db = RunCalDatabase.getInstance(application)
    private val notionApiClient = NotionApiClient()
    private val notionEventSource = NotionEventSource(db.notionEventDao(), db.notionDatabaseDao())
    private val eventRepository = EventRepository(repository, notionEventSource)
    private val notionSyncJob = NotionSyncJob(db.notionDatabaseDao(), db.notionEventDao(), notionApiClient)
    private val zone: ZoneId = ZoneId.systemDefault()

    val today: LocalDate = LocalDate.now()
    private val anchorYearMonth: YearMonth = YearMonth.from(today)

    private val _selectedDate = MutableStateFlow(today)
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _visibleYearMonth = MutableStateFlow(anchorYearMonth)
    val visibleYearMonth: StateFlow<YearMonth> = _visibleYearMonth.asStateFlow()

    private val _weekStartDay = MutableStateFlow(DEFAULT_WEEK_START_DAY)
    val weekStartDay: StateFlow<DayOfWeek> = _weekStartDay.asStateFlow()

    private val _visibleCalendarIds = MutableStateFlow<Set<Long>?>(null)
    val visibleCalendarIds: StateFlow<Set<Long>?> = _visibleCalendarIds.asStateFlow()

    private val _appFontScaleStep = MutableStateFlow(DEFAULT_APP_FONT_SCALE_STEP)
    val appFontScaleStep: StateFlow<Int> = _appFontScaleStep.asStateFlow()

    private val _presets = MutableStateFlow(listOf(DEFAULT_APP_PRESET))
    val presets: StateFlow<List<AppPreset>> = _presets.asStateFlow()

    private val _activePresetId = MutableStateFlow(DEFAULT_APP_PRESET_ID)
    val activePresetId: StateFlow<String> = _activePresetId.asStateFlow()

    private val _calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val calendars: StateFlow<List<CalendarInfo>> = _calendars.asStateFlow()

    // null=이 종류 전체, 빈 집합=없음 — visibleCalendarIds와 같은 규약이되 기본값은 emptySet()
    // (opt-in). 프리셋을 통해서만 채워지고, 캘린더처럼 상시 노출되는 체크박스 목록은 없다.
    private val _visibleNotionDatabaseIds = MutableStateFlow<Set<String>?>(emptySet())
    val visibleNotionDatabaseIds: StateFlow<Set<String>?> = _visibleNotionDatabaseIds.asStateFlow()

    private val _notionSyncIntervalHours = MutableStateFlow(DEFAULT_NOTION_SYNC_INTERVAL_HOURS)
    val notionSyncIntervalHours: StateFlow<Int> = _notionSyncIntervalHours.asStateFlow()

    private val _notionDatabases = MutableStateFlow<List<NotionDatabaseEntity>>(emptyList())
    val notionDatabases: StateFlow<List<NotionDatabaseEntity>> = _notionDatabases.asStateFlow()

    private val _monthCache = MutableStateFlow<Map<YearMonth, List<EventItem>>>(emptyMap())
    val monthCache: StateFlow<Map<YearMonth, List<EventItem>>> = _monthCache.asStateFlow()

    private val loadingMonths = mutableSetOf<YearMonth>()
    private var settingsInitialized = false

    init {
        viewModelScope.launch {
            appSettingsRepository.settings.collect { settings ->
                val calendarFilterChanged = _visibleCalendarIds.value != settings.visibleCalendarIds
                val notionFilterChanged = _visibleNotionDatabaseIds.value != settings.visibleNotionDatabaseIds
                val weekStartChanged = _weekStartDay.value != settings.weekStartDay
                _weekStartDay.value = settings.weekStartDay
                _visibleCalendarIds.value = settings.visibleCalendarIds
                _visibleNotionDatabaseIds.value = settings.visibleNotionDatabaseIds
                _appFontScaleStep.value = settings.fontScaleStep
                _presets.value = settings.presets
                _activePresetId.value = settings.activePresetId
                _notionSyncIntervalHours.value = settings.notionSyncIntervalHours
                if (settingsInitialized && (calendarFilterChanged || notionFilterChanged || weekStartChanged)) {
                    invalidateCache()
                    ensureMonthLoaded(_visibleYearMonth.value, force = true)
                }
                settingsInitialized = true
            }
        }
        viewModelScope.launch { refreshCalendars() }
        viewModelScope.launch { refreshNotionDatabases() }
    }

    suspend fun refreshCalendars() {
        _calendars.value = repository.getCalendars()
    }

    suspend fun refreshNotionDatabases() {
        _notionDatabases.value = db.notionDatabaseDao().getAll()
    }

    fun setVisibleYearMonth(yearMonth: YearMonth) {
        _visibleYearMonth.value = yearMonth
    }

    /** 날짜를 탭하거나 점프 다이얼로그 등으로 특정 날짜를 명시적으로 선택한다. */
    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
        _visibleYearMonth.value = YearMonth.from(date)
    }

    /** 일간 화면 스와이프로 페이지가 바뀔 때 호출 — 월간 화면과 동기화되도록 선택 날짜만 갱신한다. */
    fun selectDateFromPaging(date: LocalDate) {
        _selectedDate.value = date
    }

    fun requestJumpToday() {
        selectDate(today)
    }

    fun requestJumpToMonth(yearMonth: YearMonth) {
        selectDate(yearMonth.atDay(1))
    }

    fun ensureMonthLoaded(yearMonth: YearMonth, force: Boolean = false) {
        if (!force && (_monthCache.value.containsKey(yearMonth) || yearMonth in loadingMonths)) return
        loadingMonths += yearMonth
        viewModelScope.launch {
            val weeks = buildMonthGridWeeks(yearMonth, _weekStartDay.value)
            val (start, endExclusive) = monthGridDateRange(weeks)
            val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
            val selection = SourceSelection(_visibleCalendarIds.value, _visibleNotionDatabaseIds.value)
            val events = eventRepository.getEvents(startMillis, endMillis, selection)
            _monthCache.update { it + (yearMonth to events) }
            loadingMonths -= yearMonth
        }
    }

    fun invalidateCache() {
        _monthCache.value = emptyMap()
        loadingMonths.clear()
    }

    /**
     * 월 캐시를 거치지 않고 임의의 [startMillis, endMillis) 범위 이벤트를 직접 조회한다.
     * 주간표 내보내기처럼 월 경계를 넘나드는 범위를 한 번만 조회할 때 쓴다.
     * 표시 캘린더 필터(visibleCalendarIds)는 동일하게 적용된다.
     */
    suspend fun eventsInRange(startMillis: Long, endMillis: Long): List<EventItem> {
        val selection = SourceSelection(_visibleCalendarIds.value, _visibleNotionDatabaseIds.value)
        return eventRepository.getEvents(startMillis, endMillis, selection)
    }

    /** [date]가 속한 달의 캐시에서 해당 날짜에 걸친 이벤트만 걸러낸다. */
    fun eventsForDate(cache: Map<YearMonth, List<EventItem>>, date: LocalDate): List<EventItem> =
        cache[YearMonth.from(date)].orEmpty().filter { it.occursOn(date, zone) }

    /** 개발/테스트 도구: 로컬 테스트 캘린더를 만들고 위젯/앱 화면을 모두 갱신한다. */
    suspend fun ensureLocalTestCalendar(): Long {
        val id = repository.ensureLocalTestCalendar()
        refreshCalendars()
        RunCalWidgetRenderer.updateAllWidgets(getApplication())
        return id
    }

    /** 개발/테스트 도구: 샘플 일정을 추가하고 캐시를 갱신해 화면에 바로 반영한다. */
    suspend fun addSampleEvents(calendarId: Long, count: Int = 10): Int {
        val inserted = repository.addSampleEvents(calendarId, count)
        invalidateCache()
        ensureMonthLoaded(_visibleYearMonth.value, force = true)
        RunCalWidgetRenderer.updateAllWidgets(getApplication())
        return inserted
    }

    suspend fun setVisibleCalendarIds(ids: Set<Long>?) = appSettingsRepository.setVisibleCalendarIds(ids)

    suspend fun setWeekStartDay(day: DayOfWeek) = appSettingsRepository.setWeekStartDay(day)

    suspend fun setAppFontScaleStep(step: Int) = appSettingsRepository.setFontScaleStep(step)

    /**
     * 프리셋을 적용한다 — 표시 캘린더/Notion DB를 프리셋 값으로 덮어쓰고 활성 프리셋으로 표시한다.
     * preset.notionDatabaseIds의 null(="Notion 없음", 프리셋 쪽 관례)을 visibleNotionDatabaseIds의
     * emptySet()으로 변환해서 넘긴다 — 그대로 null을 넘기면 "전체 Notion DB"로 해석돼버린다.
     */
    suspend fun applyPreset(preset: AppPreset) {
        appSettingsRepository.setVisibleCalendarIds(preset.calendarIds)
        appSettingsRepository.setVisibleNotionDatabaseIds(preset.notionDatabaseIds ?: emptySet())
        appSettingsRepository.setActivePresetId(preset.id)
    }

    suspend fun savePresets(presets: List<AppPreset>) = appSettingsRepository.setPresets(presets)

    suspend fun fetchNotionSchema(notionDatabaseId: String): NotionDatabaseSchemaResponse =
        notionApiClient.retrieveDatabase(notionDatabaseId)

    /** DB를 등록/수정하고 즉시 1회 동기화한다. 결과(성공 여부·건수)를 UI가 바로 보여줄 수 있게 반환한다. */
    suspend fun registerNotionDatabase(entity: NotionDatabaseEntity): NotionSyncResult {
        db.notionDatabaseDao().upsert(entity)
        val result = notionSyncJob.syncOne(entity)
        refreshNotionDatabases()
        invalidateCache()
        ensureMonthLoaded(_visibleYearMonth.value, force = true)
        RunCalWidgetRenderer.updateAllWidgets(getApplication())
        return result
    }

    /** "지금 동기화" — 등록된 모든 DB를 즉시(동기적으로) 재동기화한다. */
    suspend fun syncAllNotionDatabases(): List<NotionSyncResult> {
        val results = notionSyncJob.syncAll()
        refreshNotionDatabases()
        invalidateCache()
        ensureMonthLoaded(_visibleYearMonth.value, force = true)
        RunCalWidgetRenderer.updateAllWidgets(getApplication())
        return results
    }

    suspend fun deleteNotionDatabase(id: String) {
        db.notionDatabaseDao().deleteById(id)
        refreshNotionDatabases()
        invalidateCache()
        ensureMonthLoaded(_visibleYearMonth.value, force = true)
        RunCalWidgetRenderer.updateAllWidgets(getApplication())
    }

    suspend fun setNotionSyncIntervalHours(hours: Int) {
        appSettingsRepository.setNotionSyncIntervalHours(hours)
        WorkScheduler.reschedulePeriodic(getApplication(), hours.toLong())
    }

    fun pageForYearMonth(yearMonth: YearMonth): Int =
        MONTH_ANCHOR_PAGE + ChronoUnit.MONTHS.between(anchorYearMonth, yearMonth).toInt()

    fun yearMonthForPage(page: Int): YearMonth = anchorYearMonth.plusMonths((page - MONTH_ANCHOR_PAGE).toLong())

    fun pageForDate(date: LocalDate): Int = DAY_ANCHOR_PAGE + ChronoUnit.DAYS.between(today, date).toInt()

    fun dateForPage(page: Int): LocalDate = today.plusDays((page - DAY_ANCHOR_PAGE).toLong())
}
