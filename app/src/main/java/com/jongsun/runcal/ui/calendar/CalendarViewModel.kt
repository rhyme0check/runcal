package com.jongsun.runcal.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jongsun.runcal.data.AppPreset
import com.jongsun.runcal.data.AppSettingsRepository
import com.jongsun.runcal.data.CalendarInfo
import com.jongsun.runcal.data.CalendarRepository
import com.jongsun.runcal.data.backup.BackupPayload
import com.jongsun.runcal.data.backup.BackupRestoreService
import com.jongsun.runcal.data.backup.RestoreMode
import com.jongsun.runcal.data.backup.RestoreSummary
import com.jongsun.runcal.data.DEFAULT_APP_FONT_SCALE_STEP
import com.jongsun.runcal.data.DEFAULT_APP_PRESET
import com.jongsun.runcal.data.DEFAULT_APP_PRESET_ID
import com.jongsun.runcal.data.DEFAULT_NOTION_SYNC_INTERVAL_HOURS
import com.jongsun.runcal.data.DEFAULT_WEEK_START_DAY
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.notion.NotionApiClient
import com.jongsun.runcal.data.notion.NotionDatabaseSchemaResponse
import com.jongsun.runcal.data.occursOn
import com.jongsun.runcal.data.room.EventColorStyleEntity
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

    // sourceKey("calendar:<id>" | "notion:<registrationId>") → 스타일. 앱/위젯이 공유하는 Room에서
    // 읽으므로 설정 화면에서 바꾸면 위젯도 같은 값을 보게 된다.
    private val _eventColorStyles = MutableStateFlow<Map<String, EventColorStyleEntity>>(emptyMap())
    val eventColorStyles: StateFlow<Map<String, EventColorStyleEntity>> = _eventColorStyles.asStateFlow()

    private val _monthCache = MutableStateFlow<Map<YearMonth, List<EventItem>>>(emptyMap())
    val monthCache: StateFlow<Map<YearMonth, List<EventItem>>> = _monthCache.asStateFlow()

    private val loadingMonths = mutableSetOf<YearMonth>()

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
                // DataStore의 첫 값이 도착하기 전에 Monthly/Daily가 먼저 컴포지션되어
                // ensureMonthLoaded가 기본값(빈 Notion 필터)으로 먼저 캐시를 채워버릴 수 있다.
                // "최초 로드였는지"로 걸러내면 그 잘못 채워진 캐시를 영영 못 고치므로, 매번
                // 비교해서 실제로 달라졌을 때는(최초든 아니든) 무조건 무효화한다.
                if (calendarFilterChanged || notionFilterChanged || weekStartChanged) {
                    invalidateCache()
                    ensureMonthLoaded(_visibleYearMonth.value, force = true)
                }
            }
        }
        viewModelScope.launch { refreshCalendars() }
        viewModelScope.launch { refreshNotionDatabases() }
        viewModelScope.launch { refreshEventColorStyles() }
    }

    suspend fun refreshCalendars() {
        _calendars.value = repository.getCalendars()
    }

    suspend fun refreshNotionDatabases() {
        _notionDatabases.value = db.notionDatabaseDao().getAll()
    }

    suspend fun refreshEventColorStyles() {
        _eventColorStyles.value = db.eventColorStyleDao().getAll().associateBy { it.sourceKey }
    }

    /**
     * 소스 하나의 색상 스타일을 저장한다. [paletteKey]가 null이면 "시스템 기본"(오버라이드 해제).
     * 위젯도 같은 Room 값을 읽으므로, 앱 캐시엔 영향이 없지만(원본 event.color는 그대로 두고
     * 그리는 시점에만 해석) 위젯은 즉시 다시 그려줘야 화면에 반영된다.
     */
    suspend fun setEventColorStyle(sourceKey: String, paletteKey: String?, bold: Boolean) {
        db.eventColorStyleDao().upsert(EventColorStyleEntity(sourceKey, paletteKey, bold))
        refreshEventColorStyles()
        RunCalWidgetRenderer.updateAllWidgets(getApplication())
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

    /**
     * 캘린더만 순수하게 조회한다(Notion 섞지 않음) — 주간표 내보내기의 "캘린더" 소스 전용.
     * 표시할 캘린더 필터(visibleCalendarIds)는 그대로 적용한다.
     */
    suspend fun calendarEventsInRange(startMillis: Long, endMillis: Long): List<EventItem> {
        val calendarIds = _visibleCalendarIds.value
        return if (calendarIds != null && calendarIds.isEmpty()) {
            emptyList()
        } else {
            repository.getEvents(startMillis, endMillis, calendarIds?.toList())
        }
    }

    /**
     * 특정 Notion DB의 캐시(Room)만 읽는다 — 네트워크 조회 없음. 주간표 내보내기의 Notion 소스 전용.
     */
    suspend fun notionEventsInRangeForExport(
        registrationId: String,
        startMillis: Long,
        endMillis: Long,
    ) = db.notionEventDao().getEventsInRange(listOf(registrationId), startMillis, endMillis)

    /** [date]가 속한 달의 캐시에서 해당 날짜에 걸친 이벤트만 걸러낸다. */
    fun eventsForDate(cache: Map<YearMonth, List<EventItem>>, date: LocalDate): List<EventItem> =
        cache[YearMonth.from(date)].orEmpty().filter { it.occursOn(date, zone) }

    /**
     * P2 편집 화면 전용 래퍼. 데이터 계층(CalendarRepository)은 화면 상태를 모르므로, 여기서
     * 캐시 무효화 + 위젯 갱신까지 함께 처리한다 — registerNotionDatabase 등과 같은 패턴.
     */
    suspend fun createLocalEvent(
        calendarId: Long,
        title: String,
        startMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        location: String,
        description: String,
        reminderMinutes: List<Int>,
    ): Long {
        val id = repository.createEvent(calendarId, title, startMillis, endMillis, allDay, location, description, reminderMinutes)
        if (id > 0) {
            invalidateCache()
            ensureMonthLoaded(_visibleYearMonth.value, force = true)
            RunCalWidgetRenderer.updateAllWidgets(getApplication())
        }
        return id
    }

    suspend fun updateLocalEvent(
        eventId: Long,
        title: String,
        startMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        location: String,
        description: String,
        reminderMinutes: List<Int>,
    ): Int {
        val updated = repository.updateEvent(eventId, title, startMillis, endMillis, allDay, location, description, reminderMinutes)
        if (updated > 0) {
            invalidateCache()
            ensureMonthLoaded(_visibleYearMonth.value, force = true)
            RunCalWidgetRenderer.updateAllWidgets(getApplication())
        }
        return updated
    }

    suspend fun deleteLocalEvent(eventId: Long): Int {
        val deleted = repository.deleteEvent(eventId)
        if (deleted > 0) {
            invalidateCache()
            ensureMonthLoaded(_visibleYearMonth.value, force = true)
            RunCalWidgetRenderer.updateAllWidgets(getApplication())
        }
        return deleted
    }

    suspend fun getReminders(eventId: Long): List<Int> = repository.getReminders(eventId)

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

    /** 특정 DB 하나만 즉시 재동기화한다(등록 정보는 이미 존재, 재등록 없이 동기화만). */
    suspend fun syncNotionDatabase(registration: NotionDatabaseEntity): NotionSyncResult {
        val result = notionSyncJob.syncOne(registration)
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

    /**
     * 백업을 실제로 적용한다. 데이터 계층(BackupRestoreService)은 Room/DataStore/CalendarContract만
     * 건드리고 화면 상태는 모르므로, 여기서 캘린더/Notion/색상 스타일 목록과 캐시를 새로 고치고
     * 위젯도 즉시 다시 그린다 — registerNotionDatabase 등 다른 변경 함수들과 같은 패턴.
     */
    suspend fun restoreFromBackup(payload: BackupPayload, mode: RestoreMode): RestoreSummary {
        val summary = BackupRestoreService.restore(getApplication(), payload, mode)
        refreshCalendars()
        refreshNotionDatabases()
        refreshEventColorStyles()
        invalidateCache()
        ensureMonthLoaded(_visibleYearMonth.value, force = true)
        RunCalWidgetRenderer.updateAllWidgets(getApplication())
        return summary
    }

    fun pageForYearMonth(yearMonth: YearMonth): Int =
        MONTH_ANCHOR_PAGE + ChronoUnit.MONTHS.between(anchorYearMonth, yearMonth).toInt()

    fun yearMonthForPage(page: Int): YearMonth = anchorYearMonth.plusMonths((page - MONTH_ANCHOR_PAGE).toLong())

    fun pageForDate(date: LocalDate): Int = DAY_ANCHOR_PAGE + ChronoUnit.DAYS.between(today, date).toInt()

    fun dateForPage(page: Int): LocalDate = today.plusDays((page - DAY_ANCHOR_PAGE).toLong())
}
