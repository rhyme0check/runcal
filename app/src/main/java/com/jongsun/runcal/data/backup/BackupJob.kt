package com.jongsun.runcal.data.backup

import android.content.Context
import android.net.Uri
import com.jongsun.runcal.data.AppSettingsRepository
import com.jongsun.runcal.data.CalendarRepository
import com.jongsun.runcal.data.room.RunCalDatabase
import com.jongsun.runcal.widget.WidgetKind
import com.jongsun.runcal.widget.enumeratePlacedWidgetIds
import com.jongsun.runcal.widget.loadWidgetFilterSettings
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 파일명에 박아넣는 타임스탬프 — 보존 정리(7일)가 파일 메타데이터가 아니라 이 값을 기준으로 판단한다. */
private val FILENAME_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
private val FILENAME_TIMESTAMP_REGEX = Regex("""_(\d{8}_\d{6})\.json$""")
private const val DAILY_RETENTION_DAYS = 7L

data class BackupFileInfo(val file: File, val createdAtMillis: Long)

object BackupJob {

    private fun dailyBackupDir(context: Context): File = File(context.filesDir, "backups/daily")

    private fun timestampLabel(nowMillis: Long): String =
        FILENAME_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()))

    /** 파일명에서 타임스탬프를 되짚어 epoch millis로 복원한다. 형식이 안 맞으면 null. */
    private fun parseTimestampFromFilename(name: String): Long? {
        val match = FILENAME_TIMESTAMP_REGEX.find(name) ?: return null
        return runCatching {
            val parsed = java.time.LocalDateTime.parse(match.groupValues[1], FILENAME_TIMESTAMP_FORMATTER)
            parsed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /**
     * 현재 상태(설정/프리셋/위젯/Notion 등록/색상 스타일/로컬 일정)를 한 장의 [BackupPayload]로
     * 모은다. Notion 이벤트 캐시와 [com.jongsun.runcal.BuildConfig.NOTION_API_TOKEN] 등 어떤
     * 인증 정보도 이 함수가 만드는 구조체엔 애초에 필드가 없어 절대 포함되지 않는다.
     */
    suspend fun buildPayload(context: Context, backupType: BackupType): BackupPayload = withContext(Dispatchers.IO) {
        val appSettingsRepository = AppSettingsRepository(context)
        val settings = appSettingsRepository.settings.first()
        val specialFlags = com.jongsun.runcal.data.special.SpecialDayPrefs.load(context)
        val db = RunCalDatabase.getInstance(context)
        val calendarRepository = CalendarRepository(context)

        val widgetInstances = enumeratePlacedWidgetIds(context).map { appWidgetId ->
            val kind = WidgetKind.forAppWidgetId(context, appWidgetId)
            val widgetSettings = loadWidgetFilterSettings(context, appWidgetId)
            BackupWidgetInstance(
                appWidgetId = appWidgetId,
                widgetKindHint = kind.name,
                presets = widgetSettings.presets,
                currentPresetIndex = widgetSettings.currentPresetIndex,
                fontScaleStep = widgetSettings.fontScaleStep,
                backgroundOpacity = widgetSettings.backgroundOpacity,
                showWeekNumber = widgetSettings.showWeekNumber,
                showLunar = widgetSettings.showLunar,
            )
        }

        val notionDatabases = db.notionDatabaseDao().getAll().map { entity ->
            BackupNotionDatabase(
                id = entity.id,
                notionDatabaseId = entity.notionDatabaseId,
                displayName = entity.displayName,
                colorArgb = entity.colorArgb,
                iconEmojiOrUrl = entity.iconEmojiOrUrl,
                dateProperty = entity.dateProperty,
                titleProperty = entity.titleProperty,
                subtitleProperty = entity.subtitleProperty,
                statusProperty = entity.statusProperty,
            )
        }

        val eventColorStyles = db.eventColorStyleDao().getAll().map {
            BackupEventColorStyle(it.sourceKey, it.paletteKey, it.bold)
        }

        // provenance에 적힌 id로 CalendarContract를 다시 읽는다 — 사용자가 시스템 캘린더 앱으로
        // 나중에 제목/시간을 바꿨다면 그 최신 값을 백업에 담기 위함.
        val localEvents = db.localEventProvenanceDao().getAll().mapNotNull { provenance ->
            calendarRepository.getEventById(provenance.calendarEventId)?.let { event ->
                BackupLocalEvent(
                    calendarEventId = provenance.calendarEventId,
                    calendarId = provenance.calendarId,
                    title = event.title,
                    startMillis = event.begin,
                    endMillis = event.end,
                    allDay = event.allDay,
                    location = event.location,
                )
            }
        }

        BackupPayload(
            createdAtMillis = System.currentTimeMillis(),
            backupType = backupType,
            appSettings = BackupAppSettings(
                visibleCalendarIds = settings.visibleCalendarIds,
                visibleNotionDatabaseIds = settings.visibleNotionDatabaseIds,
                weekStartDayName = settings.weekStartDay.name,
                fontScaleStep = settings.fontScaleStep,
                activePresetId = settings.activePresetId,
                notionSyncIntervalHours = settings.notionSyncIntervalHours,
                showLunar = specialFlags.showLunar,
                showHolidays = specialFlags.showHolidays,
                showSolarTerms = specialFlags.showSolarTerms,
            ),
            appPresets = settings.presets,
            widgetInstances = widgetInstances,
            notionDatabases = notionDatabases,
            eventColorStyles = eventColorStyles,
            localEvents = localEvents,
        )
    }

    /** 24시간 주기 자동 백업. 쓰기 직후 7일 지난 파일(prerestore 안전 백업 포함)을 정리한다. */
    suspend fun writeDailyBackup(context: Context): File = withContext(Dispatchers.IO) {
        val payload = buildPayload(context, BackupType.DAILY)
        val dir = dailyBackupDir(context).apply { mkdirs() }
        val file = File(dir, "runcal_backup_daily_${timestampLabel(payload.createdAtMillis)}.json")
        file.writeText(backupJson.encodeToString(BackupPayload.serializer(), payload))
        pruneDailyBackups(context)
        file
    }

    /**
     * 복원 직전 자동 안전 백업. 로컬(같은 일일 폴더)에만 쓰며, 실패하면 예외를 그대로 던져
     * 호출부(복원 UI)가 복원 자체를 중단하게 한다 — 안전망 없이 복원하지 않는다.
     */
    suspend fun writePreRestoreSafetyBackup(context: Context): File = withContext(Dispatchers.IO) {
        val payload = buildPayload(context, BackupType.DAILY)
        val dir = dailyBackupDir(context).apply { mkdirs() }
        val file = File(dir, "runcal_backup_prerestore_${timestampLabel(payload.createdAtMillis)}.json")
        file.writeText(backupJson.encodeToString(BackupPayload.serializer(), payload))
        file
    }

    /** 수동 백업 — 사용자가 SAF로 고른 위치(uri)에 직접 쓴다. 자동 보존 정리 대상이 아니다(이제 사용자 파일). */
    suspend fun writeManualBackupToUri(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val payload = buildPayload(context, BackupType.MANUAL)
        val json = backupJson.encodeToString(BackupPayload.serializer(), payload)
        context.contentResolver.openOutputStream(uri)?.use { out -> out.write(json.toByteArray()) }
            ?: error("openOutputStream returned null for $uri")
    }

    fun readPayload(file: File): BackupPayload =
        backupJson.decodeFromString(BackupPayload.serializer(), file.readText())

    /** SAF로 고른(내부 filesDir 밖의) 임의 백업 파일을 읽는다 — "파일에서 복원" 진입점 전용. */
    fun readPayload(context: Context, uri: Uri): BackupPayload {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("openInputStream returned null for $uri")
        return backupJson.decodeFromString(BackupPayload.serializer(), text)
    }

    /** 일일 백업(자동 + prerestore 안전 백업) 목록을 최신순으로 반환한다. */
    fun listDailyBackups(context: Context): List<BackupFileInfo> {
        val dir = dailyBackupDir(context)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f -> parseTimestampFromFilename(f.name)?.let { ts -> BackupFileInfo(f, ts) } }
            ?.sortedByDescending { it.createdAtMillis }
            ?.toList()
            ?: emptyList()
    }

    /** 파일명에 박힌 타임스탬프 기준으로 7일 지난 일일/prerestore 백업 파일을 지운다. */
    private fun pruneDailyBackups(context: Context) {
        val cutoff = System.currentTimeMillis() - DAILY_RETENTION_DAYS * 24 * 60 * 60 * 1000L
        listDailyBackups(context).filter { it.createdAtMillis < cutoff }.forEach { it.file.delete() }
    }
}
