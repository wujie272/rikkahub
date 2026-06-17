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
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.data.ExtraStore

import me.rerere.rikkahub.ui.theme.CustomColors
import java.time.Month
import java.util.Locale

private val MonthLabelsKey = ExtraStore.Key<List<String>>()

data class MonthCount(
    val month: Int,
    val year: Int,
    val count: Int,
)

@Composable
fun TrendLineChart(
    data: List<MonthCount>,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val sortedData = remember(data) { data.sortedBy { it.year * 12 + it.month } }

    LaunchedEffect(sortedData) {
        if (sortedData.isEmpty()) return@LaunchedEffect
        val values = sortedData.map { it.count.toFloat() }
        val labels = sortedData.map {
            val monthName = Month.of(it.month).getDisplayName(
                java.time.format.TextStyle.SHORT, Locale.getDefault()
            )
            if (it.month == 1) "${"$"}{it.year}" else monthName
        }
        modelProducer.runTransaction {
            lineModel { series(values) }
            extras { it[MonthLabelsKey] = labels }
        }
    }

    val valueFormatter = remember {
        CartesianValueFormatter { context, x, _ ->
            context.model.extraStore.getOrNull(MonthLabelsKey)
                ?.getOrElse(x.toInt()) { "" } ?: ""
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "\uD83D\uDCC8 \u5BF9\u8BDD\u521B\u5EFA\u8D8B\u52BF",
                style = MaterialTheme.typography.titleMedium,
            )

            if (sortedData.isNotEmpty()) {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberLineCartesianLayer(),
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
                        .height(200.dp)
                        .padding(top = 8.dp),
                )
            } else {
                Text(
                    text = "\u6682\u65E0\u6570\u636E",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
