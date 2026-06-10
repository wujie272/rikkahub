package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.key.ApiKeyEntry
import kotlin.uuid.Uuid

/**
 * 添加/编辑 API Key 的 ModalBottomSheet
 *
 * @param existing 不为 null 时表示编辑模式，null 表示添加模式
 * @param onDismiss 关闭弹窗
 * @param onConfirm 确认保存
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyEditSheet(
    existing: ApiKeyEntry?,
    onDismiss: () -> Unit,
    onConfirm: (ApiKeyEntry) -> Unit,
) {
    val isEditing = existing != null
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── 内部状态 ──
    var keyValue by remember(existing) { mutableStateOf(existing?.keyValue ?: "") }
    var label by remember(existing) { mutableStateOf(existing?.label ?: "") }
    var isEnabled by remember(existing) { mutableStateOf(existing?.isEnabled ?: true) }
    var keyVisible by remember { mutableStateOf(false) }

    // 快捷标签下拉
    var labelDropdownExpanded by remember { mutableStateOf(false) }
    val presetLabels = listOf("主力 Key", "备用 Key", "开发 Key", "测试 Key")

    val isValid = keyValue.isNotBlank() && label.isNotBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 标题 ──
            Text(
                text = if (isEditing) "编辑密钥" else "添加 API 密钥",
                style = MaterialTheme.typography.titleLarge,
            )

            // ── API Key 输入 ──
            OutlinedTextField(
                value = keyValue,
                onValueChange = {
                    keyValue = it.trim()
                },
                label = { Text("API Key") },
                placeholder = { Text("sk-proj-...") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
                enabled = !isEditing,  // 编辑模式下不允许改 Key 值
                visualTransformation = if (keyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    Row {
                        // 粘贴按钮（仅添加模式）
                        if (!isEditing) {
                            val clipboard = LocalClipboardManager.current
                            IconButton(onClick = {
                                clipboard.getText()?.text?.let {
                                    keyValue = it.trim()
                                }
                            }) {
                                Icon(
                                    HugeIcons.Clipboard,
                                    contentDescription = "粘贴"
                                )
                            }
                        }
                        // 显示/隐藏
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) HugeIcons.ViewOff else HugeIcons.View,
                                contentDescription = if (keyVisible) "隐藏" else "显示"
                            )
                        }
                    }
                },
            )

            // ── 标签输入（下拉+自定义） ──
            ExposedDropdownMenuBox(
                expanded = labelDropdownExpanded,
                onExpandedChange = { labelDropdownExpanded = it },
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("标签") },
                    placeholder = { Text("例如：主力 Key") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = labelDropdownExpanded)
                    },
                    singleLine = true,
                )
                ExposedDropdownMenu(
                    expanded = labelDropdownExpanded,
                    onDismissRequest = { labelDropdownExpanded = false },
                ) {
                    presetLabels.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset) },
                            onClick = {
                                label = preset
                                labelDropdownExpanded = false
                            },
                        )
                    }
                }
            }

            // ── 启用/暂停 ──
            Text(
                text = "状态",
                style = MaterialTheme.typography.titleSmall,
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SegmentedButton(
                    selected = isEnabled,
                    onClick = { isEnabled = true },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    label = { Text("启用") },
                )
                SegmentedButton(
                    selected = !isEnabled,
                    onClick = { isEnabled = false },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    label = { Text("暂停") },
                )
            }

            // ── 编辑模式下显示统计 ──
            if (isEditing && existing != null) {
                KeyStatsSummary(existing)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── 操作按钮 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        val entry = (existing?.copy(
                            keyValue = keyValue,
                            label = label,
                            isEnabled = isEnabled,
                        ) ?: ApiKeyEntry(
                            keyValue = keyValue,
                            label = label,
                            isEnabled = isEnabled,
                        ))
                        onConfirm(entry)
                    },
                    enabled = isValid,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isEditing) "保存" else "添加")
                }
            }
        }
    }
}

/**
 * 编辑模式下显示的 Key 统计摘要
 */
@Composable
private fun KeyStatsSummary(entry: ApiKeyEntry) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "使用统计",
            style = MaterialTheme.typography.titleSmall,
        )

        val totalMinutes = if (entry.lastUsedAt > 0) {
            val elapsed = System.currentTimeMillis() - entry.lastUsedAt
            when {
                elapsed < 60_000 -> "刚刚"
                elapsed < 3_600_000 -> "${elapsed / 60_000} 分钟前"
                elapsed < 86_400_000 -> "${elapsed / 3_600_000} 小时前"
                else -> "${elapsed / 86_400_000} 天前"
            }
        } else "从未使用"

        Text(
            text = "使用次数: ${entry.usageCount}  最后使用: $totalMinutes",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (entry.createdAt > 0) {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date(entry.createdAt))
            Text(
                text = "创建时间: $date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (entry.lastErrorAt > 0) {
            Text(
                text = "最近错误: ${entry.lastErrorMessage}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
            )
        }
    }
}
