package me.rerere.rikkahub.ui.pages.knowledge

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.data.knowledge.SearchResult
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import androidx.documentfile.provider.DocumentFile
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
    val availableModels by vm.availableModels.collectAsStateWithLifecycle()
    val modelNameMap = remember(availableModels) { availableModels.associate { it.id to it.displayName } }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var tabIndex by remember { mutableIntStateOf(0) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 导入状态由 VM 管理（ImportProgressState + FailedImportItem）

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

    /** 批量导入一组 URI 文件 — 使用 VM 的细化进度系统 */
    suspend fun importUris(context: android.content.Context, kbId: String, uris: List<Uri>) {
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

        if (fileContents.isEmpty()) {
            vm.addDocumentsConcurrent(kbId, emptyList())
        } else {
            vm.addDocumentsConcurrent(kbId, fileContents)
        }
    }

    // 多文件选择器
    val multiFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch { importUris(context, kbId, uris) }
        }
    }

    // 目录选择器（递归扫描子目录）
    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            scope.launch {
                try {
                    val uris = scanDirectory(context, treeUri)
                    if (uris.isEmpty()) {
                        vm.addDocumentsConcurrent(kbId, emptyList())
                    } else {
                        importUris(context, kbId, uris)
                    }
                } catch (e: Exception) { }
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(kb?.name ?: stringResource(R.string.kb_detail)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = {
                        vm.initEditForm(kb ?: return@IconButton)
                        navController.navigate(Screen.KnowledgeBaseEdit(kbId))
                    }) {
                        Icon(HugeIcons.Edit01, stringResource(R.string.kb_edit))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Info chips
            kb?.let { k ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InfoChip(stringResource(R.string.kb_embedding_model), modelNameMap[k.modelId] ?: k.modelId.take(12) + "...")
                    InfoChip(stringResource(R.string.kb_dimensions, k.dimensions).split(":").firstOrNull()?.trim() ?: "维度", k.dimensions.toString())
                }
            }

            // Tabs
            SecondaryTabRow(selectedTabIndex = tabIndex) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 },
                    text = { Text(stringResource(R.string.kb_doc_tab)) },
                    icon = { Icon(HugeIcons.File02, null, modifier = Modifier.size(16.dp)) }
                )
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 },
                    text = { Text(stringResource(R.string.kb_search_tab)) },
                    icon = { Icon(HugeIcons.GlobalSearch, null, modifier = Modifier.size(16.dp)) }
                )
            }

            when (tabIndex) {
                0 -> DocumentTab(
                    fileList = fileList,
                    vm = vm,
                    kbId = kbId,
                    onPickFile = { multiFileLauncher.launch(supportedMimeTypes) },
                    onPickDir = { dirPickerLauncher.launch(null) },
                    onDeleteFile = { filePath -> vm.deleteFile(kbId, filePath) },
                    onViewChunks = { filePath ->
                        vm.loadChunks(kbId, filePath)
                        navController.navigate(Screen.KnowledgeBaseChunks(kbId, Uri.encode(filePath)))
                    }
                )
                1 -> SearchTab(
                    query = searchQuery,
                    results = searchResults,
                    isSearching = isSearching,
                    onSearch = { query -> vm.search(kbId, query) },
                    onClear = { vm.clearSearch() },
                    onViewChunk = { result ->
                        vm.loadChunks(kbId, result.filePath)
                        navController.navigate(Screen.KnowledgeBaseChunks(kbId, Uri.encode(result.filePath)))
                    }
                )
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

@Composable
private fun DocumentTab(
    fileList: List<KnowledgeVM.FileInfo>,
    vm: KnowledgeVM,
    kbId: String,
    onPickFile: () -> Unit,
    onPickDir: () -> Unit,
    onDeleteFile: (String) -> Unit,
    onViewChunks: (String) -> Unit,
) {
    val importProgress by vm.importProgress.collectAsStateWithLifecycle()
    val failedItems by vm.failedItems.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onPickFile,
                modifier = Modifier.weight(1f),
                enabled = !importProgress.active
            ) {
                Icon(HugeIcons.Upload02, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.kb_import_docs))
            }
            FilledTonalButton(
                onClick = onPickDir,
                modifier = Modifier.weight(1f),
                enabled = !importProgress.active
            ) {
                Icon(HugeIcons.Folder01, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.kb_import_dir))
            }
        }

        // 细化进度面板
        ImportProgressPanel(
            progress = importProgress,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        // 失败重试面板
        FailedImportBanner(
            failedItems = failedItems,
            onRetry = { itemId -> vm.retryFailedItem(kbId, itemId) },
            onDismiss = { itemId -> vm.dismissFailedItem(itemId) },
            onClearAll = { vm.clearAllFailedItems() },
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Spacer(Modifier.height(16.dp))

        if (fileList.isEmpty()) {
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(fileList, key = { it.filePath }) { file ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(HugeIcons.File02, null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(R.string.kb_chunks_label, file.chunkCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(onClick = { onViewChunks(file.filePath) }) {
                                Text(stringResource(R.string.kb_view_chunks))
                            }
                            IconButton(onClick = { onDeleteFile(file.filePath) }) {
                                Icon(HugeIcons.Delete01, stringResource(R.string.kb_delete),
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchTab(
    query: String,
    results: List<SearchResult>,
    isSearching: Boolean,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    onViewChunk: (SearchResult) -> Unit,
) {
    var searchText by remember(query) { mutableStateOf(query) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.kb_search_placeholder)) },
            trailingIcon = {
                if (searchText.isNotBlank()) {
                    IconButton(onClick = { onSearch(searchText) }) {
                        Icon(HugeIcons.GlobalSearch, stringResource(R.string.kb_search_tab))
                    }
                }
            },
            singleLine = true,
        )

        Spacer(Modifier.height(8.dp))

        if (isSearching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (results.isEmpty() && query.isNotBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.kb_search_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(HugeIcons.GlobalSearch, null, modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.kb_search_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.documentId }) { result ->
                    SearchResultCard(result = result, onClick = { onViewChunk(result) })
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    result: SearchResult,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
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
                    Text(stringResource(R.string.kb_search_tab), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(result.fileName, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/**
 * 读取文档内容（支持 PDF、DOCX、PPTX、EPUB、纯文本）
 */
/**
 * 从 content:// URI 中提取文件名
 */
private fun getFileNameFromUri(context: android.content.Context, uri: android.net.Uri): String? {
    var name: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = it.getString(nameIndex)
                }
            }
        }
    }
    if (name == null) {
        name = uri.lastPathSegment
    }
    // 确保有扩展名
    if (name != null && !name.contains(".")) {
        name = "$name.md"
    }
    return name
}

/**
 * 根据 MIME 类型获取文件扩展名
 */
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
    // 先复制到临时文件（解析器需要 File）
    val tempFile = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cacheDir = java.io.File(context.cacheDir, "kb_import")
            cacheDir.mkdirs()
            val tmp = java.io.File.createTempFile("import_", getExtension(mimeType), cacheDir)
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                return@withContext tmp
            }
            inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            tmp
        }
    }

    return runCatching {
        when {
            mimeType == "application/pdf" || mimeType.contains("pdf") ->
                PdfParser.parserPdf(tempFile)
            mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                mimeType.contains("word") || tempFile.name.endsWith(".docx") ->
                DocxParser.parse(tempFile)
            mimeType == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ||
                mimeType.contains("presentation") || tempFile.name.endsWith(".pptx") ->
                PptxParser.parse(tempFile)
            mimeType == "application/epub+zip" || tempFile.name.endsWith(".epub") ->
                EpubParser.parse(tempFile)
            mimeType.startsWith("text/") || tempFile.name.endsWith(".md") ->
                tempFile.readText()
            else ->
                tempFile.readText()
        }
    }.getOrElse {
        tempFile.readText() // fallback
    }.also {
        tempFile.delete()
    }
}

/** 判断文件扩展名是否属于支持的文档类型 */
private fun isSupportedExtension(fileName: String?): Boolean {
    if (fileName == null) return false
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in listOf("txt", "md", "markdown", "pdf", "docx", "pptx", "epub", "csv", "json", "xml", "yaml", "yml")
}

/**
 * 递归扫描 DocumentFile 目录，收集所有支持的文档
 */
private suspend fun scanDirectory(context: android.content.Context, treeUri: android.net.Uri): List<android.net.Uri> {
    return withContext(Dispatchers.IO) {
        val result = mutableListOf<android.net.Uri>()
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext result
        scanDirRecursive(root, result)
        result
    }
}

private fun scanDirRecursive(dir: DocumentFile, result: MutableList<android.net.Uri>) {
    for (child in dir.listFiles()) {
        when {
            child.isDirectory -> scanDirRecursive(child, result)
            child.isFile && isSupportedExtension(child.name) -> {
                result.add(child.uri)
            }
        }
    }
}

