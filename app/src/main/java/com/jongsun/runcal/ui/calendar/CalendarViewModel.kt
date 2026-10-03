package com.jongsun.runcal.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jongsun.runcal.data.AppPreset
import com.jongsun.runcal.data.AppSettingsRepository
import com.jongsun.runcal.data.CalendarInfo
import com.jongsun.runcal.data.EventStyleChoice
import com.jongsun.runcal.data.parseCustomColorKey
import com.jongsun.runcal.data.recommendColors
import com.jongsun.runcal.data.room.EventTypeEntity
import com.jongsun.runcal.data.room.NotionGroupAssignmentEntity
import com.jongsun.runcal.data.EventGroupIndex
import com.jongsun.runcal.data.EventGroups
import com.jongsun.runcal.data.HiddenCalendars
import com.jongsun.runcal.data.CalendarRepository
import com.jongsun.runcal.data.backup.BackupPayload
import com.jongsun.runcal.data.backup.BackupRestoreService
import com.jongsun.runcal.data.backup.RestoreMode
import com.jongsun.runcal.data.backup.RestoreSummary
import com.jongsun.runcal.data.DEFAULT_APP_FONT_SCALE_STEP
import com.jongsun.runcal.data.DEFAULT_APP_PRESET
import com.jongsun.runcal.data.DEFAULT_APP_PRESET_ID
import com.jongsun.runcal.data.DEFAULT_NOTION_SYNC_INTERVAL_HOURS
import com.jongsun.runcal.data.DEFAULT_REMINDER_MINUTES
import com.jongsun.runcal.data.DEFAULT_WEEK_START_DAY
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.notion.NotionApiClient
import com.jongsun.runcal.data.notion.NotionChange
import com.jongsun.runcal.data.notion.NotionWriteResult
import com.jongsun.runcal.data.notion.NotionWriteService
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
import com.jongsun.runcal.data.special.SpecialDayFlags
import com.jongsun.runcal.data.special.SpecialDayPrefs
import com.jongsun.runcal.data.special.SpecialDaySnapshot
import com.jongsun.runcal.data.special.SpecialDayStore
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MONTH_ANCHOR_PAGE = 1200
const val MONTH_PAGE_COUNT = 2400
private const val DAY_ANCHOR_PAGE = 50000

/** 앱을 열 때 동기화하는 최소 간격(잠깐 나갔다 들어올 때마다 네트워크를 쓰지 않게). */
private const val APP_OPEN_SYNC_MIN_INTERVAL_MS = 10 * 60 * 1000L

