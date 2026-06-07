package me.rerere.rikkahub.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

/**
 * RikkaHub Widget — 桌面数据看板
 *
 * 4×2 尺寸，展示 RikkaHub 使用统计：
 * - 总对话数
 * - 最常用模型（使用率）
 * - 最常用助手（使用率）
 * - 总消息数
 *
 * 标准 AppWidgetProvider API，零门槛适配 HyperOS。
 */
class RikkaHubWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidgetAsync(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var repository: WidgetStatsRepository? = null

        /**
         * 异步更新 Widget
         */
        private fun updateWidgetAsync(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            if (repository == null) {
                repository = WidgetStatsRepository(context)
            }

            scope.launch {
                try {
                    val stats = repository?.getStats() ?: return@launch

                    val views = createRemoteViews(context, stats)

                    // 点击跳转
                    val intent = Intent(context, RouteActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val pi = PendingIntent.getActivity(context, 0, intent, flags)
                    views.setOnClickPendingIntent(R.id.widget_root, pi)

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                } catch (e: Exception) {
                    // 查询失败时显示占位
                    val views = RemoteViews(context.packageName, R.layout.widget_rikkahub)
                    views.setTextViewText(R.id.widget_total_conversations, "?")
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            }
        }

        /**
         * 根据统计数据创建 RemoteViews
         */
        fun createRemoteViews(
            context: Context,
            stats: WidgetStatsRepository.WidgetStats
        ): RemoteViews {
            return RemoteViews(context.packageName, R.layout.widget_rikkahub).apply {
                setTextViewText(
                    R.id.widget_total_conversations,
                    formatCount(stats.totalConversations)
                )

                // 模型使用率
                val modelText = if (stats.topModelPercentage > 0) {
                    "${stats.topModelName} ${stats.topModelPercentage}%"
                } else {
                    stats.topModelName
                }
                setTextViewText(R.id.widget_top_model, modelText)

                // 助手使用率
                val assistantText = if (stats.topAssistantPercentage > 0) {
                    "${stats.topAssistantName} ${stats.topAssistantPercentage}%"
                } else {
                    stats.topAssistantName
                }
                setTextViewText(R.id.widget_top_assistant, assistantText)

                // 总消息数
                val msgText = if (stats.totalMessages > 0) {
                    "共 ${formatCount(stats.totalMessages)} 条消息 · ${stats.lastUpdated}"
                } else {
                    "更新于 ${stats.lastUpdated}"
                }
                setTextViewText(R.id.widget_total_messages, msgText)
                setTextViewText(R.id.widget_updated_at, stats.lastUpdated)
            }
        }

        /**
         * 格式化数字: 1234 → 1.2k
         */
        fun formatCount(count: Long): String {
            return when {
                count >= 1_000_000 -> "${count / 1_000_000}.${(count % 1_000_000) / 100_000}M"
                count >= 1_000 -> "${count / 1_000}.${(count % 1_000) / 100}k"
                else -> count.toString()
            }
        }

        fun formatCount(count: Int): String = formatCount(count.toLong())

        /**
         * 公共刷新方法 — 供后台任务或数据更新时调用
         */
        fun refreshWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, RikkaHubWidget::class.java)
            )
            for (id in ids) {
                updateWidgetAsync(context, manager, id)
            }
        }
    }
}
