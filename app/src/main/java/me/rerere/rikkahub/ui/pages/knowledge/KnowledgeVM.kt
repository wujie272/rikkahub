package me.rerere.rikkahub.ui.pages.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.knowledge.KnowledgeBaseEntity
import me.rerere.rikkahub.data.knowledge.KnowledgeDocumentEntity
import me.rerere.rikkahub.data.knowledge.KnowledgeService
import me.rerere.rikkahub.data.knowledge.EmbeddingService
import me.rerere.rikkahub.data.knowledge.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.uuid.Uuid

class KnowledgeVM(
    private val context: Application,
    private val knowledgeService: KnowledgeService,
    private val settingsStore: SettingsStore,
    private val embeddingService: EmbeddingService,
) : ViewModel() {

    // ============ 列表页状态 ============
    val knowledgeBases: StateFlow<List<KnowledgeBaseEntity>> = knowledgeService.observeAllKnowledgeBases()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // ============ 详情页状态 ============
    private val _selectedKb = MutableStateFlow<KnowledgeBaseEntity?>(null)
    val selectedKb: StateFlow<KnowledgeBaseEntity?> = _selectedKb.asStateFlow()

    private val _documents = MutableStateFlow<List<KnowledgeDocumentEntity>>(emptyList())
    val documents: StateFlow<List<KnowledgeDocumentEntity>> = _documents.asStateFlow()

    private val _fileList = MutableStateFlow<List<FileInfo>>(emptyList())
    val fileList: StateFlow<List<FileInfo>> = _fileList.asStateFlow()

    private val _chunks = MutableStateFlow<List<KnowledgeDocumentEntity>>(emptyList())
    val chunks: StateFlow<List<KnowledgeDocumentEntity>> = _chunks.asStateFlow()

    private val _selectedChunk = MutableStateFlow<KnowledgeDocumentEntity?>(null)
    val selectedChunk: StateFlow<KnowledgeDocumentEntity?> = _selectedChunk.asStateFlow()

    // ============ 搜索状态 ============
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // ============ 编辑页状态 ============
    data class EditForm(
        val name: String = "",
        val description: String = "",
        val modelId: String = "",
        val modelDisplayName: String = "",
        val dimensions: Int = 1536,
        val documentCount: Int = 6,
        val chunkSize: Int = 1000,
        val chunkOverlap: Int = 200,
        val chunkStrategy: String = "fixed",
        val threshold: Float = 0.35f,
    )
    private val _editForm = MutableStateFlow(EditForm())
    val editForm: StateFlow<EditForm> = _editForm.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelDisplay>>(emptyList())
    val availableModels: StateFlow<List<ModelDisplay>> = _availableModels.asStateFlow()

    // ============ Snackbar / Toast ============
    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar.asStateFlow()

    data class ModelDisplay(
        val id: String,
        val displayName: String,
        val providerName: String,
        val dimensions: Int,
    )

    data class FileInfo(
        val filePath: String,
        val fileName: String,
        val chunkCount: Int,
    )

    init {
        loadEmbeddingModels()
    }

    fun dismissSnackbar() { _snackbar.value = null }

    /**
     * 检测模型的实际嵌入维度
     * 通过嵌入一段测试文本，从返回的向量长度获取真实维度
     */
    fun detectModelDimensions(modelId: String) {
        viewModelScope.launch {
            try {
                val testVector = embeddingService.embed("test", modelId)
                if (testVector.isNotEmpty()) {
                    _editForm.value = _editForm.value.copy(dimensions = testVector.size)
                }
            } catch (_: Exception) {
                // 检测失败时保持原有维度值不变
            }
        }
    }

    // ============ 创建/编辑 ============
    fun loadEmbeddingModels() {
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.value
            val models = mutableListOf<ModelDisplay>()
            for (provider in settings.providers) {
                for (model in provider.models) {
                    if (model.type == ModelType.EMBEDDING) {
                        models.add(ModelDisplay(
                            id = model.id.toString(),
                            displayName = model.displayName.ifBlank { model.modelId },
                            providerName = provider.name.ifBlank { provider.id.toString() },
                            dimensions = 1536, // EmbeddingGenerationParams.dimensions, Model class doesn't expose this yet
                        ))
                    }
                }
            }
            _availableModels.value = models
        }
    }

    fun initCreateForm() {
        val models = _availableModels.value
        _editForm.value = EditForm(
            modelId = models.firstOrNull()?.id ?: "",
            modelDisplayName = models.firstOrNull()?.displayName ?: "",
        )
    }

    fun initEditForm(kb: KnowledgeBaseEntity) {
        val modelDisplay = _availableModels.value.find { it.id == kb.modelId }
        _editForm.value = EditForm(
            name = kb.name,
            description = kb.description,
            modelId = kb.modelId,
            modelDisplayName = modelDisplay?.displayName ?: "",
            dimensions = kb.dimensions,
            documentCount = kb.documentCount,
            chunkSize = kb.chunkSize,
            chunkOverlap = kb.chunkOverlap,
            chunkStrategy = kb.chunkStrategy,
            threshold = kb.threshold,
        )
    }

    fun updateEditForm(transform: (EditForm) -> EditForm) {
        _editForm.value = transform(_editForm.value)
    }

    fun saveKnowledgeBase(isEditing: Boolean, id: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            val form = _editForm.value
            if (form.name.isBlank()) {
                _snackbar.value = context.getString(R.string.kb_name_required)
                return@launch
            }
            if (form.modelId.isBlank()) {
                _snackbar.value = context.getString(R.string.kb_model_required)
                return@launch
            }
            try {
                if (isEditing && id != null) {
                    knowledgeService.updateKnowledgeBase(
                        id = id.toString(),
                        name = form.name,
                        description = form.description,
                        modelId = form.modelId,
                        dimensions = form.dimensions,
                        documentCount = form.documentCount,
                        chunkSize = form.chunkSize,
                        chunkOverlap = form.chunkOverlap,
                        chunkStrategy = form.chunkStrategy,
                        threshold = form.threshold,
                    )
                    _snackbar.value = context.getString(R.string.kb_updated)
                } else {
                    knowledgeService.createKnowledgeBase(
                        name = form.name,
                        description = form.description,
                        modelId = form.modelId,
                        dimensions = form.dimensions,
                        documentCount = form.documentCount,
                        chunkSize = form.chunkSize,
                        chunkOverlap = form.chunkOverlap,
                        chunkStrategy = form.chunkStrategy,
                        threshold = form.threshold,
                    )
                    _snackbar.value = context.getString(R.string.kb_created)
                }
                onDone()
            } catch (e: Exception) {
                _snackbar.value = context.getString(R.string.kb_save_failed, e.message ?: "")
            }
        }
    }

    fun deleteKnowledgeBase(id: String) {
        viewModelScope.launch {
            knowledgeService.deleteKnowledgeBase(id)
            _snackbar.value = context.getString(R.string.kb_deleted)
        }
    }

    // ============ 详情 ============
    fun selectKnowledgeBase(id: String) {
        viewModelScope.launch {
            val kb = knowledgeService.getKnowledgeBase(id)
            _selectedKb.value = kb
            if (kb != null) {
                val files = knowledgeService.getDistinctFiles(id)
                _fileList.value = files.map {
                    FileInfo(
                        filePath = it.file_path,
                        fileName = it.file_name.ifBlank { it.file_path },
                        chunkCount = knowledgeService.getChunksByFile(id, it.file_path).size,
                    )
                }
            }
        }
    }

    // ============ 导入状态（流式 + 进度 + 取消 + 失败重试）============
    private val _importProgress = MutableStateFlow(me.rerere.rikkahub.ui.components.settings.ImportProgressState())
    val importProgress: StateFlow<me.rerere.rikkahub.ui.components.settings.ImportProgressState> = _importProgress.asStateFlow()

    private val _failedItems = MutableStateFlow<List<me.rerere.rikkahub.ui.components.settings.FailedImportItem>>(emptyList())
    val failedItems: StateFlow<List<me.rerere.rikkahub.ui.components.settings.FailedImportItem>> = _failedItems.asStateFlow()

    /** 当前导入任务，用于取消 */
    private var _importJob: Job? = null

    fun cancelImport() {
        _importJob?.cancel()
        _importProgress.value = me.rerere.rikkahub.ui.components.settings.ImportProgressState()
    }

    fun dismissFailedItem(itemId: String) {
        _failedItems.value = _failedItems.value.filter { it.id != itemId }
    }

    fun clearAllFailedItems() {
        _failedItems.value = emptyList()
    }

    fun retryFailedItem(kbId: String, itemId: String) {
        viewModelScope.launch {
            val item = _failedItems.value.find { it.id == itemId } ?: return@launch
            _failedItems.value = _failedItems.value.filter { it.id != itemId }
            _snackbar.value = "请重新选择文件: ${item.fileName}"
        }
    }

    // ============ 文档 ============
    fun addDocument(kbId: String, content: String, filePath: String, fileName: String,
                    onProgress: (Int, Int) -> Unit = { _, _ -> }, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val result = knowledgeService.addDocument(kbId, content, filePath, fileName, onProgress)
            result.onSuccess {
                _snackbar.value = context.getString(R.string.kb_added_chunks, it)
                selectKnowledgeBase(kbId)
                onDone()
            }.onFailure {
                _snackbar.value = context.getString(R.string.kb_add_failed, it.message ?: "")
            }
        }
    }

    fun renameFile(kbId: String, filePath: String, newName: String) {
        viewModelScope.launch {
            knowledgeService.renameFile(kbId, filePath, newName)
            _snackbar.value = context.getString(R.string.kb_file_renamed)
            selectKnowledgeBase(kbId)
        }
    }

    fun deleteFile(kbId: String, filePath: String) {
        viewModelScope.launch {
            knowledgeService.deleteFile(kbId, filePath)
            _snackbar.value = context.getString(R.string.kb_file_deleted)
            selectKnowledgeBase(kbId)
        }
    }

    /** 流式导入目录：边扫边导，不攒内存，支持取消和去重 */
    fun importDirectory(
        kbId: String,
        androidContext: android.content.Context,
        treeUri: android.net.Uri,
    ) {
        _importJob = viewModelScope.launch {
            _importProgress.value = me.rerere.rikkahub.ui.components.settings.ImportProgressState(
                active = true,
                currentStage = me.rerere.rikkahub.ui.components.settings.ProcessingStage.SCANNING,
                currentFileName = context.getString(R.string.kb_import_scanning),
            )

            // 1. 扫描目录（IO 线程）
            val uris = withContext(Dispatchers.IO) {
                val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(androidContext, treeUri)
                    ?: return@withContext emptyList()
                val result = mutableListOf<android.net.Uri>()
                scanDirRecursive(root, result)
                result
            }

            if (!isActive) return@launch
            if (uris.isEmpty()) {
                _importProgress.value = me.rerere.rikkahub.ui.components.settings.ImportProgressState()
                _snackbar.value = context.getString(R.string.kb_import_no_docs)
                return@launch
            }

            // 2. 去重：跳过已导入的文件
            val existingPaths = knowledgeService.getExistingFilePaths(kbId)
            val newUris = uris.filter { it.toString() !in existingPaths }
            val skipped = uris.size - newUris.size

            _importProgress.value = _importProgress.value.copy(
                totalFiles = newUris.size,
                currentFileName = if (skipped > 0) "已跳过 $skipped 个重复文件" else "",
            )

            if (newUris.isEmpty()) {
                _importProgress.value = me.rerere.rikkahub.ui.components.settings.ImportProgressState()
                _snackbar.value = "所有文件已导入，无需重复导入"
                selectKnowledgeBase(kbId)
                return@launch
            }

            // 3. 流式处理：每个文件读→分块→嵌入→入库，用 Semaphore 控制并发
            val semaphore = Semaphore(3) // 最多3个并发嵌入
            var completed = 0
            val failed = mutableListOf<me.rerere.rikkahub.ui.components.settings.FailedImportItem>()

            for (uri in newUris) {
                if (!isActive) break

                val fileName = getFileNameFromUri(androidContext, uri) ?: "unknown"
                _importProgress.value = _importProgress.value.copy(
                    currentFileName = fileName,
                    currentStage = me.rerere.rikkahub.ui.components.settings.ProcessingStage.READING,
                    currentFileProgress = 0f,
                )

                // 用 Semaphore 控制并发数，避免打爆 API
                semaphore.withPermit {
                    if (!isActive) return@withPermit

                    // 读文件
                    val mimeType = try {
                        androidContext.contentResolver.getType(uri) ?: "text/plain"
                    } catch (_: Exception) { "text/plain" }
                    val content = readDocumentContent(androidContext, uri, mimeType)
                    if (content.isBlank()) {
                        completed++
                        _importProgress.value = _importProgress.value.copy(completedFiles = completed)
                        return@withPermit
                    }

                    _importProgress.value = _importProgress.value.copy(
                        currentStage = me.rerere.rikkahub.ui.components.settings.ProcessingStage.EMBEDDING,
                        currentFileProgress = 0.5f,
                    )

                    // 直接导入单个文件（不攒内存）
                    val result = knowledgeService.addDocument(
                        kbId = kbId,
                        content = content,
                        filePath = uri.toString(),
                        fileName = fileName,
                    )

                    result.onFailure { e ->
                        failed.add(me.rerere.rikkahub.ui.components.settings.FailedImportItem(
                            id = uri.toString(),
                            fileName = fileName,
                            errorMessage = e.message ?: "Unknown error",
                        ))
                    }

                    completed++
                    _importProgress.value = _importProgress.value.copy(
                        completedFiles = completed,
                        currentFileProgress = 1f,
                    )
                }
            }

            // 4. 完成
            _importProgress.value = me.rerere.rikkahub.ui.components.settings.ImportProgressState()
            _failedItems.value = failed
            _snackbar.value = context.getString(R.string.kb_added_chunks, completed)
            selectKnowledgeBase(kbId)
        }
    }

    /** 保留旧接口：直接导入已读好的文件列表（用于多文件选择器） */
    fun importFiles(
        kbId: String,
        files: List<Triple<String, String, String>>,
    ) {
        _importJob = viewModelScope.launch {
            val total = files.size
            _importProgress.value = me.rerere.rikkahub.ui.components.settings.ImportProgressState(
                totalFiles = total,
                active = true,
            )

            // 去重
            val existingPaths = knowledgeService.getExistingFilePaths(kbId)
            val newFiles = files.filter { it.second !in existingPaths }
            val skipped = files.size - newFiles.size

            _importProgress.value = _importProgress.value.copy(
                totalFiles = newFiles.size,
                currentFileName = if (skipped > 0) "已跳过 $skipped 个重复文件" else "",
            )

            if (newFiles.isEmpty()) {
                _importProgress.value = me.rerere.rikkahub.ui.components.settings.ImportProgressState()
                _snackbar.value = "所有文件已导入"
                selectKnowledgeBase(kbId)
                return@launch
            }

            val semaphore = Semaphore(3)
            var completed = 0
            val failed = mutableListOf<me.rerere.rikkahub.ui.components.settings.FailedImportItem>()

            for ((content, filePath, fileName) in newFiles) {
                if (!isActive) break

                _importProgress.value = _importProgress.value.copy(
                    currentFileName = fileName,
                    currentStage = me.rerere.rikkahub.ui.components.settings.ProcessingStage.EMBEDDING,
                    currentFileProgress = 0.3f,
                )

                semaphore.withPermit {
                    if (!isActive) return@withPermit
                    val result = knowledgeService.addDocument(kbId, content, filePath, fileName)
                    result.onFailure { e ->
                        failed.add(me.rerere.rikkahub.ui.components.settings.FailedImportItem(
                            id = filePath, fileName = fileName,
                            errorMessage = e.message ?: "",
                        ))
                    }
                    completed++
                    _importProgress.value = _importProgress.value.copy(
                        completedFiles = completed,
                        currentFileProgress = 1f,
                    )
                }
            }

            _importProgress.value = me.rerere.rikkahub.ui.components.settings.ImportProgressState()
            _failedItems.value = failed
            _snackbar.value = context.getString(R.string.kb_added_chunks, completed)
            selectKnowledgeBase(kbId)
        }
    }

    // ============ 分块查看 ============
    fun loadChunks(kbId: String, filePath: String) {
        viewModelScope.launch {
            _chunks.value = knowledgeService.getChunksByFile(kbId, filePath)
        }
    }

    fun updateChunkContent(chunkId: String, newText: String) {
        viewModelScope.launch {
            val ok = knowledgeService.updateChunkContent(chunkId, newText)
            if (ok) {
                _snackbar.value = context.getString(R.string.kb_chunk_updated)
                // Refresh local state
                _chunks.value = _chunks.value.map {
                    if (it.id == chunkId) it.copy(chunkText = newText) else it
                }
            } else {
                _snackbar.value = context.getString(R.string.kb_update_failed)
            }
        }
    }

    fun toggleChunkEnabled(chunkId: String, enabled: Boolean) {
        viewModelScope.launch {
            knowledgeService.setChunkEnabled(chunkId, enabled)
            _chunks.value = _chunks.value.map {
                if (it.id == chunkId) it.copy(enabled = enabled) else it
            }
        }
    }

    fun deleteChunk(chunkId: String) {
        viewModelScope.launch {
            knowledgeService.deleteChunk(chunkId)
            _chunks.value = _chunks.value.filter { it.id != chunkId }
            _snackbar.value = context.getString(R.string.kb_chunk_deleted)
        }
    }

    // ============ 搜索 ============
    fun search(kbId: String, query: String, tagFilter: String? = null) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _isSearching.value = true
            _searchQuery.value = query
            _searchResults.value = knowledgeService.search(kbId, query, tagFilter = tagFilter)
            _isSearching.value = false
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    // ============ 导入辅助函数 ============

    /** 递归扫描目录，收集所有支持的文档 URI（跳过隐藏文件/文件夹） */
    private fun scanDirRecursive(
        dir: androidx.documentfile.provider.DocumentFile,
        result: MutableList<android.net.Uri>,
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            // 跳过 .开头 的隐藏文件和文件夹（.git, .obsidian, .DS_Store 等）
            if (name.startsWith(".")) continue
            when {
                child.isDirectory -> scanDirRecursive(child, result)
                child.isFile && isSupportedExtension(name) -> result.add(child.uri)
            }
        }
    }

    /** 判断文件扩展名是否属于支持的文档类型 */
    private fun isSupportedExtension(fileName: String?): Boolean {
        if (fileName == null) return false
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in listOf("txt", "md", "markdown", "pdf", "docx", "pptx", "epub", "csv", "json", "xml", "yaml", "yml")
    }

    /** 从 content:// URI 中提取文件名 */
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

    /** 读取文档内容（支持 PDF、DOCX、PPTX、EPUB、纯文本） */
    private fun readDocumentContent(
        context: android.content.Context,
        uri: android.net.Uri,
        mimeType: String,
    ): String {
        val tempFile = kotlinx.coroutines.runBlocking {
            withContext(Dispatchers.IO) {
                val cacheDir = java.io.File(context.cacheDir, "kb_import")
                cacheDir.mkdirs()
                val ext = when {
                    mimeType.contains("pdf") -> ".pdf"
                    mimeType.contains("word") || mimeType.contains("document") -> ".docx"
                    mimeType.contains("presentation") -> ".pptx"
                    mimeType.contains("epub") -> ".epub"
                    mimeType.contains("markdown") || mimeType.contains("md") -> ".md"
                    else -> ".txt"
                }
                val tmp = java.io.File.createTempFile("import_", ext, cacheDir)
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        inputStream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
                    }
                } catch (_: Exception) {}
                tmp
            }
        }

        return runCatching {
            when {
                mimeType == "application/pdf" || mimeType.contains("pdf") ->
                    me.rerere.document.PdfParser.parserPdf(tempFile)
                mimeType.contains("word") || tempFile.name.endsWith(".docx") ->
                    me.rerere.document.DocxParser.parse(tempFile)
                mimeType.contains("presentation") || tempFile.name.endsWith(".pptx") ->
                    me.rerere.document.PptxParser.parse(tempFile)
                mimeType == "application/epub+zip" || tempFile.name.endsWith(".epub") ->
                    me.rerere.document.EpubParser.parse(tempFile)
                mimeType.startsWith("text/") || tempFile.name.endsWith(".md") ->
                    tempFile.readText()
                else -> tempFile.readText()
            }
        }.getOrElse { tempFile.readText() }.also { tempFile.delete() }
    }
}
