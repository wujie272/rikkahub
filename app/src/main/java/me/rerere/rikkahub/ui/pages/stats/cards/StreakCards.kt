package me.rerere.rikkahub.ui.pages.stats.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar01
import me.rerere.hugeicons.stroke.Fire
import me.rerere.hugeicons.stroke.Message01
import me.rerere.rikkahub.ui.theme.CustomColors

data class StreakInfo(
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val longestStart: String = "",
    val longestEnd: String = "",
)

@Composable
fun StreakCards(
    currentStreak: Int,
    longestStreak: Int,
    avgMessagesPerConversation: Double,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MiniStatCard(
            modifier = Modifier.weight(1f),
            icon = HugeIcons.Fire,
            label = "最长连续",
            value = "${longestStreak} 天",
            color = MaterialTheme.colorScheme.error,
        )
        MiniStatCard(
            modifier = Modifier.weight(1f),
            icon = HugeIcons.Calendar01,
            label = "当前连续",
            value = "${currentStreak} 天",
        )
        MiniStatCard(
            modifier = Modifier.weight(1f),
            icon = HugeIcons.Message01,
            label = "均值",
            value = "%.1f".format(avgMessagesPerConversation) + " 条/对话",
        )
    }
}

@Composable
private fun MiniStatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Card(
        modifier = modifier,
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
