package me.rerere.rikkahub.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll

/**
 * 桌面小组件：工作流状态。
 *
 * 中号 4×2，显示已启用的工作流列表及其最近运行状态。
 * 支持点击打开详情。
 */
class WorkflowStatusWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WorkflowStatusWidget

    companion object {
        /**
         * 从任意 Context 触发小组件刷新（例如 WorkflowEngine.fire() 完成后调用）。
         */
        suspend fun updateAll(context: Context) {
            WorkflowStatusWidget.updateAll(context)
        }
    }
}
