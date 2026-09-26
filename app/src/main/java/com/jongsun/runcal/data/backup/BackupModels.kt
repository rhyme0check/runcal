package com.jongsun.runcal.data.backup

import com.jongsun.runcal.data.AppPreset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 기념일 추가 등 스키마가 실제로 바뀔 때만 올린다. 지금은 기념일 미구현이라 1로 시작.
 * 이 값을 올릴 땐 [BackupPayload]에 필드를 "추가"만 하고 기존 필드는 건드리지 않아야
 * (kotlinx.serialization 기본값 덕분에) 구버전 백업 파일도 계속 복원 가능하다.
 */
const val CURRENT_BACKUP_FORMAT_VERSION = 1

/** 쓰기/읽기 양쪽에서 공유하는 Json 설정 — 미래 포맷 필드가 늘어나도 구버전 앱이 깨지지 않게. */
val backupJson = Json { ignoreUnknownKeys = true }

@Serializable
enum class BackupType { DAILY, MANUAL, MONTHLY }

/**
 * 앱 화면 전용 표시 설정 스냅샷. [AppSettings]를 그대로 재사용하지 않고 별도 DTO로 두는 이유는
 * [DayOfWeek]가 kotlinx.serialization 기본 지원 타입이 아니라 이름(String)으로 담아야 해서다.
 */
@Serializable
data class BackupAppSettings(
    val visibleCalendarIds: Set<Long>?,
    val visibleNotionDatabaseIds: Set<String>?,
    val weekStartDayName: String,
    val fontScaleStep: Int,
    val activePresetId: String,
    val notionSyncIntervalHours: Int,
    // 음력/공휴일/절기 표시. null = 이 필드가 생기기 전의 구버전 백업 → 복원 시 현재 값을 그대로 둔다.
    val showLunar: Boolean? = null,
    val showHolidays: Boolean? = null,
    val showSolarTerms: Boolean? = null,
)

/**
 * 위젯 인스턴스 하나의 설정 스냅샷. [widgetKindHint]는 표시 전용이다 — 복원이 홈 화면에 새
 * 위젯을 배치할 수는 없으므로(Android API 한계), 백업 당시의 [appWidgetId]가 현재 기기에 없으면
 * 이 항목은 건수만 세고 건너뛴다.
 */
@Serializable
data class BackupWidgetInstance(
    val appWidgetId: Int,
    val widgetKindHint: String,
    val presets: List<com.jongsun.runcal.widget.WidgetPreset>,
    val currentPresetIndex: Int,
    val fontScaleStep: Int,
    val backgroundOpacity: Float,
    val showWeekNumber: Boolean,
    // 위젯별 음력 표시(4x5 확장 위젯 전용). null = 구버전 백업 → 복원 시 현재 값 유지.
    val showLunar: Boolean? = null,
)

/**
 * Notion DB 등록/매핑 정보만 담는다 — 동기화 상태(schemaJson/lastSyncedAtMillis/...)는 캐시성
 * 데이터라 백업에 넣지 않는다(복원 후 다시 동기화하면 저절로 채워짐).
 */
@Serializable
data class BackupNotionDatabase(
    val id: String,
    val notionDatabaseId: String,
    val displayName: String,
    val colorArgb: Int,
    val iconEmojiOrUrl: String?,
    val dateProperty: String,
    val titleProperty: String,
    val subtitleProperty: String?,
    val statusProperty: String?,
)

@Serializable
data class BackupEventColorStyle(
    val sourceKey: String,
    val paletteKey: String?,
    val bold: Boolean,
)

/**
 * 로컬(RunCal이 만든) 캘린더 일정 스냅샷. [calendarEventId]는 참고용일 뿐 복원 매칭 키로
 * 신뢰하지 않는다(provider가 발급하는 id는 재사용될 수 있음) — 실제 매칭은
 * (title, startMillis, endMillis) 조합으로 한다.
 */
@Serializable
data class BackupLocalEvent(
    val calendarEventId: Long,
    val calendarId: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String,
)

@Serializable
data class BackupPayload(
    val formatVersion: Int = CURRENT_BACKUP_FORMAT_VERSION,
    val createdAtMillis: Long,
    val backupType: BackupType,
    val appSettings: BackupAppSettings,
    val appPresets: List<AppPreset>,
    val widgetInstances: List<BackupWidgetInstance>,
    val notionDatabases: List<BackupNotionDatabase>,
    val eventColorStyles: List<BackupEventColorStyle>,
    val localEvents: List<BackupLocalEvent>,
)
