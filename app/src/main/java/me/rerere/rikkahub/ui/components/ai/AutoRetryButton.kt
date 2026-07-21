package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import kotlin.uuid.Uuid

@Composable
fun AutoRetryButton(
    modifier: Modifier = Modifier,
    autoContinueOnError: Boolean,
    continueModelId: Uuid?,
    providers: List<ProviderSetting>,
    onUpdate: (autoContinue: Boolean, continueModelId: Uuid?) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        AutoContinuePicker(
            autoContinueOnError = autoContinueOnError,
            continueModelId = continueModelId,
            providers = providers,
            onDismissRequest = { showPicker = false },
            onUpdate = onUpdate,
        )
    }

    ToggleSurface(
        checked = autoContinueOnError,
        onClick = { showPicker = true },
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HugeIcons.Refresh01,
                contentDescription = "自动继续",
            )
        }
    }
}

@Composable
private fun AutoContinuePicker(
    autoContinueOnError: Boolean,
    continueModelId: Uuid?,
    providers: List<ProviderSetting>,
    onDismissRequest: () -> Unit,
    onUpdate: (Boolean, Uuid?) -> Unit,
) {
    var enabled by remember { mutableStateOf(autoContinueOnError) }
    var selectedModelId by remember { mutableStateOf(continueModelId) }

    val modelListState = rememberModelListState(
        modelId = selectedModelId,
        providers = providers,
        type = ModelType.CHAT,
    )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 标题
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "自动继续设置",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "生成中断时自动继续，不用手动输入「继续步骤」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // 开关
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            "启用自动继续",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "中断后自动从断点继续生成",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            onUpdate(it, selectedModelId)
                        },
                    )
                }
            }

            // 模型选择
            AnimatedVisibility(visible = enabled) {
                Surface(
                    onClick = { modelListState.open() },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "继续模型",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            val continueModel = selectedModelId?.let {
                                providers.findModelById(it)
                            }
                            Text(
                                text = continueModel?.displayName ?: "使用主模型",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "未设置则使用当前对话模型",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        Icon(
                            imageVector = HugeIcons.ArrowRight01,
                            contentDescription = null,
                        )
                    }
                }
            }

            // 提示
            AnimatedVisibility(visible = enabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = HugeIcons.AlertCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Text(
                        text = "继续会消耗额外 token",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }

    // 复用现有的模型选择底 Sheet
    ModelListSheet(
        state = modelListState,
        onSelect = { model ->
            selectedModelId = model.id
            onUpdate(enabled, model.id)
        },
    )
}
