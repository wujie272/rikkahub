package me.rerere.rikkahub.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.RikkaHubApp
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.workflow.repository.WorkflowRepository.Loaded
import org.koin.android.ext.android.get

/**
 * 工作流状态桌面小组件。
 *
 * 中号 (4×2)：显示已启用的工作流列表，每行包含状态指示灯、名称、
 * 上次运行时间、"立即运行"按钮。点击行打开详情页。
 *
 * 取数：通过 [RikkaHubApp] 的 Koin 容器获取 [WorkflowRepository]。
 * 刷新：由 [WorkflowStatusWidgetProvider.updateAll] 触发。
 */
object WorkflowStatusWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val flows = withContext(Dispatchers.IO) {
            (context.applicationContext as RikkaHubApp)
                .get<WorkflowRepository>()
                .listEnabled()
        }
        provideContent {
            WorkflowStatusContent(workflows = flows, context = context)
        }
    }

    /** 打开工作流列表页 */
    fun openListAction(context: Context): Action =
        actionStartActivity(
            Intent(context, RouteActivity::class.java).apply {
                putExtra("navigateTo", "workflows")
            },
        )

    /** 打开工作流详情页 */
    fun openDetailAction(context: Context, workflowId: String): Action =
        actionStartActivity(
            Intent(context, RouteActivity::class.java).apply {
                putExtra("navigateTo", "workflow_detail")
                putExtra("workflowId", workflowId)
            },
        )

    /** 立即运行工作流（经 RouteActivity 触发引擎） */
    fun runNowAction(context: Context, workflowId: String): Action =
        actionStartActivity(
            Intent(context, RouteActivity::class.java).apply {
                putExtra("navigateTo", "workflow_run_now")
                putExtra("workflowId", workflowId)
            },
        )
}

private val TEXT_COLOR = Color(0xFFE6E6E6)
private val TEXT_SECONDARY = Color(0xFF9E9E9E)
private val BG_COLOR = Color(0xFF1E1E1E)
private val ACCENT = Color(0xFFF5A623)

@Composable
@androidx.glance.GlanceComposable
internal fun WorkflowStatusContent(
    workflows: List<Loaded>,
    context: Context,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(BG_COLOR))
            .padding(12.dp),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {

            // 标题行（点击打开列表）
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .clickable(WorkflowStatusWidget.openListAction(context)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "⚡ 工作流状态",
                    style = TextStyle(
                        color = ColorProvider(TEXT_COLOR),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    ),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(
                    text = "今日 ${workflows.count { it.entity.lastRunAtMs != null }} 次",
                    style = TextStyle(
                        color = ColorProvider(TEXT_SECONDARY),
                        fontSize = 11.sp,
                    ),
                )
            }

            Spacer(modifier = GlanceModifier.height(6.dp))

            if (workflows.isEmpty()) {
                Text(
                    text = "暂无工作流",
                    style = TextStyle(
                        color = ColorProvider(TEXT_SECONDARY),
                        fontSize = 13.sp,
                    ),
                )
            } else {
                // 最多显示 3 行（每行带运行按钮，空间有限）
                workflows.take(3).forEach { flow ->
                    WorkflowStatusRow(flow = flow, context = context)
                }
            }
        }
    }
}

@Composable
@androidx.glance.GlanceComposable
private fun WorkflowStatusRow(flow: Loaded, context: Context) {
    val entity = flow.entity
    val status = entity.lastRunStatus
    val dot = when (status) {
        null -> "⏸"
        "SUCCESS" -> "✅"
        "FAILED" -> "❌"
        else -> "⏸"
    }

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态灯 + 名称（点击打开详情）
        Row(
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(WorkflowStatusWidget.openDetailAction(context, entity.id)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dot,
                style = TextStyle(fontSize = 12.sp),
                modifier = GlanceModifier.width(22.dp),
            )
            Text(
                text = entity.name,
                style = TextStyle(
                    color = ColorProvider(TEXT_COLOR),
                    fontSize = 12.sp,
                ),
                modifier = GlanceModifier.defaultWeight(),
                maxLines = 1,
            )
        }

        // 立即运行按钮
        Text(
            text = "▶",
            style = TextStyle(
                color = ColorProvider(ACCENT),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier
                .padding(start = 6.dp)
                .clickable(WorkflowStatusWidget.runNowAction(context, entity.id)),
        )
    }
}

private fun formatRelativeTime(timestampMs: Long?): String {
    if (timestampMs == null) return "从未"
    val diff = System.currentTimeMillis() - timestampMs
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        else -> "${diff / 86_400_000}天前"
    }
}
