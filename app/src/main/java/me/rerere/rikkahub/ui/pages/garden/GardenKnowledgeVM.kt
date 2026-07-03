package me.rerere.rikkahub.ui.pages.garden

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.garden.GardenIndexService
import me.rerere.rikkahub.data.garden.GardenRepository
import me.rerere.rikkahub.data.garden.GardenSearchService

class GardenKnowledgeVM(
    private val gardenRepository: GardenRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    data class UiState(
        val isReady: Boolean = false,
        val totalChunks: Int = 0,
        val totalFiles: Int = 0,
        val folderCount: Int = 0,
        val lastUpdated: Long = 0,
        val isIndexing: Boolean = false,
        val indexProgress: GardenIndexService.IndexProgress? = null,
        val searchQuery: String = "",
        val searchResults: List<GardenSearchService.SearchResult> = emptyList(),
        val isSearching: Boolean = false,
        val folders: List<String> = emptyList(),
        val selectedFolder: String? = null,
        val error: String? = null,
        val vaultPath: String = "",
        val ignoreFolders: String = "",
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
                val stats = gardenRepository.getStats()
                val folders = gardenRepository.getFolders()
                _uiState.value = _uiState.value.copy(
                    isReady = gardenRepository.isReady(),
                    totalChunks = stats.totalChunks,
                    totalFiles = stats.totalFiles,
                    folderCount = stats.folderCount,
                    lastUpdated = stats.lastUpdated,
                    folders = folders,
                    vaultPath = settings.gardenVaultPath,
                    ignoreFolders = settings.gardenIgnoreFolders,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun updateVaultPath(path: String) {
        _uiState.value = _uiState.value.copy(vaultPath = path)
        viewModelScope.launch {
            settingsStore.update { it.copy(gardenVaultPath = path) }
        }
    }

    fun updateIgnoreFolders(ignore: String) {
        _uiState.value = _uiState.value.copy(ignoreFolders = ignore)
        viewModelScope.launch {
            settingsStore.update { it.copy(gardenIgnoreFolders = ignore) }
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
            gardenRepository.indexService.index(
                vaultPath = vaultPath,
                ignoreFolders = _uiState.value.ignoreFolders,
                progressCallback = { progress ->
                    _uiState.value = _uiState.value.copy(
                        isIndexing = progress.isRunning,
                        indexProgress = progress,
                    )
                },
            )
            refresh()
            _uiState.value = _uiState.value.copy(isIndexing = false)
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

        _uiState.value = _uiState.value.copy(searchQuery = query, isSearching = true)

        viewModelScope.launch {
            try {
                val results = gardenRepository.searchService.search(
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

    fun selectFolder(folder: String?) {
        _uiState.value = _uiState.value.copy(selectedFolder = folder)
        if (_uiState.value.searchQuery.isNotBlank()) {
            search(_uiState.value.searchQuery)
        }
    }
}
