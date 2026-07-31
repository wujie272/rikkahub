package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid
import me.rerere.ai.provider.ProviderApiKey
import me.rerere.ai.provider.ProviderKeyStrategy
import me.rerere.ai.provider.ProviderProxy

@Serializable
data class BalanceOption(
    val enabled: Boolean = false,
    val apiPath: String = "/credits",
    val resultPath: String = "data.total_usage",
)

@Serializable
data class FallbackConfig(
    val enabled: Boolean = false,
    val cooldownSeconds: Int = 60,
    val maxRetries: Int = 3,
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
    abstract val fallbackConfig: FallbackConfig
    abstract val multiKeyEnabled: Boolean
    abstract val apiKeys: List<ProviderApiKey>
    abstract val keyStrategy: ProviderKeyStrategy
    abstract val proxy: ProviderProxy

    /** 此 Provider 是否需要独立的 API Key 配置页（本地/设备端 Provider 不需要） */
    open val hasKeyPage: Boolean get() = true
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
        fallbackConfig: FallbackConfig = this.fallbackConfig,
        multiKeyEnabled: Boolean = this.multiKeyEnabled,
        apiKeys: List<ProviderApiKey> = this.apiKeys,
        keyStrategy: ProviderKeyStrategy = this.keyStrategy,
        proxy: ProviderProxy = this.proxy,
        builtIn: Boolean = this.builtIn,
        description: @Composable (() -> Unit) = this.description,
        shortDescription: @Composable (() -> Unit) = this.shortDescription,
    ): ProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val fallbackConfig: FallbackConfig = FallbackConfig(),
        override val multiKeyEnabled: Boolean = false,
        override val apiKeys: List<ProviderApiKey> = emptyList(),
        override val keyStrategy: ProviderKeyStrategy = ProviderKeyStrategy.ROUND_ROBIN,
        override val proxy: ProviderProxy = ProviderProxy.None,
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        var promptCaching: Boolean = true,
        var includeHistoryReasoning: Boolean = true,
        var routing: OpenRouterRouting = OpenRouterRouting(),
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)
        override fun editModel(model: Model): ProviderSetting = copy(models = models.map { if (it.id == model.id) model.copy() else it })
        override fun delModel(model: Model): ProviderSetting = copy(models = models.filter { it.id != model.id })
        override fun moveMove(from: Int, to: Int): ProviderSetting = copy(models = models.toMutableList().apply { add(to, removeAt(from)) })
        override fun copyProvider(
            id: Uuid, enabled: Boolean, name: String, models: List<Model>,
            balanceOption: BalanceOption, fallbackConfig: FallbackConfig,
            multiKeyEnabled: Boolean, apiKeys: List<ProviderApiKey>, keyStrategy: ProviderKeyStrategy,
            proxy: ProviderProxy, builtIn: Boolean,
            description: @Composable (() -> Unit), shortDescription: @Composable (() -> Unit),
        ): ProviderSetting = copy(
            id = id, enabled = enabled, name = name, models = models,
            balanceOption = balanceOption, fallbackConfig = fallbackConfig,
            multiKeyEnabled = multiKeyEnabled, apiKeys = apiKeys, keyStrategy = keyStrategy,
            proxy = proxy, builtIn = builtIn, description = description, shortDescription = shortDescription,
        )
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Google",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val fallbackConfig: FallbackConfig = FallbackConfig(),
        override val multiKeyEnabled: Boolean = false,
        override val apiKeys: List<ProviderApiKey> = emptyList(),
        override val keyStrategy: ProviderKeyStrategy = ProviderKeyStrategy.ROUND_ROBIN,
        override val proxy: ProviderProxy = ProviderProxy.None,
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
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)
        override fun editModel(model: Model): ProviderSetting = copy(models = models.map { if (it.id == model.id) model.copy() else it })
        override fun delModel(model: Model): ProviderSetting = copy(models = models.filter { it.id != model.id })
        override fun moveMove(from: Int, to: Int): ProviderSetting = copy(models = models.toMutableList().apply { add(to, removeAt(from)) })
        override fun copyProvider(
            id: Uuid, enabled: Boolean, name: String, models: List<Model>,
            balanceOption: BalanceOption, fallbackConfig: FallbackConfig,
            multiKeyEnabled: Boolean, apiKeys: List<ProviderApiKey>, keyStrategy: ProviderKeyStrategy,
            proxy: ProviderProxy, builtIn: Boolean,
            description: @Composable (() -> Unit), shortDescription: @Composable (() -> Unit),
        ): ProviderSetting = copy(
            id = id, enabled = enabled, name = name, models = models,
            balanceOption = balanceOption, fallbackConfig = fallbackConfig,
            multiKeyEnabled = multiKeyEnabled, apiKeys = apiKeys, keyStrategy = keyStrategy,
            proxy = proxy, builtIn = builtIn, description = description, shortDescription = shortDescription,
        )
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Claude",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val fallbackConfig: FallbackConfig = FallbackConfig(),
        override val multiKeyEnabled: Boolean = false,
        override val apiKeys: List<ProviderApiKey> = emptyList(),
        override val keyStrategy: ProviderKeyStrategy = ProviderKeyStrategy.ROUND_ROBIN,
        override val proxy: ProviderProxy = ProviderProxy.None,
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = true,
        var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)
        override fun editModel(model: Model): ProviderSetting = copy(models = models.map { if (it.id == model.id) model.copy() else it })
        override fun delModel(model: Model): ProviderSetting = copy(models = models.filter { it.id != model.id })
        override fun moveMove(from: Int, to: Int): ProviderSetting = copy(models = models.toMutableList().apply { add(to, removeAt(from)) })
        override fun copyProvider(
            id: Uuid, enabled: Boolean, name: String, models: List<Model>,
            balanceOption: BalanceOption, fallbackConfig: FallbackConfig,
            multiKeyEnabled: Boolean, apiKeys: List<ProviderApiKey>, keyStrategy: ProviderKeyStrategy,
            proxy: ProviderProxy, builtIn: Boolean,
            description: @Composable (() -> Unit), shortDescription: @Composable (() -> Unit),
        ): ProviderSetting = copy(
            id = id, enabled = enabled, name = name, models = models,
            balanceOption = balanceOption, fallbackConfig = fallbackConfig,
            multiKeyEnabled = multiKeyEnabled, apiKeys = apiKeys, keyStrategy = keyStrategy,
            proxy = proxy, builtIn = builtIn, description = description, shortDescription = shortDescription,
        )
    }



    @Serializable
    @SerialName("codex")
    data class Codex(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = false,
        override var name: String = "Codex",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val fallbackConfig: FallbackConfig = FallbackConfig(),
        override val multiKeyEnabled: Boolean = false,
        override val apiKeys: List<ProviderApiKey> = emptyList(),
        override val keyStrategy: ProviderKeyStrategy = ProviderKeyStrategy.ROUND_ROBIN,
        override val proxy: ProviderProxy = ProviderProxy.None,
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)
        override fun editModel(model: Model): ProviderSetting = copy(models = models.map { if (it.id == model.id) model.copy() else it })
        override fun delModel(model: Model): ProviderSetting = copy(models = models.filter { it.id != model.id })
        override fun moveMove(from: Int, to: Int): ProviderSetting = copy(models = models.toMutableList().apply { add(to, removeAt(from)) })
        override fun copyProvider(
            id: Uuid, enabled: Boolean, name: String, models: List<Model>,
            balanceOption: BalanceOption, fallbackConfig: FallbackConfig,
            multiKeyEnabled: Boolean, apiKeys: List<ProviderApiKey>, keyStrategy: ProviderKeyStrategy,
            proxy: ProviderProxy, builtIn: Boolean,
            description: @Composable (() -> Unit), shortDescription: @Composable (() -> Unit),
        ): ProviderSetting = copy(
            id = id, enabled = enabled, name = name, models = models,
            balanceOption = balanceOption, fallbackConfig = fallbackConfig,
            multiKeyEnabled = multiKeyEnabled, apiKeys = apiKeys, keyStrategy = keyStrategy,
            proxy = proxy, builtIn = builtIn, description = description, shortDescription = shortDescription,
        )
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Google::class,
                Claude::class,
            )
        }
    }
}


