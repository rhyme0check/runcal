package com.jongsun.runcal.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jongsun.runcal.data.AppSettingsRepository
import com.jongsun.runcal.data.CalendarInfo
import com.jongsun.runcal.data.CalendarRepository
import com.jongsun.runcal.data.DEFAULT_APP_FONT_SCALE_STEP
import com.jongsun.runcal.data.DEFAULT_WEEK_START_DAY
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.occursOn
import com.jongsun.runcal.widget.RunCalWidgetRenderer
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

    private val _calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val calendars: StateFlow<List<CalendarInfo>> = _calendars.asStateFlow()

    private val _monthCache = MutableStateFlow<Map<YearMonth, List<EventItem>>>(emptyMap())
    val monthCache: StateFlow<Map<YearMonth, List<EventItem>>> = _monthCache.asStateFlow()

    private val loadingMonths = mutableSetOf<YearMonth>()
    private var settingsInitialized = false

    init {
        viewModelScope.launch {
            appSettingsRepository.settings.collect { settings ->
                val calendarFilterChanged = _visibleCalendarIds.value != settings.visibleCalendarIds
                val weekStartChanged = _weekStartDay.value != settings.weekStartDay
                _weekStartDay.value = settings.weekStartDay
                _visibleCalendarIds.value = settings.visibleCalendarIds
                _appFontScaleStep.value = settings.fontScaleStep
                if (settingsInitialized && (calendarFilterChanged || weekStartChanged)) {
                    invalidateCache()
                    ensureMonthLoaded(_visibleYearMonth.value, force = true)
                }
                settingsInitialized = true
            }
        }
        viewModelScope.launch { refreshCalendars() }
    }

    suspend fun refreshCalendars() {
        _calendars.value = repository.getCalendars()
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
            val calendarIds = _visibleCalendarIds.value
            val events = if (calendarIds != null && calendarIds.isEmpty()) {
                emptyList()
            } else {
                repository.getEvents(startMillis, endMillis, calendarIds?.toList())
            }
            _monthCache.update { it + (yearMonth to events) }
            loadingMonths -= yearMonth
        }
    }

    fun invalidateCache() {
        _monthCache.value = emptyMap()
        loadingMonths.clear()
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

    fun pageForYearMonth(yearMonth: YearMonth): Int =
        MONTH_ANCHOR_PAGE + ChronoUnit.MONTHS.between(anchorYearMonth, yearMonth).toInt()

    fun yearMonthForPage(page: Int): YearMonth = anchorYearMonth.plusMonths((page - MONTH_ANCHOR_PAGE).toLong())

    fun pageForDate(date: LocalDate): Int = DAY_ANCHOR_PAGE + ChronoUnit.DAYS.between(today, date).toInt()

    fun dateForPage(page: Int): LocalDate = today.plusDays((page - DAY_ANCHOR_PAGE).toLong())
}
