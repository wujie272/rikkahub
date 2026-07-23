package me.rerere.rikkahub.ui.pages.knowledge

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.rikkahub.data.knowledge.SearchResult
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.document.DocxParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.document.EpubParser
import me.rerere.rikkahub.ui.components.settings.ImportProgressPanel
import me.rerere.rikkahub.ui.components.settings.FailedImportBanner
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import androidx.compose.foundation.background

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseDetailPage(
    kbId: String,
    vm: KnowledgeVM = koinViewModel(),
) {
    val kb by vm.selectedKb.collectAsStateWithLifecycle()
    val fileList by vm.fileList.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val isSearching by vm.isSearching.collectAsStateWithLifecycle()
    val importProgress by vm.importProgress.collectAsStateWithLifecycle()
    val failedItems by vm.failedItems.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showMenu by remember { mutableStateOf(false) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var searchText by remember(searchQuery) { mutableStateOf(searchQuery) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Snackbar / Toast 提示
    val snackbar by vm.snackbar.collectAsStateWithLifecycle()
    val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
    LaunchedEffect(snackbar) {
        snackbar?.let { msg ->
            toaster.show(message = msg, type = com.dokar.sonner.ToastType.Success)
            vm.dismissSnackbar()
        }
    }

    // 重建索引状态
    val isRebuilding by vm.isRebuilding.collectAsStateWithLifecycle()
    val rebuildProgress by vm.rebuildProgress.collectAsStateWithLifecycle()
    val rebuildTotal by vm.rebuildTotal.collectAsStateWithLifecycle()
    var showRebuildConfirm by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var showTrashDialog by remember { mutableStateOf(false) }
    val deletedFiles by vm.deletedFiles.collectAsStateWithLifecycle()

    // 加载回收站数据
    LaunchedEffect(showTrashDialog) {
        if (showTrashDialog) {
            vm.loadDeletedFiles(kbId)
        }
    }

    // 网址导入对话框
    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text(stringResource(R.string.kb_url_import_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.kb_url_import_hint), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = { Text(stringResource(R.string.kb_url_import_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (vm.isImportingUrl.collectAsStateWithLifecycle().value) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.importFromUrl(kbId, urlInput)
                        showUrlDialog = false
                        urlInput = ""
                    },
                    enabled = urlInput.isNotBlank() && !vm.isImportingUrl.collectAsStateWithLifecycle().value
                ) {
                    Text(stringResource(R.string.kb_url_import_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false; urlInput = "" }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // 回收站对话框
    if (showTrashDialog) {
        AlertDialog(
            onDismissRequest = { showTrashDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.kb_trash_title))
                    if (deletedFiles.isNotEmpty()) {
                        TextButton(onClick = {
                            showTrashDialog = false
                            vm.emptyTrash(kbId)
                        }) {
                            Text(stringResource(R.string.kb_trash_empty_trash),
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            text = {
                if (deletedFiles.isEmpty()) {
                    Text(stringResource(R.string.kb_trash_empty))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(deletedFiles, key = { it.filePath }) { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${file.chunkCount} 个分块",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { vm.restoreFile(kbId, file.filePath) }) {
                                    Text(stringResource(R.string.kb_trash_restore))
                                }
                                TextButton(onClick = { vm.permanentlyDeleteFile(kbId, file.filePath) }) {
                                    Text(stringResource(R.string.kb_trash_permanently_delete),
                                        color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTrashDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    // 重建索引确认弹窗
    if (showRebuildConfirm) {
        AlertDialog(
            onDismissRequest = { showRebuildConfirm = false },
            title = { Text(stringResource(R.string.kb_rebuild_confirm_title)) },
            text = { Text(stringResource(R.string.kb_rebuild_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebuildConfirm = false
                        vm.rebuildIndex(kbId)
                    }
                ) {
                    Text(stringResource(R.string.kb_rebuild_confirm_continue))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRebuildConfirm = false }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    LaunchedEffect(kbId) {
        vm.selectKnowledgeBase(kbId)
    }

    // 支持的文档 MIME 类型
    val supportedMimeTypes = arrayOf(
        "text/*", "text/markdown", "application/octet-stream",
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/epub+zip",
    )

    // 笔记专用：只选 Markdown/文本文件
    val noteMimeTypes = arrayOf("text/markdown", "text/plain", "text/*", "application/octet-stream")

    val noteFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val fileContents = mutableListOf<Triple<String, String, String>>()
                for (uri in uris) {
                    try {
                        val mimeType = context.contentResolver.getType(uri) ?: "text/plain"
                        val fileName = getFileNameFromUri(context, uri) ?: "unknown"
                        val content = readDocumentContent(context, uri, mimeType)
                        if (content.isNotBlank()) {
                            fileContents.add(Triple(content, uri.toString(), fileName))
                        }
                    } catch (_: Exception) { }
                }
                if (fileContents.isNotEmpty()) {
                    vm.importFiles(kbId, fileContents)
                }
            }
        }
    }

    val multiFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val fileContents = mutableListOf<Triple<String, String, String>>()
                for (uri in uris) {
                    try {
                        val mimeType = context.contentResolver.getType(uri) ?: "text/plain"
                        val fileName = getFileNameFromUri(context, uri) ?: "unknown"
                        val content = readDocumentContent(context, uri, mimeType)
                        if (content.isNotBlank()) {
                            fileContents.add(Triple(content, uri.toString(), fileName))
                        }
                    } catch (_: Exception) { }
                }
                if (fileContents.isNotEmpty()) {
                    vm.importFiles(kbId, fileContents)
                }
            }
        }
    }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            vm.importDirectory(kbId, context, treeUri)
        }
    }

    // 底部添加数据源 Sheet
    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState,
        ) {
            AddDataSourceSheet(
                onPickFile = { showBottomSheet = false; multiFileLauncher.launch(supportedMimeTypes) },
                onPickDir = { showBottomSheet = false; dirPickerLauncher.launch(null) },
            )
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(kb?.name ?: stringResource(R.string.kb_detail)) },
                navigationIcon = { BackButton() },
                actions = {
                    // "+" 添加按钮
                    IconButton(onClick = { showBottomSheet = true }) {
                        Icon(HugeIcons.Add01, stringResource(R.string.kb_import_doc))
                    }
                    // "..." 菜单按钮
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(HugeIcons.Settings02, "更多")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("库设置") },
                                onClick = {
                                    showMenu = false
                                    vm.initEditForm(kb ?: return@DropdownMenuItem)
                                    navController.navigate(Screen.KnowledgeBaseEdit(kbId))
                                },
                                leadingIcon = { Icon(HugeIcons.Settings02, null, modifier = Modifier.size(18.dp)) }
                            )

                            DropdownMenuItem(
                                text = { Text("重建索引") },
                                onClick = {
                                    showMenu = false
                                    showRebuildConfirm = true
                                },
                                leadingIcon = { Icon(HugeIcons.Refresh01, null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text("回收站") },
                                onClick = {
                                    showMenu = false
                                    showTrashDialog = true
                                },
                                leadingIcon = { Icon(HugeIcons.Delete01, null, modifier = Modifier.size(18.dp)) }
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 搜索栏 + 条目数
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("关键词搜索") },
                    trailingIcon = {
                        if (searchText.isNotBlank()) {
                            IconButton(onClick = {
                                vm.search(kbId, searchText)
                            }) {
                                Icon(HugeIcons.GlobalSearch, "搜索")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
                Text(
                    text = "条目 (${fileList.size})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 导入进度
            ImportProgressPanel(
                progress = importProgress,
                onCancel = { vm.cancelImport() },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            FailedImportBanner(
                failedItems = failedItems,
                onRetry = { itemId -> vm.retryFailedItem(kbId, itemId) },
                onDismiss = { itemId -> vm.dismissFailedItem(itemId) },
                onClearAll = { vm.clearAllFailedItems() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // 重建索引进度条
            if (isRebuilding) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (rebuildTotal > 0)
                                stringResource(R.string.kb_rebuild_in_progress, rebuildProgress, rebuildTotal)
                            else "正在准备...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (rebuildTotal > 0) rebuildProgress.toFloat() / rebuildTotal.toFloat()
                                else 0f
                            },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }

            // 搜索结果 / 文件列表
            if (searchQuery.isNotBlank() || isSearching) {
                SearchResultsList(
                    results = searchResults,
                    isSearching = isSearching,
                    query = searchQuery,
                    onClear = { vm.clearSearch(); searchText = "" },
                    onViewChunk = { result ->
                        vm.loadChunks(kbId, result.filePath)
                        navController.navigate(Screen.KnowledgeBaseChunks(kbId, Uri.encode(result.filePath)))
                    }
                )
            } else if (fileList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(HugeIcons.File02, null, modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.kb_no_docs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.kb_no_docs_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp, vertical = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(fileList, key = { it.filePath }) { file ->
                        FileListItem(
                            fileName = file.fileName,
                            chunkCount = file.chunkCount,
                            onViewChunks = {
                                vm.loadChunks(kbId, file.filePath)
                                navController.navigate(Screen.KnowledgeBaseChunks(kbId, Uri.encode(file.filePath)))
                            },
                            onDelete = { vm.deleteFile(kbId, file.filePath) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileListItem(
    fileName: String,
    chunkCount: Int,
    onViewChunks: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onViewChunks() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 文件图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    HugeIcons.File02,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            // 文件名 + 分块信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 0.dp,
                    ) {
                        Text(
                            stringResource(R.string.kb_chunks_label, chunkCount),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (chunkCount > 1) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 0.dp,
                        ) {
                            Text(
                                stringResource(R.string.kb_view_chunks),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            // 删除按钮
            IconButton(onClick = onDelete) {
                Icon(
                    HugeIcons.Delete01,
                    stringResource(R.string.kb_delete),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchResultsList(
    results: List<SearchResult>,
    isSearching: Boolean,
    query: String,
    onClear: () -> Unit,
    onViewChunk: (SearchResult) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索结果头部
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "搜索结果 (${results.size})",
                style = MaterialTheme.typography.titleSmall,
            )
            androidx.compose.material3.TextButton(onClick = onClear) {
                Text("清除")
            }
        }

        if (isSearching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.kb_search_no_results),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 4.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(results, key = { it.documentId }) { result ->
                    SearchResultItem(
                        result = result,
                        onClick = { onViewChunk(result) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    result: SearchResult,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 分数 + 文件名
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { result.score.coerceIn(0f, 1f) },
                        modifier = Modifier.size(width = 60.dp, height = 6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(
                        "${(result.score * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    result.fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = result.content.take(300) + if (result.content.length > 300) "..." else "",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.expandedContext.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text(
                    text = result.expandedContext.take(200) + if (result.expandedContext.length > 200) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDataSourceSheet(
    onPickFile: () -> Unit,
    onPickDir: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "添加数据源",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        // 笔记
        DataSourceOption(
            icon = HugeIcons.BookOpen01,
            title = "笔记",
            subtitle = "选择 Markdown / 文本笔记文件导入",
            onClick = { showBottomSheet = false; noteFileLauncher.launch(noteMimeTypes) }
        )

        // 文件
        DataSourceOption(
            icon = HugeIcons.File02,
            title = "文件",
            subtitle = "支持 txt / md / html / docx / pdf / pptx / xlsx / epub，云端解码后支持 doc / ppt / xls 等更多格式",
            onClick = onPickFile,
        )

        // 网址
        DataSourceOption(
            icon = HugeIcons.Link01,
            title = "网址",
            subtitle = "获取网页内容与截图",
            onClick = { showBottomSheet = false; showUrlDialog = true }
        )

        // 文件夹 / 目录
        DataSourceOption(
            icon = HugeIcons.Folder01,
            title = "文件夹 / 目录",
            subtitle = "选择文件夹或工作区，批量提取其中的文本文件",
            onClick = onPickDir,
        )
    }
}

@Composable
private fun DataSourceOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ============ 工具函数 ============

private fun getFileNameFromUri(context: android.content.Context, uri: android.net.Uri): String? {
    var name: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = it.getString(nameIndex)
            }
        }
    }
    if (name == null) name = uri.lastPathSegment
    if (name != null && !name.contains(".")) name = "$name.md"
    return name
}

private fun getExtension(mimeType: String): String = when {
    mimeType.contains("pdf") -> ".pdf"
    mimeType.contains("word") || mimeType.contains("document") -> ".docx"
    mimeType.contains("presentation") -> ".pptx"
    mimeType.contains("epub") -> ".epub"
    mimeType.contains("markdown") || mimeType.contains("md") -> ".md"
    else -> ".txt"
}

fun readDocumentContent(
    context: android.content.Context,
    uri: android.net.Uri,
    mimeType: String,
): String {
    val tempFile = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cacheDir = java.io.File(context.cacheDir, "kb_import")
            cacheDir.mkdirs()
            val tmp = java.io.File.createTempFile("import_", getExtension(mimeType), cacheDir)
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) return@withContext tmp
            inputStream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
            tmp
        }
    }
    return runCatching {
        when {
            mimeType == "application/pdf" || mimeType.contains("pdf") ->
                PdfParser.parserPdf(tempFile)
            mimeType.contains("word") || tempFile.name.endsWith(".docx") ->
                DocxParser.parse(tempFile)
            mimeType.contains("presentation") || tempFile.name.endsWith(".pptx") ->
                PptxParser.parse(tempFile)
            mimeType == "application/epub+zip" || tempFile.name.endsWith(".epub") ->
                EpubParser.parse(tempFile)
            mimeType.startsWith("text/") || tempFile.name.endsWith(".md") ->
                tempFile.readText()
            else -> tempFile.readText()
        }
    }.getOrElse { tempFile.readText() }.also { tempFile.delete() }
}
