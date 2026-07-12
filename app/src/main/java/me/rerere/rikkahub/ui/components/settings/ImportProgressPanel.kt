package me.rerere.rikkahub.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R

enum class ProcessingStage(val labelRes: Int) {
    READING(R.string.kb_stage_reading),
    PARSING(R.string.kb_stage_parsing),
    CHUNKING(R.string.kb_stage_chunking),
    EMBEDDING(R.string.kb_stage_embedding),
    SAVING(R.string.kb_stage_saving),
}

data class ImportProgressState(
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val currentFileName: String = "",
    val currentStage: ProcessingStage = ProcessingStage.READING,
    val currentFileProgress: Float = 0f,
    val active: Boolean = false,
)

data class FailedImportItem(
    val id: String,
    val fileName: String,
    val errorMessage: String = "",
    val retryCount: Int = 0,
)

private const val MAX_RETRY_COUNT = 3

@Composable
fun ImportProgressPanel(
    progress: ImportProgressState,
    modifier: Modifier = Modifier,
) {
    if (!progress.active) return

    val totalPercent = if (progress.totalFiles > 0)
        (progress.completedFiles.toFloat() / progress.totalFiles.toFloat())
    else 0f

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.kb_import_progress_title,
                        progress.completedFiles, progress.totalFiles),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${(totalPercent * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { totalPercent },
                modifier = Modifier.fillMaxWidth().height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )

            if (progress.currentFileName.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = progress.currentFileName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(stringResource(progress.currentStage.labelRes),
                                        style = MaterialTheme.typography.labelSmall)
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { progress.currentFileProgress },
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FailedImportBanner(
    failedItems: List<FailedImportItem>,
    onRetry: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (failedItems.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        HugeIcons.AlertCircle, null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.kb_import_failed_count, failedItems.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                TextButton(onClick = onClearAll) {
                    Text(stringResource(R.string.kb_import_clear_all),
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            failedItems.forEach { item ->
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (item.retryCount < MAX_RETRY_COUNT) {
                        IconButton(
                            onClick = { onRetry(item.id) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(HugeIcons.Refresh01, stringResource(R.string.kb_retry),
                                modifier = Modifier.size(14.dp))
                        }
                    }

                    IconButton(
                        onClick = { onDismiss(item.id) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(HugeIcons.Cancel01, stringResource(R.string.kb_dismiss),
                            modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}
