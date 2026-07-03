package me.rerere.rikkahub.ui.pages.grove

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
import me.rerere.rikkahub.data.grove.GroveSearchService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.width
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect

@Composable
fun GrovePage() {
    val vm: GroveVM = koinViewModel()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.snackbar) {
        uiState.snackbar?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearSnackbar()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Grove") },
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
        GroveContent(
            modifier = Modifier.padding(innerPadding),
            uiState = uiState,
            onSearch = { vm.search(it) },
            onStartIndex = { vm.startIndex() },
            onSelectFolder = { vm.selectFolder(it) },
            onPathChange = { vm.updateVaultPath(it) },
            onIgnoreChange = { vm.updateIgnoreFolders(it) },
            onIgnoreExtensionsChange = { vm.updateIgnoreExtensions(it) },
            onInjectionEnabledChange = { vm.updateInjectionEnabled(it) },
        )
    }
}

@Composable
private fun GroveContent(
    modifier: Modifier = Modifier,
    uiState: GroveVM.UiState,
    onSearch: (String) -> Unit,
    onStartIndex: () -> Unit,
    onSelectFolder: (String?) -> Unit,
    onPathChange: (String) -> Unit,
    onIgnoreChange: (String) -> Unit,
    onIgnoreExtensionsChange: (String) -> Unit,
    onInjectionEnabledChange: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 路径设置
        item("path") {
            GroveVaultPathInput(
                path = uiState.vaultPath,
                onPathChange = onPathChange,
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

        // 忽略目录设置
        item("ignore") {
            GroveIgnoreFoldersInput(
                ignoreFolders = uiState.ignoreFolders,
                onIgnoreChange = onIgnoreChange,
            )
        }

        // Grove 注入开关
        item("injection") {
            GroveInjectionToggle(
                enabled = uiState.injectionEnabled,
                onEnabledChange = onInjectionEnabledChange,
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
                    placeholder = { Text("搜索笔记...") },
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
                            text = "没有找到相关笔记，试试其他关键词",
                            style = MaterialTheme.typography.bodyMedium,
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
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        if (!uiState.hasEmbeddingModel) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "⚠ 未配置 embedding 模型，搜索功能不可用。请在「设置 → 模型和服务」中选择一个 embedding 模型后重新索引。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        } else {
                            Text(
                                text = "输入关键词搜索笔记，支持语义匹配",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
                            text = "点击「重新索引」扫描笔记库${if (uiState.totalFiles > 0) "（${uiState.totalFiles} 篇）" else ""}\n建立语义搜索索引",
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
    indexProgress: me.rerere.rikkahub.data.grove.GroveIndexService.IndexProgress?,
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
    result: GroveSearchService.SearchResult,
) {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    HugeIcons.File02,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.filePath.substringAfterLast("/"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (result.sourceFolder.isNotEmpty()) {
                        Text(
                            text = result.sourceFolder,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                // 相似度进度条
                val scorePercent = (result.score * 100).toInt()
                val scoreColor = when {
                    scorePercent >= 70 -> MaterialTheme.colorScheme.tertiary
                    scorePercent >= 50 -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "$scorePercent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = scoreColor,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { result.score.coerceIn(0f, 1f) },
                        modifier = Modifier.width(40.dp).height(4.dp),
                        color = scoreColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
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
private fun GroveVaultPathInput(
    path: String,
    onPathChange: (String) -> Unit,
) {
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

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (path.isEmpty()) "未选择笔记库" else path,
                style = MaterialTheme.typography.bodyMedium,
                color = if (path.isEmpty())
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
}

@Composable
private fun GroveIgnoreExtensionsInput(
    ignoreExtensions: String,
    onIgnoreExtensionsChange: (String) -> Unit,
    onInjectionEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "忽略后缀名（逗号分隔）",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "这些后缀名的文件不会被索引",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = ignoreExtensions,
                onValueChange = onIgnoreExtensionsChange,
                placeholder = { Text("输入要跳过的后缀名，如 png, jpg, pdf") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
            )
        }
    }
}

@Composable
@Composable
private fun GroveInjectionToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "对话时自动检索笔记",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "聊天时根据用户消息自动检索相关笔记内容并注入上下文",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        }
    }
}

private fun GroveIgnoreFoldersInput(
    ignoreFolders: String,
    onIgnoreChange: (String) -> Unit,
    onIgnoreExtensionsChange: (String) -> Unit,
    onInjectionEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "忽略目录（逗号分隔）",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "这些目录中的 .md 文件不会被索引",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = ignoreFolders,
                onValueChange = onIgnoreChange,
                placeholder = { Text("输入要跳过的目录名称，如 .obsidian, .trash") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
            )
        }
    }
}
