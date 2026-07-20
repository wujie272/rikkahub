package me.rerere.rikkahub.data.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.rikkahub.utils.JsonInstant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.AiLogLevel
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage

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
    appScope: AppScope,
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
        logs.value = logs.value + log
        if (logs.value.size > MAX_LOGS) {
            logs.value = logs.value.drop(1)
        }

        // 同步写入公共 Logging 系统，让 LogPage 也能看到
        when (log) {
            is AILogging.Generation -> {
                val requestPreview = log.messages
                    .filter { it.role == me.rerere.ai.core.MessageRole.USER }
                    .lastOrNull()
                    ?.parts
                    ?.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                    ?.joinToString("\n") { it.text }
                    ?.truncatePreview()
                    .orEmpty()

                // 结构化参数 JSON
                val paramsJson = buildTextGenerationParamsJson(log.params, log.stream)

                Logging.logRequest(
                    LogEntry.RequestLog(
                        tag = "AI",
                        url = "",
                        method = "POST",
                        source = log.source.name,
                        providerName = log.providerSetting.name,
                        modelId = log.params.model.modelId,
                        modelDisplayName = log.params.model.displayName,
                        stream = log.stream,
                        requestBody = requestPreview,
                        responseText = requestPreview,
                        paramsJson = paramsJson,
                    )
                )
            }
            is AILogging.Embedding -> {
                val paramsJson = buildEmbeddingParamsJson(log)

                Logging.logRequest(
                    LogEntry.RequestLog(
                        tag = "AI",
                        url = "",
                        method = "POST",
                        source = log.source.name,
                        providerName = log.providerName,
                        modelId = log.modelId,
                        modelDisplayName = log.modelDisplayName,
                        requestBody = "${log.inputCount} inputs, ${log.totalInputChars} chars",
                        responseText = "${log.embeddingCount ?: 0} embeddings, ${log.dimensions ?: 0} dims",
                        paramsJson = paramsJson,
                        durationMs = log.durationMs,
                        error = log.error,
                    )
                )
            }
        }
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

    /**
     * 自动重分类：检查最近日志的 source 是否准确，修正已知误分类
     */
    suspend fun reclassifyRecentLogsIfNeeded() {
        // 检查公共 Logging 系统中的日志，修正来源
        val allLogs = Logging.getRequestLogs()
        allLogs.forEach { log ->
            if (log.source == AIRequestSource.OTHER.name && log.requestBody?.isNotBlank() == true) {
                // 可根据请求体特征判断实际来源
                // 这里留空，后续可扩展
            }
        }
    }
}

@Serializable
private data class TextGenerationParamsLog(
    val temperature: Float?,
    val topP: Float?,
    val maxTokens: Int?,
    val toolNames: List<String>,
    val customHeaders: List<CustomHeader>,
    val customBody: List<CustomBody>,
    val stream: Boolean,
)

@Serializable
private data class EmbeddingParamsLog(
    val inputCount: Int,
    val totalChars: Int,
    val embeddingCount: Int?,
    val dimensions: Int?,
)

private val SENSITIVE_KEY_NAMES = setOf(
    "authorization", "x-api-key", "api-key", "apikey", "api_key",
    "token", "access_token", "refresh_token", "secret", "password",
    "private_key", "client_secret",
)

private const val MASKED_VALUE = "********"

private fun String.isSensitiveName(): Boolean {
    return trim().lowercase() in SENSITIVE_KEY_NAMES
}

private fun buildTextGenerationParamsJson(params: TextGenerationParams, stream: Boolean = false): String {
    val safeHeaders = params.customHeaders.map { header ->
        if (header.name.isSensitiveName()) header.copy(value = MASKED_VALUE) else header
    }
    val safeBodies = params.customBody.map { body ->
        if (body.key.isSensitiveName()) {
            body.copy(value = JsonPrimitive(MASKED_VALUE))
        } else body
    }

    val safe = TextGenerationParamsLog(
        temperature = params.temperature,
        topP = params.topP,
        maxTokens = params.maxTokens,
        toolNames = params.tools.map { it.name },
        customHeaders = safeHeaders,
        customBody = safeBodies,
        stream = stream,
    )
    return JsonInstant.encodeToString(TextGenerationParamsLog.serializer(), safe)
}

private fun buildEmbeddingParamsJson(log: AILogging.Embedding): String {
    val safe = EmbeddingParamsLog(
        inputCount = log.inputCount,
        totalChars = log.totalInputChars,
        embeddingCount = log.embeddingCount,
        dimensions = log.dimensions,
    )
    return JsonInstant.encodeToString(EmbeddingParamsLog.serializer(), safe)
}
