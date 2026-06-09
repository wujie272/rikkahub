package me.rerere.rikkahub.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Widget 刷新调度器
 *
 * 使用 WorkManager 替代系统 updatePeriodMillis 轮询，支持：
 * - 智能调度：仅在设备联网且电量不低时刷新
 * - 指数退避：失败后延迟重试
 * - 生命周期感知：所有尺寸的 Widget 都移除后自动停止
 */
object WidgetRefreshScheduler {

    private const val WORK_NAME = "rikkahub_widget_refresh"
    private const val INTERVAL_MINUTES = 30L

    // 所有 Widget 提供者类
    private val WIDGET_CLASSES = listOf(
        RikkaHubWidget::class.java,
        RikkaHubWidgetSmall::class.java,
        RikkaHubWidgetLarge::class.java
    )

    /**
     * 调度周期性 Widget 刷新
     */
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
            INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                1,
                TimeUnit.MINUTES
            )
            .addTag(WORK_NAME)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }

    /**
     * 检查是否还有任何尺寸的 Widget 在桌面上
     */
    private fun hasAnyWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return WIDGET_CLASSES.any { clazz ->
            val ids = manager.getAppWidgetIds(ComponentName(context, clazz))
            ids.isNotEmpty()
        }
    }

    /**
     * 安全取消刷新 — 仅在所有 Widget 都移除后停止
     */
    fun cancel(context: Context) {
        if (!hasAnyWidgets(context)) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(WORK_NAME)
        }
    }

    /**
     * 刷新所有尺寸的所有 Widget
     */
    fun refreshAll(context: Context) {
        WIDGET_CLASSES.forEach { clazz ->
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, clazz))
            if (ids.isNotEmpty()) {
                RikkaHubWidget.refreshWidgets(context)
                return@forEach
            }
        }
    }
}
