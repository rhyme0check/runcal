package com.jongsun.runcal.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jongsun.runcal.data.special.SPECIAL_SYNC_KEY_INPUT
import com.jongsun.runcal.data.special.SpecialDayStore
import com.jongsun.runcal.data.special.SpecialDaySync
import com.jongsun.runcal.widget.RunCalWidgetRenderer

private const val TAG = "RunCal"
private const val MAX_ATTEMPTS = 5

/**
 * 공휴일/절기/음력 캐시 채우기. 입력 키가 있으면 그 연/월 하나만(화면 이동으로 범위 밖에 진입했을 때),
 * 없으면 올해+내년 전체 선반입(앱 시작·주기 갱신). 네트워크 제약이 걸려 있어 오프라인이면 연결될 때까지 대기한다.
 */
class SpecialDaySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(SPECIAL_SYNC_KEY_INPUT)
        val ok = try {
            if (key != null) SpecialDaySync.fetchKeySafely(applicationContext, key) else SpecialDaySync.prefetch(applicationContext)
        } catch (e: Exception) {
            // 스택트레이스/메시지는 남기지 않는다 — 요청 URL에 인증키가 있어 예외 메시지로 새는 것을 원천 차단.
            Log.e(TAG, "SpecialDaySyncWorker failed key=$key (${e::class.java.simpleName})")
            false
        }
        // 일부만 성공했어도 캐시는 이미 바뀌었을 수 있어 위젯은 항상 다시 그린다(그릴 게 없으면 렌더가 그냥 빈 값).
        runCatching { RunCalWidgetRenderer.updateAllWidgets(applicationContext) }
        if (ok) return Result.success()
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            if (key != null) SpecialDayStore.forgetRequest(key)
            return Result.failure()
        }
        return Result.retry()
    }
}
