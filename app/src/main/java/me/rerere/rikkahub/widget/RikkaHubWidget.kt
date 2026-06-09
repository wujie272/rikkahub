package me.rerere.rikkahub.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
 * 澎湃OS/Android 小部件，支持 2×2 / 4×2 / 4×4 三种尺寸自适应。
 * 数据刷新由 WorkManager 接管，替代系统轮询。
 *
 * 尺寸说明:
 * - 2×2 (SMALL):  简约卡片，仅显示总对话数和消息数
 * - 4×2 (MEDIUM): 标准看板，对话/模型/助手三行统计
 * - 4×4 (LARGE):  完整仪表盘，6 维度数据 + Token 用量
 */
open class RikkaHubWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidgetAsync(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        // 用户调整大小时即时切换布局
        updateWidgetAsync(context, appWidgetManager, appWidgetId)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 启用 WorkManager 智能刷新
        WidgetRefreshScheduler.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // 最后一个 Widget 移除后停止刷新
        WidgetRefreshScheduler.cancel(context)
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var repository: WidgetStatsRepository? = null

        /** 尺寸枚举 */
        enum class WidgetSize {
            SMALL_2X2,  // 2 格 × 2 格
            MEDIUM_4X2, // 4 格 × 2 格
            LARGE_4X4   // 4 格 × 4 格
        }

        /**
         * 根据 Widget 尺寸选择布局和数据显示策略
         */
        private fun resolveWidgetSize(options: Bundle): WidgetSize {
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            return when {
                width < 200 -> WidgetSize.SMALL_2X2
                else -> {
                    val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                    if (height < 200) WidgetSize.MEDIUM_4X2 else WidgetSize.LARGE_4X4
                }
            }
        }

        /** 获取对应尺寸的布局资源 ID */
        private fun getLayoutForSize(size: WidgetSize): Int = when (size) {
            WidgetSize.SMALL_2X2 -> R.layout.widget_rikkahub_2x2
            WidgetSize.MEDIUM_4X2 -> R.layout.widget_rikkahub_4x2
            WidgetSize.LARGE_4X4 -> R.layout.widget_rikkahub_4x4
        }

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

            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val size = resolveWidgetSize(options)
            val layoutId = getLayoutForSize(size)

            scope.launch {
                try {
                    val stats = repository?.getStats() ?: return@launch
                    val views = createRemoteViews(context, stats, size, layoutId)

                    // 点击跳转主界面
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
                    val views = RemoteViews(context.packageName, layoutId)
                    views.setTextViewText(R.id.widget_total_conversations, "--")
                    views.setTextViewText(R.id.widget_total_messages, "更新失败")
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            }
        }

        /**
         * 根据尺寸创建对应的 RemoteViews
         */
        fun createRemoteViews(
            context: Context,
            stats: WidgetStatsRepository.WidgetStats,
            size: WidgetSize,
            layoutId: Int
        ): RemoteViews {
            return RemoteViews(context.packageName, layoutId).apply {
                val fmtCount = formatCount(stats.totalConversations)

                when (size) {
                    WidgetSize.SMALL_2X2 -> {
                        // 2×2: 大数字 + 标签
                        setTextViewText(R.id.widget_total_conversations, fmtCount)
                        val msgText = if (stats.totalMessages > 0) {
                            "📝 ${formatCount(stats.totalMessages)} 条消息"
                        } else {
                            "更新于 ${stats.lastUpdated}"
                        }
                        setTextViewText(R.id.widget_total_messages, msgText)
                    }

                    WidgetSize.MEDIUM_4X2 -> {
                        // 4×2: 三行数据（现有布局）
                        setTextViewText(R.id.widget_total_conversations, fmtCount)

                        val modelText = if (stats.topModelPercentage > 0) {
                            "${stats.topModelName} ${stats.topModelPercentage}%"
                        } else {
                            stats.topModelName
                        }
                        setTextViewText(R.id.widget_top_model, modelText)

                        val assistantText = if (stats.topAssistantPercentage > 0) {
                            "${stats.topAssistantName} ${stats.topAssistantPercentage}%"
                        } else {
                            stats.topAssistantName
                        }
                        setTextViewText(R.id.widget_top_assistant, assistantText)

                        val msgText = if (stats.totalMessages > 0) {
                            "共 ${formatCount(stats.totalMessages)} 条消息"
                        } else {
                            "暂无消息"
                        }
                        setTextViewText(R.id.widget_total_messages, msgText)
                        setTextViewText(R.id.widget_updated_at, stats.lastUpdated)
                    }

                    WidgetSize.LARGE_4X4 -> {
                        // 4×4: 6 维度完整仪表盘
                        setTextViewText(R.id.widget_total_conversations, fmtCount)
                        setTextViewText(R.id.widget_total_messages, formatCount(stats.totalMessages))

                        val modelText = if (stats.topModelPercentage > 0) {
                            "${stats.topModelName} ${stats.topModelPercentage}%"
                        } else {
                            stats.topModelName
                        }
                        setTextViewText(R.id.widget_top_model, modelText)

                        val assistantText = if (stats.topAssistantPercentage > 0) {
                            "${stats.topAssistantName} ${stats.topAssistantPercentage}%"
                        } else {
                            stats.topAssistantName
                        }
                        setTextViewText(R.id.widget_top_assistant, assistantText)

                        // 今日对话
                        setTextViewText(R.id.widget_today_chats, stats.todayChats.toString())

                        // Token 用量
                        val tokenText = if (stats.totalTokens > 0) {
                            formatCount(stats.totalTokens)
                        } else {
                            "--"
                        }
                        setTextViewText(R.id.widget_token_usage, tokenText)

                        setTextViewText(R.id.widget_updated_at, "更新于 ${stats.lastUpdated}")
                    }
                }
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
         * 公共刷新方法 — 供 WorkManager 后台任务调用
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
