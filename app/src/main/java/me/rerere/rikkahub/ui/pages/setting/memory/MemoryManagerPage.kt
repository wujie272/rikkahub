package me.rerere.rikkahub.ui.pages.setting.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import me.rerere.hugeicons.stroke.FavouriteCircle
import me.rerere.hugeicons.stroke.Favourite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun MemoryManagerPage() {
    val vm: MemoryManagerVM = koinViewModel()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("记忆管理") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        MemoryManagerContent(
            modifier = Modifier.padding(innerPadding),
            uiState = uiState,
            onQueryChange = { vm.setQuery(it) },
            onDelete = { vm.deleteMemory(it) },
            onEdit = { id, content -> vm.updateContent(id, content) },
            onTogglePin = { id, pinned -> vm.togglePin(id, pinned) },
            onReindex = { vm.reindexAll() },
        )
    }
}

@Composable
private fun MemoryManagerContent(
    modifier: Modifier = Modifier,
    uiState: MemoryManagerUiState,
    onQueryChange: (String) -> Unit,
    onDelete: (Int) -> Unit,
    onEdit: (Int, String) -> Unit,
    onTogglePin: (Int, Boolean) -> Unit,
    onReindex: () -> Unit,
) {
    var editTarget by remember { mutableStateOf<AssistantMemory?>(null) }
    var deleteTarget by remember { mutableStateOf<AssistantMemory?>(null) }

    // 编辑对话框
    editTarget?.let { mem ->
        var editContent by remember(mem.id) { mutableStateOf(mem.content) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("编辑记忆 #${mem.id}") },
            text = {
                TextField(
                    value = editContent,
                    onValueChange = { editContent = it },
                    minLines = 2,
                    maxLines = 8,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onEdit(mem.id, editContent)
                    editTarget = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text("取消") }
            },
        )
    }

    // 删除确认
    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = "确认删除",
        confirmText = "删除",
        dismissText = "取消",
        onConfirm = {
            deleteTarget?.let { onDelete(it.id) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
        text = {
            Text(
                text = deleteTarget?.content.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 搜索框
        OutlinedTextField(
            value = uiState.query,
            onValueChange = onQueryChange,
            placeholder = { Text("搜索记忆...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // 模型状态卡片
        ModelStatusCard(
            mismatchCount = uiState.mismatchCount,
            reindexing = uiState.reindexing,
            reindexProgress = uiState.reindexProgress,
            onReindex = onReindex,
        )

        // 记忆列表
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(uiState.memories, key = { it.id }) { memory ->
                key(memory.id) {
                    MemoryItemCard(
                        memory = memory,
                        onEdit = { editTarget = memory },
                        onDelete = { deleteTarget = memory },
                        onTogglePin = { onTogglePin(memory.id, !memory.pinned) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelStatusCard(
    mismatchCount: Int,
    reindexing: Boolean,
    reindexProgress: Pair<Int, Int>?,
    onReindex: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "嵌入模型状态",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (mismatchCount > 0) {
                        Text(
                            text = "$mismatchCount 条记忆需要重新编码",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            text = "全部一致",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!reindexing) {
                    TextButton(onClick = onReindex) {
                        Text("重新计算全部")
                    }
                }
            }
            if (reindexing) {
                Spacer(Modifier.height(8.dp))
                reindexProgress?.let { (current, total) ->
                    Text(
                        text = "正在重新编码... $current / $total",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { if (total > 0) current.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryItemCard(
    memory: AssistantMemory,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${memory.id}",
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    if (memory.pinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = HugeIcons.FavouriteCircle,
                            contentDescription = "已固定",
                            modifier = Modifier.height(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (!memory.hasEmbedding) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "无向量",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = memory.content,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    if (memory.pinned) HugeIcons.FavouriteCircle else HugeIcons.Favourite,
                    contentDescription = if (memory.pinned) "取消固定" else "固定",
                )
            }
            IconButton(onClick = onEdit) {
                Icon(HugeIcons.PencilEdit01, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete01, contentDescription = "删除")
            }
        }
    }
}