/** 기기에 등록된 구글 계정들의 캘린더 동기화를 즉시 요청한다. 계정이 없거나 권한이 없으면 조용히 넘어간다. */
fun requestGoogleCalendarSync(context: android.content.Context) {
    runCatching {
        val extras = android.os.Bundle().apply {
            putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        android.accounts.AccountManager.get(context).getAccountsByType("com.google").forEach { account ->
            android.content.ContentResolver.requestSync(account, android.provider.CalendarContract.AUTHORITY, extras)
        }
    }
}
const val DAY_PAGE_COUNT = 100000

/** 월간/일간 화면이 공유하는 상태와 캐시. 화면 전환 시 재조회를 피하기 위해 월 단위로 이벤트를 캐시한다. */
class CalendarViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CalendarRepository(application)
    private val appSettingsRepository = AppSettingsRepository(application)
    private val db = RunCalDatabase.getInstance(application)
    private val notionApiClient = NotionApiClient()
    private val notionEventSource = NotionEventSource(db.notionEventDao(), db.notionDatabaseDao())
    private val eventRepository = EventRepository(repository, notionEventSource, groupIndex = { EventGroups.index(application) })
    private val notionSyncJob = NotionSyncJob(db.notionDatabaseDao(), db.notionEventDao(), notionApiClient)
    private val notionWriteService by lazy { NotionWriteService(application) }
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

    /** 숨긴 캘린더까지 포함한 전체 목록(설정 > 목록 관리 전용). 다른 화면은 [calendars]를 쓴다. */
    private val _allCalendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val allCalendars: StateFlow<List<CalendarInfo>> = _allCalendars.asStateFlow()

    private val _hiddenCalendarIds = MutableStateFlow<Set<Long>>(emptySet())
    val hiddenCalendarIds: StateFlow<Set<Long>> = _hiddenCalendarIds.asStateFlow()

    // null=이 종류 전체, 빈 집합=없음 — visibleCalendarIds와 같은 규약이되 기본값은 emptySet()
    // (opt-in). 프리셋을 통해서만 채워지고, 캘린더처럼 상시 노출되는 체크박스 목록은 없다.
    private val _visibleNotionDatabaseIds = MutableStateFlow<Set<String>?>(emptySet())
    val visibleNotionDatabaseIds: StateFlow<Set<String>?> = _visibleNotionDatabaseIds.asStateFlow()

    private val _notionSyncIntervalHours = MutableStateFlow(DEFAULT_NOTION_SYNC_INTERVAL_HOURS)
    val notionSyncIntervalHours: StateFlow<Int> = _notionSyncIntervalHours.asStateFlow()

    private val _notionDatabases = MutableStateFlow<List<NotionDatabaseEntity>>(emptyList())
    val notionDatabases: StateFlow<List<NotionDatabaseEntity>> = _notionDatabases.asStateFlow()

    private val _presetLinkEnabled = MutableStateFlow(true)
    val presetLinkEnabled: StateFlow<Boolean> = _presetLinkEnabled.asStateFlow()

    private val _remindersEnabled = MutableStateFlow(true)
    val remindersEnabled: StateFlow<Boolean> = _remindersEnabled.asStateFlow()

    private val _defaultReminderMinutes = MutableStateFlow<Int?>(DEFAULT_REMINDER_MINUTES)
    val defaultReminderMinutes: StateFlow<Int?> = _defaultReminderMinutes.asStateFlow()

    // sourceKey("calendar:<id>" | "notion:<registrationId>") → 스타일. 앱/위젯이 공유하는 Room에서
    // 읽으므로 설정 화면에서 바꾸면 위젯도 같은 값을 보게 된다.
    private val _eventColorStyles = MutableStateFlow<Map<String, EventColorStyleEntity>>(emptyMap())
    val eventColorStyles: StateFlow<Map<String, EventColorStyleEntity>> = _eventColorStyles.asStateFlow()

    private val _monthCache = MutableStateFlow<Map<YearMonth, List<EventItem>>>(emptyMap())
    val monthCache: StateFlow<Map<YearMonth, List<EventItem>>> = _monthCache.asStateFlow()

    private val _dataVersion = MutableStateFlow(0)

    /** 일정 데이터가 바뀔 때마다 증가한다. 월 캐시를 쓰지 않고 직접 조회하는 화면(목록)이 다시 읽는 신호로 쓴다. */
    val dataVersion: StateFlow<Int> = _dataVersion.asStateFlow()

    private val loadingMonths = mutableSetOf<YearMonth>()

    // 음력/공휴일/절기. 표시 설정은 위젯이 동기로 읽어야 해서 SharedPreferences(SpecialDayPrefs)에 두고,
    // 데이터는 Room 캐시의 메모리 스냅샷만 읽는다(네트워크는 WorkManager가 채운다).
    private val _specialFlags = MutableStateFlow(SpecialDayPrefs.load(application))
    val specialFlags: StateFlow<SpecialDayFlags> = _specialFlags.asStateFlow()

    private val _specialSnapshot = MutableStateFlow(SpecialDaySnapshot())
    val specialSnapshot: StateFlow<SpecialDaySnapshot> = _specialSnapshot.asStateFlow()

    init {
        viewModelScope.launch {
            // 캐시가 새로 채워질 때마다(version 증가) 스냅샷을 다시 읽어 화면에 반영한다.
            SpecialDayStore.version.collect {
                _specialSnapshot.value = SpecialDayStore.snapshot(getApplication())
                // 백업 복원이 표시 설정을 바꾼 경우에도 반영되도록 함께 다시 읽는다.
                _specialFlags.value = SpecialDayPrefs.load(getApplication())
            }
        }
        viewModelScope.launch {
            appSettingsRepository.settings.collect { settings ->
                val calendarFilterChanged = _visibleCalendarIds.value != settings.visibleCalendarIds
                val notionFilterChanged = _visibleNotionDatabaseIds.value != settings.visibleNotionDatabaseIds
                val weekStartChanged = _weekStartDay.value != settings.weekStartDay
                val activePresetBefore = _presets.value.firstOrNull { it.id == _activePresetId.value }
                val activePresetAfter = settings.presets.firstOrNull { it.id == settings.activePresetId }
                // 프리셋의 그룹·색 덮어쓰기가 바뀌면(프리셋 전환·편집) 같은 캘린더 필터여도 다시 읽어야 한다.
                val presetViewChanged = activePresetBefore?.groupIds != activePresetAfter?.groupIds ||
                    activePresetBefore?.overrideEventColor != activePresetAfter?.overrideEventColor ||
                    (activePresetAfter?.overrideEventColor == true && activePresetBefore?.colorArgb != activePresetAfter.colorArgb)
                _weekStartDay.value = settings.weekStartDay
                _visibleCalendarIds.value = settings.visibleCalendarIds
                _visibleNotionDatabaseIds.value = settings.visibleNotionDatabaseIds
                _appFontScaleStep.value = settings.fontScaleStep
                _presets.value = settings.presets
                _activePresetId.value = settings.activePresetId
                _notionSyncIntervalHours.value = settings.notionSyncIntervalHours
                _remindersEnabled.value = settings.remindersEnabled
                _presetLinkEnabled.value = settings.presetLinkEnabled
                _defaultReminderMinutes.value = settings.defaultReminderMinutes
                val hiddenChanged = _hiddenCalendarIds.value != settings.hiddenCalendarIds
                _hiddenCalendarIds.value = settings.hiddenCalendarIds
                if (hiddenChanged) {
                    HiddenCalendars.invalidate()
                    _calendars.value = _allCalendars.value.filter { it.id !in settings.hiddenCalendarIds }
                }
                // DataStore의 첫 값이 도착하기 전에 Monthly/Daily가 먼저 컴포지션되어
                // ensureMonthLoaded가 기본값(빈 Notion 필터)으로 먼저 캐시를 채워버릴 수 있다.
                // "최초 로드였는지"로 걸러내면 그 잘못 채워진 캐시를 영영 못 고치므로, 매번
                // 비교해서 실제로 달라졌을 때는(최초든 아니든) 무조건 무효화한다.
                if (calendarFilterChanged || notionFilterChanged || weekStartChanged || presetViewChanged || hiddenChanged) {
                    invalidateCache()
                    ensureMonthLoaded(_visibleYearMonth.value, force = true)
                }
            }
        }
        viewModelScope.launch { refreshCalendars() }
        viewModelScope.launch { refreshNotionDatabases() }
        // 앱 밖에서 캘린더가 바뀌면(구글 동기화로 PC에서 고친 일정이 내려온 경우 등) 화면 캐시도 다시 읽는다.
        // 동기화는 변경 알림을 연달아 보내므로 잠깐 모았다가 한 번만 처리한다.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        viewModelScope.launch {
            com.jongsun.runcal.CalendarObserverManager.changes.debounce(700).collect {
                invalidateCache()
                ensureMonthLoaded(_visibleYearMonth.value, force = true)
            }
        }
        viewModelScope.launch { refreshEventColorStyles(); refreshEventTypes() }
    }

    suspend fun refreshCalendars() {
        val all = repository.getCalendars()
        _allCalendars.value = all
        _calendars.value = all.filter { it.id !in _hiddenCalendarIds.value }
    }

    /** 목록에서 숨길 캘린더를 정한다. 숨긴 캘린더는 앱·위젯·프리셋·선택지 어디에도 나오지 않고 일정도 읽지 않는다. */
    suspend fun setHiddenCalendarIds(ids: Set<Long>) {
        appSettingsRepository.setHiddenCalendarIds(ids)
        RunCalWidgetRenderer.updateAllWidgets(getApplication())
    }

    suspend fun refreshNotionDatabases() {
        _notionDatabases.value = db.notionDatabaseDao().getAll()
    }

    suspend fun refreshEventColorStyles() {
        _eventColorStyles.value = db.eventColorStyleDao().getAll().associateBy { it.sourceKey }
    }

    private val _eventTypes = MutableStateFlow<List<EventTypeEntity>>(emptyList())
    val eventTypes: StateFlow<List<EventTypeEntity>> = _eventTypes.asStateFlow()

    suspend fun refreshEventTypes() {
        _eventTypes.value = db.eventTypeDao().getAll()
    }

    suspend fun saveEventType(type: EventTypeEntity) {
        db.eventTypeDao().upsert(type)
        afterGroupsChanged()
    }

    /** 그룹 색·규칙·직접 지정이 바뀌면 이미 그려진 일정 색과 프리셋 구성이 달라지므로 화면·위젯을 다시 읽는다. */
    private suspend fun afterGroupsChanged() {
        EventGroups.invalidate()
        refreshEventTypes()
        invalidateCache()
        ensureMonthLoaded(_visibleYearMonth.value, force = true)
        RunCalWidgetRenderer.updateAllWidgets(getApplication())
    }

    suspend fun groupIndex(): EventGroupIndex = EventGroups.index(getApplication())

    /** Notion 항목의 그룹을 직접 정한다. [typeId] null=직접 지정 해제(제목 규칙 적용), ""=그룹 없음. 앱 안에서만 쓰고 Notion에는 쓰지 않는다. */
    suspend fun setNotionGroup(event: EventItem, typeId: String?) {
        val registrationId = event.notionDatabaseId ?: return
        val pageId = event.notionPageId ?: return
        val dao = db.eventTypeDao()
        if (typeId == null) dao.deleteNotionAssignment(registrationId, pageId) else dao.assignNotion(NotionGroupAssignmentEntity(registrationId, pageId, typeId))
        afterGroupsChanged()
    }

    /**
     * 러닝용 기본 그룹 세 개를 만든다(이미 같은 이름이 있으면 건너뜀). 색은 지금 쓰이는 색(공휴일 캘린더 포함)과
     * 최대한 멀리 떨어진 추천색에서 고른다. 만든 개수를 돌려준다.
     */
    suspend fun createRunningGroups(): Int {
        val presets = listOf(
            "포인트훈련" to "RP, RACE, INT, TEMPO, MP",
            "이지훈련" to "EASY, REC, LSD",
            "보강·휴식" to "REST, STR, COR, PLY",
        )
        val existing = _eventTypes.value.map { it.name }.toSet()
        val toCreate = presets.filter { it.first !in existing }
        if (toCreate.isEmpty()) return 0
        val colors = recommendColors(usedColors(), count = toCreate.size)
        var order = (_eventTypes.value.maxOfOrNull { it.sortOrder } ?: 0) + 1
        toCreate.forEachIndexed { i, (name, keywords) ->
            db.eventTypeDao().upsert(
                EventTypeEntity(java.util.UUID.randomUUID().toString(), name, colors.getOrElse(i) { 0xFF1A73E8.toInt() }, null, null, order++, keywords),
            )
        }
        afterGroupsChanged()
        return toCreate.size
    }

    /** 유형을 지워도 이미 만든 일정의 색은 그대로 남는다(유형 표시만 사라짐). */
    suspend fun deleteEventType(id: String) {
        db.eventTypeDao().delete(id)
        db.eventTypeDao().deleteAssignmentsOfType(id)
        db.eventTypeDao().deleteNotionAssignmentsOfType(id)
        afterGroupsChanged()
    }

    suspend fun getEventTypeId(eventId: Long): String? = repository.getEventTypeId(eventId)

    /**
     * 색 선택에 쓸 "이미 쓰이는 색": 모든 캘린더 색, Notion DB 색, 소스 색 스타일로 바꾼 색, 다른 유형 색.
     * 추천색은 이것들과 최대한 멀리 떨어지게 고른다([recommendColors]).
     */
    fun usedColors(excludeTypeId: String? = null): List<Int> =
        // 앱이 직접 그리는 공휴일·절기 막대 색도 피한다(러닝 색이 공휴일과 헷갈리지 않게).
        listOf(com.jongsun.runcal.data.special.HOLIDAY_BAR_COLOR, com.jongsun.runcal.data.special.SOLAR_TERM_BAR_COLOR) +
            _calendars.value.map { it.color } +
            _presets.value.filter { it.overrideEventColor }.map { it.colorArgb } +
            _notionDatabases.value.map { it.colorArgb } +
            _eventColorStyles.value.values.mapNotNull { s ->
                parseCustomColorKey(s.paletteKey) ?: s.paletteKey?.let { k -> runCatching { com.jongsun.runcal.data.EventColorPaletteKey.valueOf(k).lightArgb }.getOrNull() }
            } +
            _eventTypes.value.filter { it.id != excludeTypeId }.map { it.colorArgb }

    /**
     * 소스 하나의 색상 스타일을 저장한다. [paletteKey]가 null이면 "시스템 기본"(오버라이드 해제).
     * 위젯도 같은 Room 값을 읽으므로, 앱 캐시엔 영향이 없지만(원본 event.color는 그대로 두고
     * 그리는 시점에만 해석) 위젯은 즉시 다시 그려줘야 화면에 반영된다.
     */
    suspend fun setEventColorStyle(sourceKey: String, paletteKey: String?, bold: Boolean) {
        db.eventColorStyleDao().upsert(EventColorStyleEntity(sourceKey, paletteKey, bold))
        com.jongsun.runcal.widget.EventColorStyleCache.invalidate()
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

    /** 이 달 그리드에 필요한 공휴일/절기/음력이 캐시에 없으면 백그라운드 조회를 예약한다(없는 동안은 빈 값으로 그림). */
    private fun requestSpecialDaysFor(yearMonth: YearMonth) {
        val flags = _specialFlags.value
        if (!flags.anyEnabled) return
        viewModelScope.launch {
            val (start, endExclusive) = monthGridDateRange(buildMonthGridWeeks(yearMonth, _weekStartDay.value))
            SpecialDayStore.requestMissing(getApplication(), SpecialDayStore.snapshot(getApplication()), start, endExclusive, flags)
        }
    }

    fun setSpecialFlags(flags: SpecialDayFlags) {
        SpecialDayPrefs.save(getApplication(), flags)
        _specialFlags.value = flags
        requestSpecialDaysFor(_visibleYearMonth.value)
        viewModelScope.launch { RunCalWidgetRenderer.updateAllWidgets(getApplication()) }
    }

    fun ensureMonthLoaded(yearMonth: YearMonth, force: Boolean = false) {
        requestSpecialDaysFor(yearMonth)
        if (!force && (_monthCache.value.containsKey(yearMonth) || yearMonth in loadingMonths)) return
        loadingMonths += yearMonth
        viewModelScope.launch {
            val weeks = buildMonthGridWeeks(yearMonth, _weekStartDay.value)
            val (start, endExclusive) = monthGridDateRange(weeks)
            val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
            val events = eventRepository.getEvents(startMillis, endMillis, currentSelection())
            _monthCache.update { it + (yearMonth to events) }
            loadingMonths -= yearMonth
        }
    }

    fun invalidateCache() {
        _monthCache.value = emptyMap()
        loadingMonths.clear()
        _dataVersion.value++
    }

    /**
     * 월 캐시를 거치지 않고 임의의 [startMillis, endMillis) 범위 이벤트를 직접 조회한다.
     * 주간표 내보내기처럼 월 경계를 넘나드는 범위를 한 번만 조회할 때 쓴다.
     * 표시 캘린더 필터(visibleCalendarIds)는 동일하게 적용된다.
     */
    suspend fun eventsInRange(startMillis: Long, endMillis: Long): List<EventItem> {
        return eventRepository.getEvents(startMillis, endMillis, currentSelection())
    }

    /** 지금 화면에 적용할 조회 조건: 표시 캘린더·Notion DB + 활성 프리셋의 일정그룹·색 덮어쓰기(P11). */
    private fun currentSelection(): SourceSelection {
        val preset = _presets.value.firstOrNull { it.id == _activePresetId.value }
        return SourceSelection(
            _visibleCalendarIds.value,
            _visibleNotionDatabaseIds.value,
            groupIds = preset?.groupIds,
            overrideColor = preset?.takeIf { it.overrideEventColor }?.colorArgb,
        )
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
        rrule: String? = null,
        style: EventStyleChoice? = null,
    ): Long {
        val id = repository.createEvent(calendarId, title, startMillis, endMillis, allDay, location, description, reminderMinutes, rrule = rrule, eventColor = style?.eventColor, eventTypeId = style?.typeId)
        if (id > 0) {
            invalidateCache()
            ensureMonthLoaded(_visibleYearMonth.value, force = true)
            RunCalWidgetRenderer.updateAllWidgets(getApplication())
            WorkScheduler.triggerReminderResyncNow(getApplication())
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
        rrule: String? = null,
        style: EventStyleChoice? = null,
    ): Int {
        val updated = repository.updateEvent(eventId, title, startMillis, endMillis, allDay, location, description, reminderMinutes, rrule = rrule,
            updateColor = style != null, eventColor = style?.eventColor, eventTypeId = style?.typeId,
        )
        if (updated > 0) {
            invalidateCache()
            ensureMonthLoaded(_visibleYearMonth.value, force = true)
            RunCalWidgetRenderer.updateAllWidgets(getApplication())
            WorkScheduler.triggerReminderResyncNow(getApplication())
        }
        return updated
    }

    suspend fun deleteLocalEvent(eventId: Long): Int {
        val deleted = repository.deleteEvent(eventId)
        if (deleted > 0) {
            invalidateCache()
            ensureMonthLoaded(_visibleYearMonth.value, force = true)
            RunCalWidgetRenderer.updateAllWidgets(getApplication())
            WorkScheduler.triggerReminderResyncNow(getApplication())
        }
        return deleted
    }

    suspend fun getReminders(eventId: Long): List<Int> = repository.getReminders(eventId)

    /**
     * "전체" 범위 수정 전용. Instances 조회로 얻은 [EventItem]의 begin/end는 탭한 그 회차의
     * 시각이라 마스터의 진짜 DTSTART/DURATION과 다를 수 있다 — 시리즈 전체를 시간 이동시킬 때
     * (탭한 회차의 새 시각 - 원래 시각)만큼 마스터의 진짜 시작 시각에 델타를 더해야 하므로,
     * 그 델타 계산의 기준값을 여기서 원본 Events 행을 다시 읽어 제공한다.
     */
    suspend fun getEventDetail(eventId: Long): EventItem? = repository.getEventById(eventId)

    /**
     * "이번만 수정" — 반복 회차 하나만 다른 내용으로 바꾸는 예외 이벤트를 만든다.
     * [originalInstanceBeginMillis]는 반드시 편집 전(사용자가 손대지 않은) 회차 값이어야 한다 —
     * CalendarContract가 이 값으로 어느 회차를 대체하는지 찾기 때문이다.
     */
    suspend fun createSingleOccurrenceException(
        masterEventId: Long,
        originalInstanceBeginMillis: Long,
        title: String,
        startMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        location: String,
        description: String,
        reminderMinutes: List<Int>,
        style: EventStyleChoice? = null,
    ): Long {
        val id = repository.createExceptionEvent(
            masterEventId, originalInstanceBeginMillis, title, startMillis, endMillis, allDay, location, description, reminderMinutes,
        )
        if (id > 0 && style != null) repository.updateEvent(id, updateColor = true, eventColor = style.eventColor, eventTypeId = style.typeId)
        if (id > 0) {
            invalidateCache()
            ensureMonthLoaded(_visibleYearMonth.value, force = true)
            RunCalWidgetRenderer.updateAllWidgets(getApplication())
            WorkScheduler.triggerReminderResyncNow(getApplication())
        }
        return id
    }

    /** "이번만 삭제" — 시각은 그대로 두고 해당 회차만 STATUS_CANCELED 예외로 감춘다. */
    suspend fun deleteSingleOccurrence(masterEventId: Long, originalInstanceBeginMillis: Long): Long {
        val id = repository.cancelSingleInstance(masterEventId, originalInstanceBeginMillis)
        if (id > 0) {
            invalidateCache()
            ensureMonthLoaded(_visibleYearMonth.value, force = true)
            RunCalWidgetRenderer.updateAllWidgets(getApplication())
            WorkScheduler.triggerReminderResyncNow(getApplication())
        }
        return id
    }

    /**
     * "이후 전체 수정" — 원본 시리즈를 [splitInstanceBeginMillis] 회차 직전에서 끊고, 그 회차부터는
     * (새 내용/새 반복 규칙으로) 새 시리즈를 만든다.
     */
    suspend fun updateFollowingOccurrences(
        masterEventId: Long,
        masterAllDay: Boolean,
        masterRrule: String,
        splitInstanceBeginMillis: Long,
        calendarId: Long,
        title: String,
        startMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        location: String,
        description: String,
        reminderMinutes: List<Int>,
        newRrule: String?,
        style: EventStyleChoice? = null,
    ): Long {
        // 새 시리즈는 따로 지정하지 않으면 원래 시리즈의 색·유형을 이어받는다.
        val inherited = style ?: EventStyleChoice(repository.getEventById(masterEventId)?.eventColor, repository.getEventTypeId(masterEventId))
        val truncated = repository.truncateSeriesBefore(masterEventId, masterRrule, splitInstanceBeginMillis, masterAllDay)
        if (!truncated) return -1L
        val newId = repository.createEvent(
            calendarId, title, startMillis, endMillis, allDay, location, description, reminderMinutes, rrule = newRrule,
            eventColor = inherited.eventColor, eventTypeId = inherited.typeId,
        )
        invalidateCache()
        ensureMonthLoaded(_visibleYearMonth.value, force = true)
        RunCalWidgetRenderer.updateAllWidgets(getApplication())
        WorkScheduler.triggerReminderResyncNow(getApplication())
        return newId
    }

    /** "이후 전체 삭제" — 새 시리즈 없이 원본 시리즈를 [splitInstanceBeginMillis] 회차 직전에서 끊기만 한다. */
    suspend fun deleteFollowingOccurrences(
        masterEventId: Long,
        masterAllDay: Boolean,
        masterRrule: String,
        splitInstanceBeginMillis: Long,
    ): Boolean {
        val truncated = repository.truncateSeriesBefore(masterEventId, masterRrule, splitInstanceBeginMillis, masterAllDay)
        if (truncated) {
            invalidateCache()
            ensureMonthLoaded(_visibleYearMonth.value, force = true)
            RunCalWidgetRenderer.updateAllWidgets(getApplication())
            WorkScheduler.triggerReminderResyncNow(getApplication())
        }
        return truncated
    }

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
        appSettingsRepository.applyPreset(preset)
        // 연동 중이면 고정하지 않은 위젯들이 새 프리셋을 따라간다.
        if (_presetLinkEnabled.value) RunCalWidgetRenderer.updateAllWidgets(getApplication())
    }

    /** 프리셋 정의는 앱·위젯 공용이라, 바꾸면 위젯을 항상 다시 그린다. */
    suspend fun savePresets(presets: List<AppPreset>) {
        appSettingsRepository.setPresets(presets)
        // 지금 보고 있는 프리셋을 고쳤으면 표시 캘린더·Notion 필터도 새 정의로 다시 적용한다(전엔 다른 프리셋으로 바꿨다 와야 반영됐다).
        val active = presets.firstOrNull { it.id == _activePresetId.value }
        if (active != null) appSettingsRepository.applyPreset(active)
        RunCalWidgetRenderer.updateAllWidgets(getApplication())
    }

    suspend fun setPresetLinkEnabled(enabled: Boolean) {
        appSettingsRepository.setPresetLinkEnabled(enabled)
        RunCalWidgetRenderer.updateAllWidgets(getApplication())
    }

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

    private var lastAppOpenSyncAt = 0L

    /**
     * 앱을 열(돌아올) 때마다 호출된다(P9). PC 등 다른 곳에서 바꾼 일정을 바로 보이게 한다:
     * 구글 계정 캘린더는 시스템 동기화를 요청하고(결과는 캘린더 옵저버가 반영), Notion은 마지막 동기화가
     * [APP_OPEN_SYNC_MIN_INTERVAL_MS]보다 오래됐으면 지금 다시 받는다. 너무 잦은 호출은 같은 간격으로 걸러낸다.
     */
    fun syncOnAppOpen() {
        val now = System.currentTimeMillis()
        if (now - lastAppOpenSyncAt < APP_OPEN_SYNC_MIN_INTERVAL_MS) return
        lastAppOpenSyncAt = now
        viewModelScope.launch {
            requestGoogleCalendarSync(getApplication())
            val stale = db.notionDatabaseDao().getAll().any { now - it.lastSyncedAtMillis > APP_OPEN_SYNC_MIN_INTERVAL_MS }
            if (stale) runCatching { syncAllNotionDatabases() }
        }
    }

    /** DB별 "앱에서 수정 허용"(P8). 끄면 날짜·상태 변경이 모두 막힌다. */
    suspend fun setNotionWriteEnabled(id: String, enabled: Boolean) {
        db.notionDatabaseDao().setWriteEnabled(id, enabled)
        refreshNotionDatabases()
    }

    /** Notion 항목의 날짜·상태를 바꾼다. 성공·충돌(재동기화됨) 모두 화면·위젯을 새로 그린다. */
    suspend fun applyNotionChange(event: EventItem, change: NotionChange): NotionWriteResult {
        val result = notionWriteService.apply(event, change)
        if (result !is NotionWriteResult.Failed) afterNotionWrite()
        return result
    }

    suspend fun undoNotionChange(undoId: String): NotionWriteResult {
        val result = notionWriteService.undo(undoId)
        if (result !is NotionWriteResult.Failed) afterNotionWrite()
        return result
    }

    private suspend fun afterNotionWrite() {
        refreshNotionDatabases()
        invalidateCache()
        ensureMonthLoaded(_visibleYearMonth.value, force = true)
        RunCalWidgetRenderer.updateAllWidgets(getApplication())
    }

    suspend fun setNotionSyncIntervalHours(hours: Int) {
        appSettingsRepository.setNotionSyncIntervalHours(hours)
        WorkScheduler.reschedulePeriodic(getApplication(), hours.toLong())
    }

    /** 알림 전체 on/off. 끄면 이미 걸린 알람도 즉시 정리한다(다음 재동기화까지 기다리지 않음). */
    suspend fun setRemindersEnabled(enabled: Boolean) {
        appSettingsRepository.setRemindersEnabled(enabled)
        if (enabled) {
            WorkScheduler.triggerReminderResyncNow(getApplication())
        } else {
            com.jongsun.runcal.notification.ReminderScheduler.cancelAll(getApplication())
        }
    }

    suspend fun setDefaultReminderMinutes(minutes: Int?) {
        appSettingsRepository.setDefaultReminderMinutes(minutes)
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
        refreshEventTypes()
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
