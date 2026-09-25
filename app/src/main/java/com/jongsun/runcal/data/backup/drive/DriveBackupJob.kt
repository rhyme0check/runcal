package com.jongsun.runcal.data.backup.drive

import android.content.Context
import com.jongsun.runcal.data.backup.BackupJob
import com.jongsun.runcal.data.backup.BackupPayload
import com.jongsun.runcal.data.backup.BackupType
import com.jongsun.runcal.data.backup.backupJson
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val FILENAME_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
private const val MONTHLY_RETENTION_MONTHS = 12L
private const val MONTHLY_PREFIX = "runcal_backup_monthly_"

data class DriveBackupFileInfo(val fileId: String, val fileName: String, val createdAtMillis: Long, val sizeBytes: Long?)

/**
 * Drive appDataFolder에 백업을 올리고/받고/지운다. 로컬(BackupJob)과 스키마([BackupPayload])는
 * 완전히 같다 — 목적지만 다르다. 월간 백업 보존 정리는 [writeMonthlyBackup] 성공 직후에만
 * 실행한다(설계안 그대로) — 업로드가 실패하면 오래된 백업을 지우지 않는다.
 */
object DriveBackupJob {

    private fun timestampLabel(atMillis: Long): String =
        FILENAME_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()))

    suspend fun writeMonthlyBackup(context: Context, accessToken: String): DriveBackupFileInfo = withContext(Dispatchers.IO) {
        val payload = BackupJob.buildPayload(context, BackupType.MONTHLY)
        val fileName = "$MONTHLY_PREFIX${timestampLabel(payload.createdAtMillis)}.json"
        val fileId = DriveApiClient().createAppDataFile(accessToken, fileName, encode(payload))
        // 성공했을 때만 정리한다 — 업로드가 실패했는데 오래된 백업까지 지우면 백업이 아예 없어질 수 있다.
        pruneMonthlyBackups(accessToken)
        DriveBackupFileInfo(fileId, fileName, payload.createdAtMillis, null)
    }

    suspend fun writeManualBackup(context: Context, accessToken: String): DriveBackupFileInfo = withContext(Dispatchers.IO) {
        val payload = BackupJob.buildPayload(context, BackupType.MANUAL)
        val fileName = "runcal_backup_manual_${timestampLabel(payload.createdAtMillis)}.json"
        val fileId = DriveApiClient().createAppDataFile(accessToken, fileName, encode(payload))
        DriveBackupFileInfo(fileId, fileName, payload.createdAtMillis, null)
    }

    suspend fun listBackups(accessToken: String): List<DriveBackupFileInfo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<DriveBackupFileInfo>()
        var pageToken: String? = null
        do {
            val page = DriveApiClient().listAppDataFiles(accessToken, pageToken)
            page.files.forEach { file ->
                val createdAtMillis = parseTimestampFromFilename(file.name) ?: parseIsoInstant(file.modifiedTime)
                result += DriveBackupFileInfo(file.id, file.name, createdAtMillis, file.size?.toLongOrNull())
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        result.sortedByDescending { it.createdAtMillis }
    }

    suspend fun readPayload(accessToken: String, fileId: String): BackupPayload = withContext(Dispatchers.IO) {
        val content = DriveApiClient().getAppDataFileContent(accessToken, fileId)
        backupJson.decodeFromString(BackupPayload.serializer(), content)
    }

    suspend fun deleteBackup(accessToken: String, fileId: String) {
        DriveApiClient().deleteAppDataFile(accessToken, fileId)
    }

    private suspend fun pruneMonthlyBackups(accessToken: String) {
        val cutoff = Instant.now().minus(MONTHLY_RETENTION_MONTHS * 30, ChronoUnit.DAYS).toEpochMilli()
        listBackups(accessToken)
            .filter { it.fileName.startsWith(MONTHLY_PREFIX) && it.createdAtMillis < cutoff }
            .forEach { DriveApiClient().deleteAppDataFile(accessToken, it.fileId) }
    }

    private fun encode(payload: BackupPayload): String = backupJson.encodeToString(BackupPayload.serializer(), payload)

    private fun parseTimestampFromFilename(name: String): Long? {
        val match = Regex("""_(\d{8}_\d{6})\.json$""").find(name) ?: return null
        return runCatching {
            java.time.LocalDateTime.parse(match.groupValues[1], FILENAME_TIMESTAMP_FORMATTER)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun parseIsoInstant(iso: String?): Long =
        iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
}
