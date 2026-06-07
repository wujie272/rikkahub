package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.util.ApiKeyConfig
import me.rerere.ai.util.KeyManagementConfig
import me.rerere.ai.util.activeKeys
import me.rerere.ai.util.parseLegacyApiKeys
import me.rerere.ai.util.serializeToLegacyApiKey
import kotlin.uuid.Uuid

@Serializable
data class BalanceOption(
    val enabled: Boolean = false,
    val apiPath: String = "/credits",
    val resultPath: String = "data.total_usage",
)

@Serializable
enum class ClaudePromptCacheTtl(val apiValue: String?) {
    @SerialName("5m")
    FIVE_MINUTES(null),

    @SerialName("1h")
    ONE_HOUR("1h")
}

@Serializable
sealed class ProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>
    abstract val balanceOption: BalanceOption
    abstract val apiKeys: List<ApiKeyConfig>
    abstract val keyManagement: KeyManagementConfig

    abstract val builtIn: Boolean
    abstract val description: @Composable() () -> Unit
    abstract val shortDescription: @Composable() () -> Unit

    abstract fun addModel(model: Model): ProviderSetting
    abstract fun editModel(model: Model): ProviderSetting
    abstract fun delModel(model: Model): ProviderSetting
    abstract fun moveMove(from: Int, to: Int): ProviderSetting
    abstract fun copyProvider(
        id: Uuid = this.id,
        enabled: Boolean = this.enabled,
        name: String = this.name,
        models: List<Model> = this.models,
        balanceOption: BalanceOption = this.balanceOption,
        apiKeys: List<ApiKeyConfig> = this.apiKeys,
        keyManagement: KeyManagementConfig = this.keyManagement,
        builtIn: Boolean = this.builtIn,
        description: @Composable (() -> Unit) = this.description,
        shortDescription: @Composable (() -> Unit) = this.shortDescription,
    ): ProviderSetting

    /**
     * 获取有效的 API Key 列表
     * 优先使用结构化 apiKeys，若为空则从 apiKey 字符串解析
     */
    fun getEffectiveApiKeys(): List<ApiKeyConfig> {
        return if (apiKeys.isNotEmpty()) {
            apiKeys.activeKeys()
        } else {
            parseLegacyApiKeys(getLegacyApiKey())
        }
    }

    /** 同步结构化 keys 到旧的 apiKey 字符串（向后兼容序列化） */
    abstract fun syncApiKeyString()

    /**
     * 从旧的 apiKey 字符串同步到结构化 apiKeys（反向同步）
     * 编辑旧字段时自动触发，保持双向兼容
     */
    fun syncApiKeysFromSource(): ProviderSetting {
        val raw = getLegacyApiKey()
        if (raw.isBlank()) return this
        val parsedKeys = parseLegacyApiKeys(raw)
        if (parsedKeys.isEmpty()) return this
        val existingMap = apiKeys.associateBy { it.id }
        val mergedKeys = parsedKeys.map { parsedKey ->
            existingMap[parsedKey.id]?.let { existing ->
                existing.copy(
                    key = parsedKey.key,
                    name = existing.name.ifBlank { parsedKey.name },
                )
            } ?: parsedKey
        }
        val updated = copyProvider(apiKeys = mergedKeys)
        updated.syncApiKeyString()
        return updated
    }

    /** 获取旧格式的 apiKey 字符串 */
    abstract fun getLegacyApiKey(): String

    /** 设置旧格式的 apiKey 字符串并自动解析 */
    abstract fun setLegacyApiKey(key: String)

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override var apiKeys: List<ApiKeyConfig> = emptyList(),
        override var keyManagement: KeyManagementConfig = KeyManagementConfig(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        var includeHistoryReasoning: Boolean = true,
    ) : ProviderSetting() {
        override fun getLegacyApiKey(): String = apiKey
        override fun setLegacyApiKey(key: String) { apiKey = key }
        override fun syncApiKeyString() {
            apiKey = if (apiKeys.isNotEmpty()) {
                serializeToLegacyApiKey(apiKeys)
            } else {
                ""
            }
        }

        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid, enabled: Boolean, name: String, models: List<Model>,
            balanceOption: BalanceOption, apiKeys: List<ApiKeyConfig>,
            keyManagement: KeyManagementConfig, builtIn: Boolean,
            description: @Composable (() -> Unit), shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id, enabled = enabled, name = name, models = models,
                builtIn = builtIn, description = description,
                balanceOption = balanceOption, shortDescription = shortDescription,
                apiKeys = apiKeys, keyManagement = keyManagement,
            )
        }
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Google",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override var apiKeys: List<ApiKeyConfig> = emptyList(),
        override var keyManagement: KeyManagementConfig = KeyManagementConfig(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        var vertexAI: Boolean = false,
        var useServiceAccount: Boolean = false,
        var privateKey: String = "",
        var serviceAccountEmail: String = "",
        var location: String = "us-central1",
        var projectId: String = "",
    ) : ProviderSetting() {
        override fun getLegacyApiKey(): String = apiKey
        override fun setLegacyApiKey(key: String) { apiKey = key }
        override fun syncApiKeyString() {
            apiKey = if (apiKeys.isNotEmpty()) {
                serializeToLegacyApiKey(apiKeys)
            } else {
                ""
            }
        }

        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid, enabled: Boolean, name: String, models: List<Model>,
            balanceOption: BalanceOption, apiKeys: List<ApiKeyConfig>,
            keyManagement: KeyManagementConfig, builtIn: Boolean,
            description: @Composable (() -> Unit), shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id, enabled = enabled, name = name, models = models,
                builtIn = builtIn, description = description,
                balanceOption = balanceOption, shortDescription = shortDescription,
                apiKeys = apiKeys, keyManagement = keyManagement,
            )
        }
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Claude",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override var apiKeys: List<ApiKeyConfig> = emptyList(),
        override var keyManagement: KeyManagementConfig = KeyManagementConfig(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = false,
        var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
    ) : ProviderSetting() {
        override fun getLegacyApiKey(): String = apiKey
        override fun setLegacyApiKey(key: String) { apiKey = key }
        override fun syncApiKeyString() {
            apiKey = if (apiKeys.isNotEmpty()) {
                serializeToLegacyApiKey(apiKeys)
            } else {
                ""
            }
        }

        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid, enabled: Boolean, name: String, models: List<Model>,
            balanceOption: BalanceOption, apiKeys: List<ApiKeyConfig>,
            keyManagement: KeyManagementConfig, builtIn: Boolean,
            description: @Composable (() -> Unit), shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id, enabled = enabled, name = name, models = models,
                balanceOption = balanceOption, builtIn = builtIn,
                description = description, shortDescription = shortDescription,
                apiKeys = apiKeys, keyManagement = keyManagement,
            )
        }
    }

    companion object {
        val Types by lazy {
            listOf(OpenAI::class, Google::class, Claude::class)
        }
    }
}
