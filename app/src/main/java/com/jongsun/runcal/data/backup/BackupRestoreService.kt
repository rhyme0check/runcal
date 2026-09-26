package com.jongsun.runcal.data.backup

import android.content.Context
import com.jongsun.runcal.data.AppSettingsRepository
import com.jongsun.runcal.data.CalendarRepository
import com.jongsun.runcal.data.DEFAULT_APP_PRESET
import com.jongsun.runcal.data.DEFAULT_WEEK_START_DAY
import com.jongsun.runcal.data.room.EventColorStyleEntity
import com.jongsun.runcal.data.room.NotionDatabaseEntity
import com.jongsun.runcal.data.room.RunCalDatabase
import com.jongsun.runcal.widget.enumeratePlacedWidgetIds
import com.jongsun.runcal.widget.saveWidgetFilterSettings
import com.jongsun.runcal.work.WorkScheduler
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class RestoreMode { OVERWRITE, MERGE }

data class RestoreSummary(
    val presetsCount: Int,
    val widgetInstancesRestored: Int,
    val widgetInstancesSkipped: Int,
    val notionDatabasesRestored: Int,
    val colorStylesRestored: Int,
    val localEventsRestored: Int,
)

/** 백업이 지원하는 포맷보다 새 버전일 때(다른 기기가 더 최신 앱으로 만든 경우) 던진다. */
class UnsupportedBackupFormatException(val fileVersion: Int, val supportedVersion: Int) :
    Exception("backup formatVersion=$fileVersion > supported=$supportedVersion")

/**
 * 카테고리별 덮어쓰기/병합 규칙(설계안 6절)을 실제로 적용한다. 위젯 인스턴스는 병합/덮어쓰기
 * 모두 "백업과 현재 기기 양쪽에 존재하는 것만" 전체 교체하고(appWidgetId가 기기마다 다시
 * 배정되므로 새로 만들 수는 없음), 언급되지 않은 나머지는 두 모드 다 건드리지 않는다.
 */
object BackupRestoreService {

    suspend fun restore(context: Context, payload: BackupPayload, mode: RestoreMode): RestoreSummary =
        withContext(Dispatchers.IO) {
            if (payload.formatVersion > CURRENT_BACKUP_FORMAT_VERSION) {
                throw UnsupportedBackupFormatException(payload.formatVersion, CURRENT_BACKUP_FORMAT_VERSION)
            }

            val appSettingsRepository = AppSettingsRepository(context)
            val db = RunCalDatabase.getInstance(context)
            val calendarRepository = CalendarRepository(context)

            restoreAppSettings(appSettingsRepository, payload.appSettings)
            val presetsCount = restorePresets(appSettingsRepository, payload.appPresets, mode)
            val (widgetsRestored, widgetsSkipped) = restoreWidgetInstances(context, payload.widgetInstances)
            val notionRestored = restoreNotionDatabases(context, db, payload.notionDatabases, mode)
            val colorStylesRestored = restoreEventColorStyles(db, payload.eventColorStyles, mode)
            val localEventsRestored = restoreLocalEvents(db, calendarRepository, payload.localEvents, mode)

            RestoreSummary(
                presetsCount = presetsCount,
                widgetInstancesRestored = widgetsRestored,
                widgetInstancesSkipped = widgetsSkipped,
                notionDatabasesRestored = notionRestored,
                colorStylesRestored = colorStylesRestored,
                localEventsRestored = localEventsRestored,
            )
        }

    /** 앱 설정은 병합 대상이 아니라 항상 덮어쓴다(설계안 그대로). */
    private suspend fun restoreAppSettings(repository: AppSettingsRepository, snapshot: BackupAppSettings) {
        repository.setVisibleCalendarIds(snapshot.visibleCalendarIds)
        repository.setVisibleNotionDatabaseIds(snapshot.visibleNotionDatabaseIds ?: emptySet())
        val weekStartDay = runCatching { DayOfWeek.valueOf(snapshot.weekStartDayName) }.getOrDefault(DEFAULT_WEEK_START_DAY)
        repository.setWeekStartDay(weekStartDay)
        repository.setFontScaleStep(snapshot.fontScaleStep)
        repository.setActivePresetId(snapshot.activePresetId)
        repository.setNotionSyncIntervalHours(snapshot.notionSyncIntervalHours)
    }

    /** 병합=id 기준 합집합(충돌 시 백업본 우선), 덮어쓰기=전체 교체. */
    private suspend fun restorePresets(
        repository: AppSettingsRepository,
        backupPresets: List<com.jongsun.runcal.data.AppPreset>,
        mode: RestoreMode,
    ): Int {
        val restored = when (mode) {
            RestoreMode.OVERWRITE -> backupPresets
            RestoreMode.MERGE -> {
                val currentPresets = repository.settings.first().presets
                val byId = linkedMapOf<String, com.jongsun.runcal.data.AppPreset>()
                currentPresets.forEach { byId[it.id] = it }
                backupPresets.forEach { byId[it.id] = it }
                byId.values.toList()
            }
        }.ifEmpty { listOf(DEFAULT_APP_PRESET) }
        repository.setPresets(restored)
        return restored.size
    }

