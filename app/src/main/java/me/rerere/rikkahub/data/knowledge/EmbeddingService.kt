package me.rerere.rikkahub.data.knowledge

import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.ai.requestlog.AIRequestLogManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import kotlin.uuid.Uuid

/**
 * 嵌入向量服务
 * 复用 RikkaHub 已有的 Provider 体系调 embedding API
 */
class EmbeddingService(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
    private val requestLogManager: AIRequestLogManager,
) {
    companion object {
        /**
         * 单次 embedding API 调用的最大输入条数。
         * 超过此数量时自动拆分为多个小批次请求。
         * 设 500 是保守值，兼容 Qwen/OpenAI 等主流 embedding 模型。
         */
        private const val BATCH_SIZE = 500
        /** 批次间延迟（毫秒），避免 API 限流 */
        private const val BATCH_DELAY_MS = 200L
    }

    suspend fun embed(text: String, modelId: String, dimensions: Int? = null): List<Float> {
        if (text.isBlank()) return emptyList()
        val results = embedBatch(listOf(text), modelId, dimensions)
        return results.firstOrNull() ?: emptyList()
    }

    /**
     * 批量嵌入，支持自动拆分大批次。
     * 如果 texts 超过 BATCH_SIZE，自动拆分为多个小请求并合并结果。
     */
    suspend fun embedBatch(texts: List<String>, modelId: String, dimensions: Int? = null): List<List<Float>> {
        if (texts.isEmpty() || texts.all { it.isBlank() }) return emptyList()

        // 如果没超过批次大小，直接调
        if (texts.size <= BATCH_SIZE) {
            return embedBatchInternal(texts, modelId, dimensions)
        }

        // 拆分为多个小批次
        val allResults = mutableListOf<List<Float>>()
        val batches = texts.chunked(BATCH_SIZE)
        for ((index, batch) in batches.withIndex()) {
            // 批次间加延迟，避免 API 限流
            if (index > 0) {
                kotlinx.coroutines.delay(BATCH_DELAY_MS)
            }
            val batchResult = embedBatchInternal(batch, modelId, dimensions)
            allResults.addAll(batchResult)
        }
        return allResults
    }

    /**
     * 实际调 API 的单次批量嵌入，不拆分。
     */
    private suspend fun embedBatchInternal(texts: List<String>, modelId: String, dimensions: Int? = null): List<List<Float>> {
        if (texts.isEmpty() || texts.all { it.isBlank() }) return emptyList()
        val settings = settingsStore.settingsFlow.value
        val modelUuid = try { Uuid.parse(modelId) } catch (_: Exception) { return emptyList() }
        val model = settings.findModelById(modelUuid) ?: return emptyList()
        val providerSetting = model.findProvider(settings.providers) ?: return emptyList()
        val provider = providerManager.getProviderByType(providerSetting)

        val startAt = System.currentTimeMillis()
        var failure: Throwable? = null
        var embeddingResult: List<List<Float>> = emptyList()

        try {
            val result = provider.generateEmbedding(
                providerSetting = providerSetting,
                params = EmbeddingGenerationParams(
                    model = model,
                    input = texts,
                    dimensions = dimensions,
                ),
            )
            embeddingResult = result.embeddings
            return embeddingResult
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            requestLogManager.logEmbedding(
                source = AIRequestSource.MEMORY_EMBEDDING,
                providerSetting = providerSetting,
                model = model,
                inputs = texts,
                embeddingCount = embeddingResult.size.takeIf { it > 0 },
                dimensions = embeddingResult.firstOrNull()?.size,
                durationMs = System.currentTimeMillis() - startAt,
                error = failure,
            )
        }
    }

    suspend fun isConfigured(modelId: String): Boolean {
        if (modelId.isBlank()) return false
        return try {
            val settings = settingsStore.settingsFlow.value
            settings.findModelById(Uuid.parse(modelId)) != null
        } catch (_: Exception) { false }
    }
}
