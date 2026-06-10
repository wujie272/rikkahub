package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.rikkahub.data.key.ApiKeyEntry
import me.rerere.rikkahub.data.key.KeyManager

/**
 * 密钥管理 Tab 页面
 *
 * 展示某个 Provider 的所有 API Key，支持添加/编辑/删除/启用/禁用/测试。
 *
 * @param providerId 当前 Provider 的 ID
 * @param keyManager 加密 Key 管理器
 * @param onTestKey 测试单个 Key 的回调（后续对接 ProviderConnectionTester）
 */
@Composable
fun KeyManagementTab(
    providerId: kotlin.uuid.Uuid,
    keyManager: KeyManager,
    modifier: Modifier = Modifier,
    onTestKey: ((ApiKeyEntry) -> Unit)? = null,
) {
    // ── 状态 ──
    var keys by remember(providerId) { mutableStateOf(keyManager.getKeys(providerId)) }
    var showEditSheet by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf<ApiKeyEntry?>(null) }

    val stats = remember(keys) { keyManager.getStats(providerId) }

    // 刷新列表
    fun refresh() {
        keys = keyManager.getKeys(providerId)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 统计概览卡片 ──
            item(key = "stats") {
                StatsCard(stats)
            }

            // ── 空状态 ──
            if (keys.isEmpty()) {
                item(key = "empty") {
                    EmptyKeyState(
                        onAdd = {
                            editingKey = null
                            showEditSheet = true
                        }
                    )
                }
            }

            // ── Key 列表 ──
            items(
                items = keys,
                key = { it.id },
            ) { entry ->
                KeyCard(
                    entry = entry,
                    onEdit = {
                        editingKey = entry
                        showEditSheet = true
                    },
                    onDelete = {
                        keyManager.deleteKey(providerId, entry.id)
                        refresh()
                    },
                    onToggleEnabled = { enabled ->
                        keyManager.updateKey(providerId, entry.copy(isEnabled = enabled))
                        refresh()
                    },
                    onTest = {
                        onTestKey?.invoke(entry)
                    },
                )
            }

            // ── 添加按钮 ──
            item(key = "add") {
                FilledTonalButton(
                    onClick = {
                        editingKey = null
                        showEditSheet = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("添加 API 密钥")
                }
            }
        }
    }

    // ── 添加/编辑弹窗 ──
    if (showEditSheet) {
        KeyEditSheet(
            existing = editingKey,
            onDismiss = {
                showEditSheet = false
                editingKey = null
            },
            onConfirm = { entry ->
                if (editingKey != null) {
                    keyManager.updateKey(providerId, entry)
                } else {
                    keyManager.addKey(providerId, entry)
                }
                showEditSheet = false
                editingKey = null
                refresh()
            },
        )
    }
}

/**
 * 统计概览卡片
 */
@Composable
private fun StatsCard(stats: KeyManager.KeyStats) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatItem("总计", "${stats.total}")
            StatItem("已启用", "${stats.enabled}")
            StatItem("已暂停", "${stats.total - stats.enabled}")
            StatItem("请求", "${stats.totalUsage}")
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * 空状态
 */
@Composable
private fun EmptyKeyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "尚未添加 API 密钥",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "添加密钥后才能使用该供应商的模型",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 不在这里放按钮，底部已有统一的添加按钮
    }
}
