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
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
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
import me.rerere.rikkahub.data.ai.requestlog.AIRequestLogEntity
import me.rerere.rikkahub.data.ai.requestlog.AIRequestLogManager
import me.rerere.rikkahub.data.ai.requestlog.AiModelUsage
import me.rerere.rikkahub.data.ai.requestlog.AiUsageSnapshot
import org.koin.android.ext.android.get

/**
 * Token 统计仪表盘桌面小组件。
 *
 * 大号 (4×4)：显示今日 AI 调用统计、Token 消耗、模型分布、最近调用记录。
 * 点击标题打开 AI 请求日志页，点击最近行打开详情。
 *
 * 取数：通过 [RikkaHubApp] 的 Koin 容器获取 [AIRequestLogManager]。
 */
object TokenDashboardWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = withContext(Dispatchers.IO) {
            val manager = (context.applicationContext as RikkaHubApp)
                .get<AIRequestLogManager>()
            TokenDashboardData(
                snapshot = manager.todaySnapshot(),
                allTime = manager.allTimeSnapshot(),
                modelUsage = manager.todayUsageByModel(6),
                recentLogs = manager.recentLogs(5),
            )
        }
        provideContent {
            TokenDashboardContent(data = data, context = context)
        }
    }

    fun openLogsAction(context: Context): Action =
        actionStartActivity(
            Intent(context, RouteActivity::class.java).apply {
                putExtra("navigateTo", "log")
            },
        )
}

/** 仪表盘渲染所需数据。 */
internal data class TokenDashboardData(
    val snapshot: AiUsageSnapshot?,
    val allTime: AiUsageSnapshot?,
    val modelUsage: List<AiModelUsage>,
    val recentLogs: List<AIRequestLogEntity>,
)

// ---- 颜色常量 ----
private val TEXT_PRIMARY = Color(0xFFE6E6E6)
private val TEXT_SECONDARY = Color(0xFF9E9E9E)
private val BG_COLOR = Color(0xFF1E1E1E)
private val ACCENT = Color(0xFFF5A623)
private val GREEN = Color(0xFF4CAF50)
private val RED = Color(0xFFE53935)
private val BAR_BG = Color(0xFF333333)
private val BAR_COLORS = listOf(
    Color(0xFF4CAF50), // 绿
    Color(0xFF2196F3), // 蓝
    Color(0xFFFF9800), // 橙
    Color(0xFF9C27B0), // 紫
    Color(0xFF00BCD4), // 青
    Color(0xFFE91E63), // 粉
)

@Composable
@androidx.glance.GlanceComposable
internal fun TokenDashboardContent(
    data: TokenDashboardData,
    context: Context,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(BG_COLOR))
            .padding(12.dp),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            // ---- 标题行 ----
            HeaderRow(
                todayCount = data.snapshot?.totalCount ?: 0,
                onClick = TokenDashboardWidget.openLogsAction(context),
            )

            Spacer(modifier = GlanceModifier.height(6.dp))

            // ---- 统计概览 ----
            StatsBlock(snapshot = data.snapshot, allTime = data.allTime)

            Spacer(modifier = GlanceModifier.height(6.dp))

            // ---- 模型分布 ----
            if (data.modelUsage.isNotEmpty()) {
                ModelDistribution(models = data.modelUsage)
                Spacer(modifier = GlanceModifier.height(6.dp))
            }

            // ---- 最近调用 ----
            RecentLogsSection(
                logs = data.recentLogs,
                context = context,
            )
        }
    }
}

@Composable
@androidx.glance.GlanceComposable
private fun HeaderRow(todayCount: Long, onClick: Action) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "🤖 AI 调用统计",
            style = TextStyle(
                color = ColorProvider(TEXT_PRIMARY),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            ),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = "今日 $todayCount 次",
            style = TextStyle(
                color = ColorProvider(TEXT_SECONDARY),
                fontSize = 11.sp,
            ),
        )
    }
}

