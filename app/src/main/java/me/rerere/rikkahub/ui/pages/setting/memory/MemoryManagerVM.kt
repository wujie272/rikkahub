package me.rerere.rikkahub.ui.pages.setting.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository

data class MemoryManagerUiState(
    val memories: List<AssistantMemory> = emptyList(),
    val query: String = "",
    val mismatchCount: Int = 0,
    val reindexProgress: Pair<Int, Int>? = null,
    val reindexing: Boolean = false,
)

class MemoryManagerVM(
    private val memoryRepository: MemoryRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MemoryManagerUiState())
    val uiState: StateFlow<MemoryManagerUiState> = _uiState.asStateFlow()

    init {
        loadMemories()
    }

    fun loadMemories() {
        viewModelScope.launch {
            val all = memoryRepository.getAllMemoriesSorted()
            val mismatch = memoryRepository.countModelMismatch()
            _uiState.value = _uiState.value.copy(
                memories = all,
                mismatchCount = mismatch,
            )
        }
    }

    fun setQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        viewModelScope.launch {
            val result = if (query.isBlank()) {
                memoryRepository.getAllMemoriesSorted()
            } else {
                memoryRepository.searchMemories(query)
            }
            _uiState.value = _uiState.value.copy(memories = result)
        }
    }

    fun deleteMemory(id: Int) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(id)
            loadMemories()
        }
    }

    fun updateContent(id: Int, content: String) {
        viewModelScope.launch {
            memoryRepository.updateContent(id, content)
            loadMemories()
        }
    }

    fun togglePin(id: Int, pinned: Boolean) {
        viewModelScope.launch {
            memoryRepository.togglePin(id, pinned)
            loadMemories()
        }
    }

    fun reindexAll() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(reindexing = true)
            memoryRepository.reindexAll { current, total ->
                _uiState.value = _uiState.value.copy(
                    reindexProgress = current to total,
                )
            }
            _uiState.value = _uiState.value.copy(
                reindexing = false,
                reindexProgress = null,
            )
            loadMemories()
        }
    }
}
