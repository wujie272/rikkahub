package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.ai.rag.VectorEngine
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import kotlin.uuid.Uuid

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val embeddingService: EmbeddingService? = null,
    private val appScope: CoroutineScope? = null,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        const val MODEL_VERSION_UNSET = "00000000-0000-0000-0000-000000000000"
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

    /**
     * 当前 embedding 模型的版本标识（模型 ID hex）。
     * 用户切换模型后此值变化，用于检测向量空间不一致。
     */
    fun currentModelVersion(): String {
        val modelId = embeddingService?.currentModelId ?: return MODEL_VERSION_UNSET
        return modelId.toString()
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val embedding = embeddingService?.embed(content)
        val modelVersion = currentModelVersion()
        memoryDAO.updateEmbedding(
            id = id,
            embedding = embedding?.let { VectorEngine.floatsToJson(it) },
            modelId = if (embedding != null) modelVersion else null,
        )
        return old.copy(
            content = content,
            embedding = embedding?.let { VectorEngine.floatsToJson(it) },
            embeddingModelId = modelVersion,
        ).toModel()
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        val embedding = embeddingService?.embed(content)
        val modelVersion = currentModelVersion()
        val id = memoryDAO.insertMemory(
            MemoryEntity(
                assistantId = assistantId,
                content = content,
                embedding = embedding?.let { VectorEngine.floatsToJson(it) },
                embeddingModelId = if (embedding != null) modelVersion else null,
                type = 0, // CORE
            )
        ).toInt()
        return AssistantMemory(
            id = id,
            content = content,
            hasEmbedding = embedding != null,
            embeddingModelId = if (embedding != null) modelVersion else null,
        )
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
    }

    suspend fun togglePin(id: Int, pinned: Boolean) {
        memoryDAO.updatePin(id, pinned)
    }

    /**
     * 检测当前 embedding 模型是否与库中记忆的模型不一致。
     */
    suspend fun hasModelMismatch(): Boolean {
        val current = currentModelVersion()
        if (current == MODEL_VERSION_UNSET) return false
        val samples = memoryDAO.getMemoriesWithModelIds(limit = 1)
        if (samples.isEmpty()) return false
        return samples.first() != current
    }

    /**
     * 语义搜索：用 [query] 的 embedding 检索 top-K 条最相关的记忆。
     * 无 embedding model 或全部记忆无向量时降级为全量返回（上游 buildMemoryPrompt 兜底）。
     *
     * 检测到 embedding 模型变更时：
     * - 后台触发全量重算（不阻塞当前请求）
     * - 当前请求降级为全量注入（旧向量与新 query 向量空间不匹配，检索不准）
     */
    suspend fun searchSimilar(
        query: String,
        assistantId: String,
        limit: Int = 5,
        minScore: Float = 0.35f,
    ): List<AssistantMemory> {
        val queryVec = embeddingService?.embed(query)
        if (queryVec.isNullOrEmpty()) {
            return getMemoriesOfAssistant(assistantId)
        }

        val entities = memoryDAO.getMemoriesOfAssistant(assistantId)

        // 模型检测：如果当前模型与记忆的模型不一致 → 降级全量注入 + 后台回填
        if (appScope != null && hasModelVersionChanged(entities)) {
            appScope.launch {
                reindexAll()
            }
            return entities.map { it.toModel() }
        }

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

    /**
     * 检测 entities 的 embedding_model_id 是否与当前模型不一致。
     */
    private suspend fun hasModelVersionChanged(entities: List<MemoryEntity>): Boolean {
        val current = currentModelVersion()
        if (current == MODEL_VERSION_UNSET) return false
        val withEmbedding = entities.filter { it.embeddingModelId != null && it.embedding != null }
        if (withEmbedding.isEmpty()) return false
        // 只要有一条且模型不同 → 视为变更
        return withEmbedding.any { it.embeddingModelId != current && it.embeddingModelId != MODEL_VERSION_UNSET }
    }

    /**
     * 全量重算所有记忆的 embedding。无 embedding service 时直接跳过。
     * 用户在设置页切换模型后可手动调用，或由 searchSimilar 自动触发。
     */
    suspend fun reindexAll(progressCallback: ((current: Int, total: Int) -> Unit)? = null) {
        val allMemories = memoryDAO.getAllMemories()
        if (allMemories.isEmpty()) return

        val currentVer = currentModelVersion()
        if (currentVer == MODEL_VERSION_UNSET) return

        val texts = allMemories.map { it.content.take(2048) }
        val embeddings = embeddingService?.embedBatch(texts) ?: emptyList()

        allMemories.forEachIndexed { index, entity ->
            if (index < embeddings.size) {
                val vec = embeddings[index]
                if (vec.isNotEmpty()) {
                    memoryDAO.updateEmbedding(
                        id = entity.id,
                        embedding = VectorEngine.floatsToJson(vec),
                        modelId = currentVer,
                    )
                }
            }
            progressCallback?.invoke(index + 1, allMemories.size)
        }
    }

    suspend fun getAllMemoriesSorted(): List<AssistantMemory> {
        return memoryDAO.getAllMemoriesSorted().map { it.toModel() }
    }

    suspend fun searchMemories(query: String): List<AssistantMemory> {
        return memoryDAO.searchMemories(query).map { it.toModel() }
    }

    /**
     * 检查多少条带有 embedding 的记忆的模型版本与当前不一致。
     */
    suspend fun countModelMismatch(): Int {
        val current = currentModelVersion()
        if (current == MODEL_VERSION_UNSET) return 0
        return memoryDAO.getAllMemories().count {
            it.embedding != null && it.embeddingModelId != null &&
                it.embeddingModelId != current && it.embeddingModelId != MODEL_VERSION_UNSET
        }
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
