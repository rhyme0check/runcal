package com.jongsun.runcal.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.jongsun.runcal.BuildConfig
import com.jongsun.runcal.data.room.AppErrorEntity
import com.jongsun.runcal.data.room.RunCalDatabase
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 사용 기록·오류 기록(앱 안에만 저장, 자동 전송 없음). 기능별 하루 사용 횟수와 오류 요약만 남기고 일정 제목·내용은 담지 않는다.
 * 설정 > 사용 기록에서 보고 내보낸다. 90일이 지나면 지운다.
 */
object UsageLog {
    private const val TAG = "RunCal"
    private const val KEEP_DAYS = 90L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 기능 이름(예: "event_create", "widget_refresh")의 오늘 사용 횟수를 1 올린다. 화면을 막지 않는다. */
    fun track(context: Context, name: String) {
        val app = context.applicationContext
        scope.launch {
            runCatching { RunCalDatabase.getInstance(app).usageDao().increment(LocalDate.now().toString(), name) }
                .onFailure { Log.w(TAG, "UsageLog.track failed: $name", it) }
        }
    }

    /** 오류 하나를 남긴다. [message]는 원인 요약(HTTP 코드, 소요 시간 등)만 — 일정 제목은 넣지 않는다. */
    fun error(context: Context, kind: String, message: String) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                RunCalDatabase.getInstance(app).usageDao().insertError(AppErrorEntity(atMillis = System.currentTimeMillis(), kind = kind, message = message.take(500)))
            }
        }
    }

    private fun crashFile(context: Context) = File(context.filesDir, "usage/crash.txt").also { it.parentFile?.mkdirs() }

    /**
     * 앱이 오류로 꺼질 때 원인을 파일에 바로 쓴다(그 순간에는 DB 쓰기가 끝나기 전에 프로세스가 죽을 수 있다).
     * 다음 실행 때 [onAppStart]가 오류 기록으로 옮긴다. 원래 처리기(시스템 꺼짐 처리)는 그대로 부른다.
     */
    fun installCrashHandler(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val frames = throwable.stackTrace.filter { it.className.startsWith("com.jongsun") }.take(8)
                    .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                val cause = generateSequence(throwable.cause) { it.cause }.lastOrNull()?.let { " / 원인: ${it.javaClass.simpleName}" }.orEmpty()
                crashFile(app).appendText("${System.currentTimeMillis()}\t${throwable.javaClass.simpleName}: ${throwable.message.orEmpty().take(200)}$cause @ $frames\n")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 앱 시작 때: 지난 꺼짐 기록을 오류 기록으로 옮기고, 90일 지난 기록을 지운다. */
    fun onAppStart(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val dao = RunCalDatabase.getInstance(app).usageDao()
                val file = crashFile(app)
                if (file.exists()) {
                    file.readLines().filter { it.isNotBlank() }.forEach { line ->
                        val at = line.substringBefore('\t').toLongOrNull() ?: System.currentTimeMillis()
                        dao.insertError(AppErrorEntity(atMillis = at, kind = "crash", message = line.substringAfter('\t').take(500)))
                    }
                    file.delete()
                }
                val cutoff = LocalDate.now().minusDays(KEEP_DAYS)
                dao.pruneCounts(cutoff.toString())
                dao.pruneErrors(cutoff.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }
        }
    }

    /** 내보내기용 텍스트(저에게 보내 개발 우선순위에 쓰는 용도). 기기·앱 버전, 기능별 합계, 날짜별 횟수, 최근 오류. */
    suspend fun exportText(context: Context): String {
        val dao = RunCalDatabase.getInstance(context).usageDao()
        val zone = ZoneId.systemDefault()
        val since30 = LocalDate.now().minusDays(30).toString()
        return buildString {
            appendLine("RunCal 사용 기록 (${LocalDate.now()})")
            appendLine("앱 ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("[최근 30일 기능별 합계]")
            dao.totalsSince(since30).forEach { appendLine("${it.name}\t${it.count}") }
            appendLine()
            appendLine("[최근 오류 50건]")
            dao.recentErrors(50).forEach { e ->
                appendLine("${Instant.ofEpochMilli(e.atMillis).atZone(zone).toLocalDateTime().withNano(0)}\t${e.kind}\t${e.message}")
            }
            appendLine()
            appendLine("[날짜별 횟수]")
            dao.allCounts().forEach { appendLine("${it.day}\t${it.name}\t${it.count}") }
        }
    }

    suspend fun clear(context: Context) {
        val dao = RunCalDatabase.getInstance(context).usageDao()
        dao.clearCounts()
        dao.clearErrors()
    }
}
