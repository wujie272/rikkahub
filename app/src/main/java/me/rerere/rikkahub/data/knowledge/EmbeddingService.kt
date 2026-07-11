package me.rerere.rikkahub.data.knowledge

import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ProviderManager
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
) {
    suspend fun embed(text: String, modelId: String): List<Float> {
        if (text.isBlank()) return emptyList()
        val results = embedBatch(listOf(text), modelId)
        return results.firstOrNull() ?: emptyList()
    }

    suspend fun embedBatch(texts: List<String>, modelId: String): List<List<Float>> {
        if (texts.isEmpty() || texts.all { it.isBlank() }) return emptyList()
        val settings = settingsStore.settingsFlow.value
        val modelUuid = try { Uuid.parse(modelId) } catch (_: Exception) { return emptyList() }
        val model = settings.findModelById(modelUuid) ?: return emptyList()
        val providerSetting = model.findProvider(settings.providers) ?: return emptyList()
        val provider = providerManager.getProviderByType(providerSetting)
        val result = provider.generateEmbedding(
            providerSetting = providerSetting,
            params = EmbeddingGenerationParams(
                model = model,
                input = texts,
            ),
        )
        return result.embeddings
    }

    suspend fun isConfigured(modelId: String): Boolean {
        if (modelId.isBlank()) return false
        return try {
            val settings = settingsStore.settingsFlow.value
            settings.findModelById(Uuid.parse(modelId)) != null
        } catch (_: Exception) { false }
    }
}
