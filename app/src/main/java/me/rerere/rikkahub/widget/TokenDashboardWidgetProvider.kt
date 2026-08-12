package me.rerere.rikkahub.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll

/**
 * 桌面小组件：Token 统计仪表盘。
 *
 * 大号 4×4，显示今日 AI 调用统计、Token 消耗、模型分布、最近调用记录。
 * 支持点击打开 AI 请求日志页。
 */
class TokenDashboardWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TokenDashboardWidget

    companion object {
        /**
         * 从任意 Context 触发小组件刷新。
         */
        suspend fun updateAll(context: Context) {
            TokenDashboardWidget.updateAll(context)
        }
    }
}
