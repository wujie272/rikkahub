package me.rerere.rikkahub.ui.pages.stats.charts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import me.rerere.rikkahub.data.db.dao.HourlyCount
import me.rerere.rikkahub.ui.theme.CustomColors

private val HourLabelsKey = ExtraStore.Key<List<String>>()

@Composable
fun HourlyBarChart(
    data: List<HourlyCount>,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val sortedData = remember(data) { data.sortedBy { it.hour } }

    LaunchedEffect(sortedData) {
        if (sortedData.isEmpty()) return@LaunchedEffect
        val values = sortedData.map { it.count.toFloat() }
        val labels = sortedData.map { "${it.hour}:00" }
        modelProducer.runTransaction {
            columnModel { series(values) }
            extras { it[HourLabelsKey] = labels }
        }
    }

    val valueFormatter = remember {
        CartesianValueFormatter { context, x, _ ->
            context.model.extraStore.getOrNull(HourLabelsKey)
                ?.getOrElse(x.toInt()) { "" } ?: ""
        }
    }

    val peakHour = sortedData.maxByOrNull { it.count }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "⏰ 你最活跃的时刻",
                style = MaterialTheme.typography.titleMedium,
            )

            if (sortedData.isNotEmpty()) {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberColumnCartesianLayer(),
                        startAxis = VerticalAxis.rememberStart(
                            valueFormatter = CartesianValueFormatter.decimal(),
                        ),
                        bottomAxis = HorizontalAxis.rememberBottom(
                            valueFormatter = valueFormatter,
                        ),
                    ),
                    modelProducer = modelProducer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .padding(top = 8.dp),
                )

                if (peakHour != null) {
                    Text(
                        text = "→ ${peakHour.hour}:00 - ${(peakHour.hour + 1) % 24}:00 最活跃，共 ${peakHour.count} 条消息",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                Text(
                    text = "暂无数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
