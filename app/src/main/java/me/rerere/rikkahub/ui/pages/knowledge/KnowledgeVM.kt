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
import me.rerere.rikkahub.data.knowledge.SearchResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlin.uuid.Uuid

class KnowledgeVM(
    private val context: Application,
    private val knowledgeService: KnowledgeService,
    private val settingsStore: SettingsStore,
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

    // ============ 导入状态（带阶段进度 + 失败重试）============
    private val _importProgress = MutableStateFlow(me.rerere.rikkahub.ui.components.settings.ImportProgressState())
    val importProgress: StateFlow<me.rerere.rikkahub.ui.components.settings.ImportProgressState> = _importProgress.asStateFlow()

    private val _failedItems = MutableStateFlow<List<me.rerere.rikkahub.ui.components.settings.FailedImportItem>>(emptyList())
    val failedItems: StateFlow<List<me.rerere.rikkahub.ui.components.settings.FailedImportItem>> = _failedItems.asStateFlow()

    fun dismissFailedItem(itemId: String) {
        _failedItems.value = _failedItems.value.filter { it.id != itemId }
    }

    fun clearAllFailedItems() {
        _failedItems.value = emptyList()
    }

    fun retryFailedItem(kbId: String, itemId: String) {
        viewModelScope.launch {
            val item = _failedItems.value.find { it.id == itemId } ?: return@launch
            // 从失败列表移除，加入导入队列
            _failedItems.value = _failedItems.value.filter { it.id != itemId }
            _importProgress.value = _importProgress.value.copy(
                totalFiles = _importProgress.value.totalFiles + 1,
            )
            // 重新导入（用原始数据重新处理）
            // 注意：这里简化处理——实际应该从缓存读取原文件内容
            _snackbar.value = "重试 ${item.fileName}，请重新选择文件"
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

    /** 批量导入多个文档（content, filePath, fileName）— 带细化进度和失败追踪 */
    fun addDocumentsConcurrent(
        kbId: String,
        files: List<Triple<String, String, String>>,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch {
            val total = files.size
            _importProgress.value = me.rerere.rikkahub.ui.components.settings.ImportProgressState(
                totalFiles = total,
                active = true,
            )

            val fileContents = mutableListOf<Triple<String, String, String>>()
            for ((i, (content, filePath, fileName)) in files.withIndex()) {
                _importProgress.value = _importProgress.value.copy(
                    currentFileName = fileName,
                    currentStage = me.rerere.rikkahub.ui.components.settings.ProcessingStage.READING,
                    currentFileProgress = 0.3f,
                )
                fileContents.add(Triple(content, filePath, fileName))
            }

            _importProgress.value = _importProgress.value.copy(
                currentStage = me.rerere.rikkahub.ui.components.settings.ProcessingStage.CHUNKING,
                currentFileProgress = 0.6f,
            )

            val result = knowledgeService.addDocumentsConcurrent(
                kbId = kbId,
                files = fileContents,
                onProgress = { completed, totalFiles, currentFile ->
                    _importProgress.value = _importProgress.value.copy(
                        completedFiles = completed,
                        currentFileName = if (currentFile.isNotEmpty()) currentFile else _importProgress.value.currentFileName,
                        currentStage = me.rerere.rikkahub.ui.components.settings.ProcessingStage.EMBEDDING,
                        currentFileProgress = completed.toFloat() / totalFiles.toFloat(),
                    )
                },
            )

            result.onSuccess { total ->
                _importProgress.value = me.rerere.rikkahub.ui.components.settings.ImportProgressState()
                _snackbar.value = context.getString(R.string.kb_added_chunks, total)
                selectKnowledgeBase(kbId)
                onDone()
            }.onFailure { e ->
                _importProgress.value = me.rerere.rikkahub.ui.components.settings.ImportProgressState()
                _snackbar.value = context.getString(R.string.kb_add_failed, e.message ?: "")
            }
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
    fun search(kbId: String, query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _isSearching.value = true
            _searchQuery.value = query
            _searchResults.value = knowledgeService.search(kbId, query)
            _isSearching.value = false
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }
}
