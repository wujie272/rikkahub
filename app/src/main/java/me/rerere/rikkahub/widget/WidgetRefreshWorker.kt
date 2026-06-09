package me.rerere.rikkahub.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Widget 刷新后台 Worker
 *
 * 由 WidgetRefreshScheduler 周期性调度，在后台刷新 Widget 数据。
 * 使用 CoroutineWorker 支持协程，避免阻塞主线程。
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // 刷新所有 Widget 实例
            RikkaHubWidget.refreshWidgets(applicationContext)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
