package me.rerere.rikkahub.ui.pages.stats.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.pie.PieChart
import com.patrykandpatrick.vico.compose.pie.PieChartHost
import com.patrykandpatrick.vico.compose.pie.PieSize
import com.patrykandpatrick.vico.compose.pie.data.PieChartModelProducer
import com.patrykandpatrick.vico.compose.pie.data.PieValueFormatter
import com.patrykandpatrick.vico.compose.pie.data.pieSeries
import com.patrykandpatrick.vico.compose.pie.rememberPieChart
import me.rerere.rikkahub.ui.theme.CustomColors

private val INPUT_COLOR = Color(0xFF6366F1)
private val OUTPUT_COLOR = Color(0xFF22C55E)
private val CACHED_COLOR = Color(0xFFEAB308)

@Composable
fun TokenDonutChart(
    promptTokens: Long,
    completionTokens: Long,
    cachedTokens: Long,
    modifier: Modifier = Modifier,
) {
    val total = promptTokens + completionTokens + cachedTokens
    val modelProducer = remember { PieChartModelProducer() }

    LaunchedEffect(promptTokens, completionTokens, cachedTokens) {
        if (total == 0L) return@LaunchedEffect
        val promptPercent = (promptTokens.toFloat() / total * 100)
        val completionPercent = (completionTokens.toFloat() / total * 100)
        val cachedPercent = (cachedTokens.toFloat() / total * 100)
        modelProducer.runTransaction {
            pieSeries { series(promptPercent, completionPercent, cachedPercent) }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "\u7c7bToken \u4f7f\u7528\u5206\u5e03",
                style = MaterialTheme.typography.titleMedium,
            )

            if (total > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PieChartHost(
                        chart = rememberPieChart(
                            sliceProvider = PieChart.SliceProvider.series(
                                listOf(
                                    PieChart.Slice(fill = Fill(INPUT_COLOR)),
                                    PieChart.Slice(fill = Fill(OUTPUT_COLOR)),
                                    PieChart.Slice(fill = Fill(CACHED_COLOR)),
                                )
                            ),
                            innerSize = PieSize.Inner.fixed(50.dp),
                            valueFormatter = PieValueFormatter { _, value, _ ->
                                "%.1f%%".format(value)
                            },
                        ),
                        modelProducer = modelProducer,
                        modifier = Modifier.size(140.dp),
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LegendItem(
                            color = INPUT_COLOR,
                            label = "\u8f93\u5165",
                            value = formatTokensCompact(promptTokens),
                            percent = if (total > 0) (promptTokens * 100 / total).toInt() else 0,
                        )
                        LegendItem(
                            color = OUTPUT_COLOR,
                            label = "\u8f93\u51fa",
                            value = formatTokensCompact(completionTokens),
                            percent = if (total > 0) (completionTokens * 100 / total).toInt() else 0,
                        )
                        LegendItem(
                            color = CACHED_COLOR,
                            label = "\u7f13\u5b58",
                            value = formatTokensCompact(cachedTokens),
                            percent = if (total > 0) (cachedTokens * 100 / total).toInt() else 0,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "\u603b\u8ba1 " + formatTokensCompact(total),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            } else {
                Text(
                    text = "\u6682\u65e0 Token \u6570\u636e",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun LegendItem(
    color: Color,
    label: String,
    value: String,
    percent: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = color)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$label  $value",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTokensCompact(count: Long): String = when {
    count >= 1_000_000_000 -> "%.2fB".format(count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.2fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}
