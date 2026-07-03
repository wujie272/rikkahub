package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory

/**
 * 记忆仓库，纯文本存储。
 * 记忆由 LLM 通过 memory_tool 管理，搜索走 SQL LIKE。
 */
class MemoryRepository(
    private val memoryDAO: MemoryDAO,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities -> entities.map { it.toModel() } }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId).map { it.toModel() }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities -> entities.map { it.toModel() } }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID).map { it.toModel() }
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        memoryDAO.updateMemory(old.copy(content = content))
        return old.copy(content = content).toModel()
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        val id = memoryDAO.insertMemory(
            MemoryEntity(
                assistantId = assistantId,
                content = content,
            )
        ).toInt()
        return AssistantMemory(
            id = id,
            content = content,
        )
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
    }

    suspend fun togglePin(id: Int, pinned: Boolean) {
        memoryDAO.updatePin(id, pinned)
    }

    suspend fun getAllMemoriesSorted(): List<AssistantMemory> {
        return memoryDAO.getAllMemoriesSorted().map { it.toModel() }
    }

    suspend fun searchMemories(query: String): List<AssistantMemory> {
        return memoryDAO.searchMemories(query).map { it.toModel() }
    }

    /// 语义搜索已移除，记忆存储为纯文本，搜索走 SQL LIKE。

    private fun MemoryEntity.toModel() = AssistantMemory(
        id = id,
        content = content,
        type = type,
        timestamp = createdAt,
        pinned = pinned,
    )
}