    /** 매칭된(=현재 기기에도 배치돼 있는) 인스턴스만 전체 교체 — 병합/덮어쓰기 구분 없음(교체 대상 선정만 다름, 내용은 항상 백업값으로). */
    private suspend fun restoreWidgetInstances(context: Context, snapshots: List<BackupWidgetInstance>): Pair<Int, Int> {
        val placedIds = enumeratePlacedWidgetIds(context).toSet()
        var restored = 0
        var skipped = 0
        snapshots.forEach { snapshot ->
            if (snapshot.appWidgetId !in placedIds) {
                skipped++
                return@forEach
            }
            saveWidgetFilterSettings(
                context, snapshot.appWidgetId, snapshot.presets, snapshot.currentPresetIndex,
                snapshot.fontScaleStep, snapshot.backgroundOpacity, snapshot.showWeekNumber,
            )
            restored++
        }
        return restored to skipped
    }

    /**
     * 병합=내부 id 기준 합집합(이미 있는 id는 건드리지 않음), notionDatabaseId 중복이면 건너뜀.
     * 덮어쓰기=전체 교체 후 복원된 DB 전체에 대해 1회성 동기화를 예약(캐시는 빈 상태로 시작).
     */
    private suspend fun restoreNotionDatabases(
        context: Context,
        db: RunCalDatabase,
        snapshots: List<BackupNotionDatabase>,
        mode: RestoreMode,
    ): Int {
        val dao = db.notionDatabaseDao()
        var restored = 0
        when (mode) {
            RestoreMode.OVERWRITE -> {
                dao.getAll().forEach { dao.deleteById(it.id) }
                snapshots.forEach { dao.upsert(it.toEntity()); restored++ }
                if (snapshots.isNotEmpty()) WorkScheduler.triggerManualSync(context)
            }
            RestoreMode.MERGE -> {
                val existing = dao.getAll()
                val existingInternalIds = existing.map { it.id }.toSet()
                val existingNotionIds = existing.map { it.notionDatabaseId }.toSet()
                snapshots.forEach { snapshot ->
                    if (snapshot.id in existingInternalIds) return@forEach
                    if (snapshot.notionDatabaseId in existingNotionIds) return@forEach
                    dao.upsert(snapshot.toEntity())
                    restored++
                }
            }
        }
        return restored
    }

    private fun BackupNotionDatabase.toEntity(): NotionDatabaseEntity {
        val now = System.currentTimeMillis()
        return NotionDatabaseEntity(
            id = id,
            notionDatabaseId = notionDatabaseId,
            displayName = displayName,
            colorArgb = colorArgb,
            iconEmojiOrUrl = iconEmojiOrUrl,
            dateProperty = dateProperty,
            titleProperty = titleProperty,
            subtitleProperty = subtitleProperty,
            statusProperty = statusProperty,
            schemaJson = "{}",
            lastSchemaCheckedAtMillis = 0L,
            lastSyncedAtMillis = 0L,
            lastSyncStatus = "PENDING",
            lastSyncError = null,
            createdAtMillis = now,
        )
    }

    /** 병합=sourceKey 기준 합집합(충돌 시 백업본 우선), 덮어쓰기=전체 교체. */
    private suspend fun restoreEventColorStyles(
        db: RunCalDatabase,
        snapshots: List<BackupEventColorStyle>,
        mode: RestoreMode,
    ): Int {
        val dao = db.eventColorStyleDao()
        if (mode == RestoreMode.OVERWRITE) {
            dao.getAll().forEach { dao.deleteBySourceKey(it.sourceKey) }
        }
        snapshots.forEach { dao.upsert(EventColorStyleEntity(it.sourceKey, it.paletteKey, it.bold)) }
        com.jongsun.runcal.widget.EventColorStyleCache.invalidate()
        return snapshots.size
    }

    /**
     * 병합=(title, startMillis, endMillis) 기준 없으면 삽입, 덮어쓰기=현재 provenance 추적 중인
     * 로컬 일정을 전부 지우고 백업 내용으로 재생성(provenance 안 걸린 이벤트는 건드리지 않음).
     */
    private suspend fun restoreLocalEvents(
        db: RunCalDatabase,
        calendarRepository: CalendarRepository,
        snapshots: List<BackupLocalEvent>,
        mode: RestoreMode,
    ): Int {
        val provenanceDao = db.localEventProvenanceDao()
        if (mode == RestoreMode.OVERWRITE) {
            provenanceDao.getAll().forEach { provenance ->
                calendarRepository.deleteEvent(provenance.calendarEventId)
            }
        }
        val existingKeys = if (mode == RestoreMode.MERGE) {
            provenanceDao.getAll().mapNotNull { provenance ->
                calendarRepository.getEventById(provenance.calendarEventId)
                    ?.let { Triple(it.title, it.begin, it.end) }
            }.toSet()
        } else {
            emptySet()
        }

        var restored = 0
        snapshots.forEach { snapshot ->
            val key = Triple(snapshot.title, snapshot.startMillis, snapshot.endMillis)
            if (mode == RestoreMode.MERGE && key in existingKeys) return@forEach
            val newId = calendarRepository.createEvent(
                calendarId = snapshot.calendarId,
                title = snapshot.title,
                startMillis = snapshot.startMillis,
                endMillis = snapshot.endMillis,
                allDay = snapshot.allDay,
            )
            if (newId > 0) restored++
        }
        return restored
    }
}
