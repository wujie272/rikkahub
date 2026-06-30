package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.ai.rag.VectorEngine
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val embeddingService: EmbeddingService? = null,
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
        val embedding = embeddingService?.embed(content)
        val newMemory = old.copy(
            content = content,
            embedding = embedding?.let { VectorEngine.floatsToJson(it) },
            embeddingModelId = embeddingService?.let { "openai" },
        )
        memoryDAO.updateMemory(newMemory)
        return newMemory.toModel()
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        val embedding = embeddingService?.embed(content)
        val id = memoryDAO.insertMemory(
            MemoryEntity(
                assistantId = assistantId,
                content = content,
                embedding = embedding?.let { VectorEngine.floatsToJson(it) },
                embeddingModelId = embedding?.let { "openai" },
                type = 0, // CORE
            )
        ).toInt()
        return AssistantMemory(
            id = id,
            content = content,
            hasEmbedding = embedding != null,
        )
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
    }

    /**
     * 语义搜索：用 [query] 的 embedding 检索 top-K 条最相关的记忆。
     * 无 embedding model 或全部记忆无向量时降级为全量返回（调用方兜底）。
     */
    suspend fun searchSimilar(
        query: String,
        assistantId: String,
        limit: Int = 5,
        minScore: Float = 0.35f,
    ): List<AssistantMemory> {
        val queryVec = embeddingService?.embed(query)
        if (queryVec.isNullOrEmpty()) {
            // 无 embedding 能力 → 全量返回（上游 buildMemoryPrompt 兜底）
            return getMemoriesOfAssistant(assistantId)
        }

        val entities = memoryDAO.getMemoriesOfAssistant(assistantId)
        val candidates = entities.map { e ->
            VectorEngine.MemCandidate(
                id = e.id,
                content = e.content,
                embedding = e.embedding?.let { VectorEngine.jsonToFloats(it) },
            )
        }

        val results = VectorEngine.searchTopK(queryVec, candidates, limit, minScore)
        val resultIds = results.map { it.id }.toSet()

        // 补充未匹配到但被 pinned 的记忆（保证重要记忆不丢失）
        val pinned = entities.filter { it.pinned && it.id !in resultIds }.take(2)

        return (results.map { r ->
            val entity = entities.firstOrNull { it.id == r.id }
            AssistantMemory(
                id = r.id,
                content = r.content,
                hasEmbedding = true,
                timestamp = entity?.createdAt ?: 0L,
                pinned = entity?.pinned ?: false,
            )
        } + pinned.map { it.toModel() })
            .sortedByDescending { it.pinned }
            .take(limit + 2)
    }

    private fun MemoryEntity.toModel() = AssistantMemory(
        id = id,
        content = content,
        type = type,
        hasEmbedding = embedding != null,
        embeddingModelId = embeddingModelId,
        timestamp = createdAt,
        pinned = pinned,
    )
}
