package me.rerere.rikkahub.data.ai.rag

import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import kotlin.uuid.Uuid

/**
 * 通过已有 Provider 体系调线上 embedding API。
 * 复用用户已配置的 API key，不额外花钱。
 */
class EmbeddingService(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
) {
    /**
     * 当前用户指定的 embedding 模型 ID。未配置时返回 null。
     */
    val currentModelId: Uuid?
        get() {
            val settings = settingsStore.settingsFlow.value
            val id = settings.embeddingModelId
            return if (settings.findModelById(id) != null) id else null
        }

    /**
     * 检查 embedding 模型是否已配置可用。
     */
    suspend fun isConfigured(): Boolean {
        val settings = settingsStore.settingsFlow.value
        return settings.findModelById(settings.embeddingModelId) != null
    }

    /**
     * 将单段文本转为 embedding 向量。
     * @return 向量列表，失败时返回空列表（调用方自行降级）
     */
    suspend fun embed(
        text: String,
        modelId: Uuid? = null,
    ): List<Float> {
        if (text.isBlank()) return emptyList()
        val result = embedBatch(listOf(text), modelId)
        return result.firstOrNull() ?: emptyList()
    }

    /**
     * 批量 embedding。
     */
    suspend fun embedBatch(
        texts: List<String>,
        modelId: Uuid? = null,
    ): List<List<Float>> {
        if (texts.isEmpty() || texts.all { it.isBlank() }) return emptyList()

        val settings = settingsStore.settingsFlow.value

        // 只用用户明确指定的模型，不做自动发现
        val effectiveModelId = modelId ?: settings.embeddingModelId
        val model = settings.findModelById(effectiveModelId) ?: return emptyList()
        val providerSetting = model.findProvider(settings.providers) ?: return emptyList()
        val provider = runCatching { providerManager.getProviderByType(providerSetting) }.getOrNull() ?: return emptyList()

        return try {
            val result = provider.generateEmbedding(
                providerSetting = providerSetting,
                params = EmbeddingGenerationParams(
                    model = model,
                    input = texts,
                ),
            )
            result.embeddings
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