@Composable
@androidx.glance.GlanceComposable
private fun StatsBlock(snapshot: AiUsageSnapshot?, allTime: AiUsageSnapshot?) {
    val s = snapshot
    if (s == null) {
        Text(
            text = "今日暂无调用记录",
            style = TextStyle(
                color = ColorProvider(TEXT_SECONDARY),
                fontSize = 13.sp,
            ),
        )
        return
    }

    val totalTokens = s.totalTokens
    val totalCost = s.totalCost
    val totalStr = formatTokenCompact(totalTokens)
    val inputStr = formatTokenCompact(s.totalInputTokens)
    val outputStr = formatTokenCompact(s.totalOutputTokens)
    val successRate = if (s.totalCount > 0) {
        (s.successCount.toDouble() / s.totalCount * 100).toInt()
    } else 0
    val avgMs = s.avgDurationMs

    // 总消耗
    Text(
        text = "总消耗: $totalStr tokens",
        style = TextStyle(
            color = ColorProvider(ACCENT),
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        ),
    )

    // 输入/输出
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        Text(
            text = "输入: $inputStr",
            style = TextStyle(color = ColorProvider(TEXT_SECONDARY), fontSize = 11.sp),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = "输出: $outputStr",
            style = TextStyle(color = ColorProvider(TEXT_SECONDARY), fontSize = 11.sp),
        )
    }

    // 成本 · 成功率 · 平均耗时
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        Text(
            text = "¥${"%.2f".format(totalCost)}",
            style = TextStyle(color = ColorProvider(GREEN), fontSize = 11.sp),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = "成功率 $successRate%",
            style = TextStyle(color = ColorProvider(TEXT_SECONDARY), fontSize = 11.sp),
            modifier = GlanceModifier.padding(end = 4.dp),
        )
        Text(
            text = "平均 ${avgMs / 1000}s",
            style = TextStyle(color = ColorProvider(TEXT_SECONDARY), fontSize = 11.sp),
        )
    }
}

@Composable
@androidx.glance.GlanceComposable
private fun ModelDistribution(models: List<AiModelUsage>) {
    val total = models.sumOf { it.modelCount }.toFloat()
    if (total <= 0) return

    Text(
        text = "模型分布",
        style = TextStyle(
            color = ColorProvider(TEXT_SECONDARY),
            fontSize = 10.sp,
        ),
    )
    Spacer(modifier = GlanceModifier.height(2.dp))

    models.take(4).forEachIndexed { index, model ->
        val pct = (model.modelCount.toFloat() / total * 100).toInt()
        val color = BAR_COLORS.getOrElse(index) { BAR_COLORS.last() }

        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 模型名
            Text(
                text = model.modelDisplayName.take(12),
                style = TextStyle(
                    color = ColorProvider(TEXT_PRIMARY),
                    fontSize = 10.sp,
                ),
                modifier = GlanceModifier.width(52.dp),
                maxLines = 1,
            )
            // 进度条背景
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .height(8.dp)
                    .background(ColorProvider(BAR_BG)),
            ) {
                // 进度条前景：pct 0-100 → 0-150dp（4x4 容器内可用宽度约 150dp）
                Box(
                    modifier = GlanceModifier
                        .width((pct * 1.5).dp.coerceAtMost(150.dp))
                        .height(8.dp)
                        .background(ColorProvider(color)),
                )
            }
            // 百分比
            Text(
                text = "$pct%",
                style = TextStyle(
                    color = ColorProvider(TEXT_SECONDARY),
                    fontSize = 10.sp,
                ),
                modifier = GlanceModifier.padding(start = 3.dp).width(24.dp),
            )
        }
    }
}

@Composable
@androidx.glance.GlanceComposable
private fun RecentLogsSection(
    logs: List<AIRequestLogEntity>,
    context: Context,
) {
    if (logs.isEmpty()) {
        Text(
            text = "暂无最近调用",
            style = TextStyle(
                color = ColorProvider(TEXT_SECONDARY),
                fontSize = 12.sp,
            ),
        )
        return
    }

    Text(
        text = "最近调用",
        style = TextStyle(
            color = ColorProvider(TEXT_SECONDARY),
            fontSize = 10.sp,
        ),
    )
    Spacer(modifier = GlanceModifier.height(2.dp))

    logs.take(3).forEachIndexed { index, log ->
        val statusIcon = if (log.error != null) "❌" else "✅"
        val timeAgo = formatRelativeTime(log.createdAt)

        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = statusIcon,
                style = TextStyle(fontSize = 10.sp),
                modifier = GlanceModifier.width(16.dp),
            )
            Text(
                text = log.modelDisplayName.take(14),
                style = TextStyle(
                    color = ColorProvider(TEXT_PRIMARY),
                    fontSize = 11.sp,
                ),
                modifier = GlanceModifier.defaultWeight(),
                maxLines = 1,
            )
            Text(
                text = timeAgo,
                style = TextStyle(
                    color = ColorProvider(TEXT_SECONDARY),
                    fontSize = 10.sp,
                ),
            )
        }
    }
}

private fun formatTokenCompact(tokens: Long): String {
    return when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}.${(tokens % 1_000_000) / 100_000}M"
        tokens >= 1_000 -> "${tokens / 1_000}.${(tokens % 1_000) / 100}K"
        else -> tokens.toString()
    }
}

private fun formatRelativeTime(timestampMs: Long): String {
    val diff = System.currentTimeMillis() - timestampMs
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        else -> "${diff / 86_400_000}天前"
    }
}
