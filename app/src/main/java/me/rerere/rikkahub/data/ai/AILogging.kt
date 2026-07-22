package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.requestlog.AIRequestLogManager
import me.rerere.rikkahub.data.datastore.AiLogLevel
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage

/**
 * AI 日志级别控制 + 轻量内存日志（供 DeveloperPage 调试用）
 *
 * 完整的请求/响应日志（含 body、URL、耗时等）通过 [AIRequestLogManager] 写入 Room 数据库，
 * 不占用内存。这里只保留最近 32 条轻量日志供 DeveloperPage 实时查看 AI 调用概况。
 */
sealed class AILogging {
    data class Generation(
        val params: TextGenerationParams,
        val messages: List<UIMessage>,
        val providerSetting: ProviderSetting,
        val stream: Boolean,
        val source: AIRequestSource = AIRequestSource.OTHER,
    ) : AILogging()

    data class Embedding(
        val modelId: String,
        val modelDisplayName: String,
        val providerName: String,
        val inputCount: Int,
        val totalInputChars: Int,
        val embeddingCount: Int?,
        val dimensions: Int?,
        val durationMs: Long?,
        val source: AIRequestSource = AIRequestSource.MEMORY_EMBEDDING,
        val error: String? = null,
    ) : AILogging()
}

private const val MAX_LOGS = 32

class AILoggingManager(
    private val settingsStore: SettingsStore,
    private val requestLogManager: AIRequestLogManager,
    private val appScope: AppScope,
) {
    private val logs = MutableStateFlow<List<AILogging>>(emptyList())
    private val logLevel = MutableStateFlow(settingsStore.settingsFlow.value.aiLogLevel)

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .map { it.aiLogLevel }
                .collectLatest { logLevel.value = it }
        }
    }

    fun getLogs(): StateFlow<List<AILogging>> = logs

    fun getLogLevel(): StateFlow<AiLogLevel> = logLevel

    suspend fun setLogLevel(level: AiLogLevel) {
        settingsStore.update { it.copy(aiLogLevel = level) }
    }

    fun addLog(log: AILogging) {
        if (logLevel.value == AiLogLevel.OFF) return
        // 轻量内存日志，供 DeveloperPage 实时查看
        logs.value = logs.value + log
        if (logs.value.size > MAX_LOGS) {
            logs.value = logs.value.drop(1)
        }

        // 完整请求/响应日志由 GenerationHandler 在 API 调用后写入 Room 数据库（含真实响应体）
        // AILoggingManager 只负责轻量内存日志供 DeveloperPage 实时查看
    }

    fun logEmbedding(
        modelId: String,
        modelDisplayName: String,
        providerName: String,
        inputCount: Int,
        totalInputChars: Int,
        embeddingCount: Int?,
        dimensions: Int?,
        durationMs: Long?,
        source: AIRequestSource = AIRequestSource.MEMORY_EMBEDDING,
        error: String? = null,
    ) {
        addLog(
            AILogging.Embedding(
                modelId = modelId,
                modelDisplayName = modelDisplayName,
                providerName = providerName,
                inputCount = inputCount,
                totalInputChars = totalInputChars,
                embeddingCount = embeddingCount,
                dimensions = dimensions,
                durationMs = durationMs,
                source = source,
                error = error,
            )
        )
    }

    fun clearLogs() {
        logs.value = emptyList()
    }

    suspend fun reclassifyRecentLogsIfNeeded() {
        requestLogManager.reclassifyRecentLogsIfNeeded()
    }
}
