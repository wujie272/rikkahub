package me.rerere.rikkahub.ui.pages.garden

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.data.garden.GardenSearchService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Composable
fun GardenKnowledgePage() {
    val vm: GardenKnowledgeVM = koinViewModel()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("数字花园") },
                navigationIcon = { BackButton() },
                actions = {
                    FilledTonalButton(
                        onClick = { vm.startIndex() },
                        enabled = !uiState.isIndexing && uiState.vaultPath.isNotBlank(),
                    ) {
                        Icon(HugeIcons.Refresh01, null, modifier = Modifier.padding(end = 4.dp))
                        Text(if (uiState.isIndexing) "索引中..." else "重新索引")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        GardenKnowledgeContent(
            modifier = Modifier.padding(innerPadding),
            uiState = uiState,
            onSearch = { vm.search(it) },
            onStartIndex = { vm.startIndex() },
            onSelectFolder = { vm.selectFolder(it) },
        )
    }
}

@Composable
private fun GardenKnowledgeContent(
    modifier: Modifier = Modifier,
    uiState: GardenKnowledgeVM.UiState,
    onSearch: (String) -> Unit,
    onStartIndex: () -> Unit,
    onSelectFolder: (String?) -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 路径设置
        item("path") {
            GardenVaultPathInput(
                path = uiState.vaultPath,
                onPathChange = { vm.updateVaultPath(it) },
            )
        }

        item("stats") {
            StatsCard(
                totalFiles = uiState.totalFiles,
                totalChunks = uiState.totalChunks,
                folderCount = uiState.folderCount,
                lastUpdated = uiState.lastUpdated,
                isReady = uiState.isReady,
                isIndexing = uiState.isIndexing,
                indexProgress = uiState.indexProgress,
            )
        }

        // 错误信息
        uiState.error?.let { error ->
            item("error") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // 搜索区域（仅在有数据时显示）
        if (uiState.isReady) {
            item("search") {
                var query by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onSearch(it)
                    },
                    placeholder = { Text("搜索你的笔记...") },
                    leadingIcon = { Icon(HugeIcons.Search01, null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            // 文件夹过滤
            if (uiState.folders.isNotEmpty()) {
                item("folders") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onSelectFolder(null) },
                        ) {
                            Text("全部")
                        }
                        uiState.folders.take(8).forEach { folder ->
                            OutlinedButton(
                                onClick = { onSelectFolder(folder) },
                            ) {
                                Icon(HugeIcons.Folder01, null, modifier = Modifier.padding(end = 4.dp))
                                Text(folder)
                            }
                        }
                    }
                }
            }

            // 搜索结果
            if (uiState.searchQuery.isNotBlank()) {
                if (uiState.isSearching) {
                    item("searching") {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                } else if (uiState.searchResults.isEmpty()) {
                    item("noResults") {
                        Text(
                            text = "没有找到相关笔记",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    items(uiState.searchResults, key = { it.id }) { result ->
                        SearchResultCard(result = result)
                    }
                }
            }

            // 未搜索时显示提示
            if (uiState.searchQuery.isBlank()) {
                item("hint") {
                    Text(
                        text = "输入关键词搜索你的笔记库，支持语义匹配",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        // 未索引时的引导
        if (!uiState.isReady && !uiState.isIndexing) {
            item("onboarding") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CustomColors.cardColorsOnSurfaceContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            HugeIcons.Bookshelf01,
                            null,
                            modifier = Modifier.padding(bottom = 16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "笔记库未索引",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "点击「重新索引」扫描你的 1,141 篇笔记\n建立语义搜索索引",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(onClick = onStartIndex) {
                            Text("设置路径并开始索引")
                        }
                    }
                }
            }
        }

        // 底部留白
        item("spacer") {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatsCard(
    totalFiles: Int,
    totalChunks: Int,
    folderCount: Int,
    lastUpdated: Long,
    isReady: Boolean,
    isIndexing: Boolean,
    indexProgress: me.rerere.rikkahub.data.garden.GardenIndexService.IndexProgress?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatItem(label = "文件", value = "$totalFiles")
                StatItem(label = "分块", value = "$totalChunks")
                StatItem(label = "文件夹", value = "$folderCount")
            }

            if (isIndexing && indexProgress != null) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = {
                        if (indexProgress.totalFiles > 0)
                            indexProgress.processedFiles.toFloat() / indexProgress.totalFiles
                        else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "索引中: ${indexProgress.processedFiles}/${indexProgress.totalFiles} · " +
                            "新增 ${indexProgress.newChunks} · 跳过 ${indexProgress.skippedFiles} · " +
                            "错误 ${indexProgress.errors}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (lastUpdated > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
                Text(
                    text = "上次更新: ${dateFormat.format(Date(lastUpdated))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!isReady) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "⚠ 尚未索引",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchResultCard(
    result: GardenSearchService.SearchResult,
) {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        HugeIcons.File02,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(
                        text = result.filePath.substringAfterLast("/"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "%.0f%%".format(result.score * 100),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            if (result.sourceFolder.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(
                        HugeIcons.Folder01,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp).then(Modifier.height(14.dp)),
                    )
                    Text(
                        text = result.sourceFolder,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = result.chunkText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


@Composable
private fun GardenVaultPathInput(
    path: String,
    onPathChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // 从 content:// URI 解析文件路径
            // SAF 返回的格式: content://com.android.externalstorage.documents/tree/primary%3ADocuments%2F...
            val pathStr = uri.toString()
            val decodedPath = try {
                val encoded = pathStr.substringAfter("tree/")
                java.net.URLDecoder.decode(encoded, "UTF-8")
                    .replace("primary:", "/storage/emulated/0/")
            } catch (e: Exception) {
                pathStr
            }
            onPathChange(decodedPath)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = path,
            onValueChange = onPathChange,
            placeholder = { Text("/storage/emulated/0/Documents/你的笔记库") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            readOnly = false,
        )
        Spacer(modifier = Modifier.width(8.dp))
        FilledTonalButton(
            onClick = { launcher.launch(null) },
        ) {
            Icon(HugeIcons.Folder01, null, modifier = Modifier.padding(end = 4.dp))
            Text("选择")
        }
    }
}
