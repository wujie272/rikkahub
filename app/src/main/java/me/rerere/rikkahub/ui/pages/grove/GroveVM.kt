package me.rerere.rikkahub.ui.pages.grove

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.grove.GroveIndexService
import me.rerere.rikkahub.data.grove.GroveRepository
import me.rerere.rikkahub.data.grove.GroveSearchService
import me.rerere.rikkahub.data.grove.FolderStat

class GroveVM(
    private val groveRepository: GroveRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    data class UiState(
        val isReady: Boolean = false,
        val totalChunks: Int = 0,
        val totalFiles: Int = 0,
        val folderCount: Int = 0,
        val lastUpdated: Long = 0,
        val isIndexing: Boolean = false,
        val indexProgress: GroveIndexService.IndexProgress? = null,
        val searchQuery: String = "",
        val searchResults: List<GroveSearchService.SearchResult> = emptyList(),
        val isSearching: Boolean = false,
        val hasEmbeddingModel: Boolean = false,
        val folders: List<String> = emptyList(),
        val selectedFolder: String? = null,
        val error: String? = null,
        val snackbar: String? = null,
        val vaultPath: String = "",
        val ignoreFolders: String = "",
        val ignoreExtensions: String = "",
        val folderFileCounts: List<FolderStat> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val settings = settingsStore.settingsFlow.value
                val stats = groveRepository.getStats()
                val folders = groveRepository.getFolders()
                _uiState.value = _uiState.value.copy(
                    isReady = groveRepository.isReady(),
                    totalChunks = stats.totalChunks,
                    totalFiles = stats.totalFiles,
                    folderCount = stats.folderCount,
                    lastUpdated = stats.lastUpdated,
                    folders = folders,
                    vaultPath = settings.groveVaultPath,
                    ignoreFolders = settings.groveIgnoreFolders,
                    ignoreExtensions = settings.groveIgnoreExtensions,
                    folderFileCounts = stats.folderFileCounts,
                    hasEmbeddingModel = settings.embeddingModelId.toString() != "00000000-0000-0000-0000-000000000000",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun updateVaultPath(path: String) {
        _uiState.value = _uiState.value.copy(vaultPath = path)
        viewModelScope.launch {
            settingsStore.update { it.copy(groveVaultPath = path) }
        }
    }

    fun updateIgnoreFolders(ignore: String) {
        _uiState.value = _uiState.value.copy(ignoreFolders = ignore)
        viewModelScope.launch {
            settingsStore.update { it.copy(groveIgnoreFolders = ignore) }
        }
    }

    fun updateIgnoreExtensions(extensions: String) {
        _uiState.value = _uiState.value.copy(ignoreExtensions = extensions)
        viewModelScope.launch {
            settingsStore.update { it.copy(groveIgnoreExtensions = extensions) }
        }
    }

    fun startIndex() {
        val vaultPath = _uiState.value.vaultPath
        if (vaultPath.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "请先设置笔记库路径")
            return
        }
        if (_uiState.value.isIndexing) return

        _uiState.value = _uiState.value.copy(isIndexing = true, error = null)

        viewModelScope.launch {
            groveRepository.indexService.index(
                vaultPath = vaultPath,
                ignoreFolders = _uiState.value.ignoreFolders,
                ignoreExtensions = _uiState.value.ignoreExtensions,
                progressCallback = { progress ->
                    _uiState.value = _uiState.value.copy(
                        isIndexing = progress.isRunning,
                        indexProgress = progress,
                    )
                },
            )
            refresh()
            val s = _uiState.value
            _uiState.value = _uiState.value.copy(
                isIndexing = false,
                snackbar = "索引完成: ${s.totalFiles} 文件, ${s.totalChunks} 分块",
            )
        }
    }

    fun search(query: String) {
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(
                searchQuery = "",
                searchResults = emptyList(),
                isSearching = false,
            )
            return
        }

        // 检查 embedding 模型是否配置
        if (!_uiState.value.hasEmbeddingModel) {
            _uiState.value = _uiState.value.copy(
                searchQuery = query,
                searchResults = emptyList(),
                isSearching = false,
                error = "未配置 embedding 模型，请在 设置 → 模型和服务 中选择一个 embedding 模型",
            )
            return
        }

        _uiState.value = _uiState.value.copy(searchQuery = query, isSearching = true)

        viewModelScope.launch {
            try {
                val results = groveRepository.searchService.search(
                    query = query,
                    limit = 10,
                    folderFilter = _uiState.value.selectedFolder,
                )
                _uiState.value = _uiState.value.copy(
                    searchResults = results,
                    isSearching = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "搜索失败: ${e.message}",
                    isSearching = false,
                )
            }
        }
    }

    fun clearSnackbar() {
        _uiState.value = _uiState.value.copy(snackbar = null)
    }

    fun selectFolder(folder: String?) {
        _uiState.value = _uiState.value.copy(selectedFolder = folder)
        if (_uiState.value.searchQuery.isNotBlank()) {
            search(_uiState.value.searchQuery)
        }
    }
}
