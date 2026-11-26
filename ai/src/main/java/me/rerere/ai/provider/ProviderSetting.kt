package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid
import me.rerere.ai.provider.ProviderApiKey
import me.rerere.ai.provider.ProviderKeyStrategy
import me.rerere.ai.provider.ProviderProxy

/** 账号登录得到的 OAuth 凭据 */
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
data class OAuthCredential(
    val accessToken: String,
    val refreshToken: String? = null,
    // access token 过期时间（毫秒时间戳），0 表示未知
    val expiresAt: Long = 0L,
    // 厂商侧的账号/工作区 ID，ChatGPT 请求需要带上
    val accountId: String? = null,
    // 以下仅用于界面展示
    val email: String? = null,
    val plan: String? = null,
) {
    override fun toString(): String =
        "OAuthCredential(accessToken=${accessToken.masked()}, refreshToken=${refreshToken?.masked()}, " +
            "expiresAt=$expiresAt, accountId=$accountId, email=$email, plan=$plan)"

    private fun String.masked(): String = if (length <= 8) "***" else "${take(4)}***${takeLast(4)}"
}

@Serializable
enum class ClaudePromptCacheTtl(val apiValue: String?) {
    @SerialName("5m")
    FIVE_MINUTES(null),

    @SerialName("1h")
    ONE_HOUR("1h")
}

/**
 * 是否支持模型内置联网搜索（[BuiltInTools.Search]）。
 *
 * OpenAI 兼容提供商只有 Responses API 有 `web_search` 工具，Chat Completions 没有。
 */
val ProviderSetting.supportsBuiltInSearch: Boolean
    get() = when (this) {
        is ProviderSetting.OpenAI -> useResponseApi
        is ProviderSetting.Google -> true
        is ProviderSetting.Claude -> true
        is ProviderSetting.Account -> true
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
    abstract val customHeaders: List<CustomHeader>

    abstract val builtIn: Boolean
    abstract val description:
        @Composable()
        () -> Unit
    abstract val shortDescription:
        @Composable()
        () -> Unit

    fun addModel(model: Model): ProviderSetting = copyProvider(models = models + model)

    fun editModel(model: Model): ProviderSetting =
        copyProvider(models = models.map { if (it.id == model.id) model.copy() else it })

    fun delModel(model: Model): ProviderSetting = copyProvider(models = models.filter { it.id != model.id })

    fun moveMove(from: Int, to: Int): ProviderSetting =
        copyProvider(models = models.toMutableList().apply { add(to, removeAt(from)) })

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
        customHeaders: List<CustomHeader> = this.customHeaders,
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
        override val quotaQuery: QuotaQuery = QuotaQuery(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        override var apiKey: String = "",
        override var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        var promptCaching: Boolean = true,
        var includeHistoryReasoning: Boolean = true,
        var responsesPath: String = "/responses",
        // 本地定制：OpenRouter 路由偏好
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
    ) : ApiKey() {
        override fun withQuotaQuery(query: QuotaQuery): ApiKey = copy(quotaQuery = query)

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription
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
        override val fallbackConfig: FallbackConfig = FallbackConfig(),
        override val multiKeyEnabled: Boolean = false,
        override val apiKeys: List<ProviderApiKey> = emptyList(),
        override val keyStrategy: ProviderKeyStrategy = ProviderKeyStrategy.ROUND_ROBIN,
        override val proxy: ProviderProxy = ProviderProxy.None,
        override val quotaQuery: QuotaQuery =
            QuotaQuery(headers = listOf(CustomHeader("x-goog-api-key", "{{apiKey}}"))),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        override var apiKey: String = "",
        override var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
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
        var privateKey: String = "", // only for vertex AI service account
        var serviceAccountEmail: String = "", // only for vertex AI service account
        var location: String = "us-central1", // only for vertex AI service account
        var projectId: String = "", // only for vertex AI service account
        var useInteractionsApi: Boolean = false, // vertex AI: service account + global/us/eu location only
    ) : ApiKey() {
        override fun withQuotaQuery(query: QuotaQuery): ApiKey = copy(quotaQuery = query)

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
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


        override val quotaQuery: QuotaQuery = QuotaQuery(headers = listOf(CustomHeader("x-api-key", "{{apiKey}}"))),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        override var apiKey: String = "",
        override var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = false,
        var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
    ) : ApiKey() {
        override fun withQuotaQuery(query: QuotaQuery): ApiKey = copy(quotaQuery = query)

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    /** 用 API Key 鉴权、地址可以自填的提供商 */
    @Serializable
    sealed class ApiKey : ProviderSetting() {
        abstract val apiKey: String
        abstract val baseUrl: String

        /** 用户自定义的额度查询 */
        abstract val quotaQuery: QuotaQuery

        abstract fun withQuotaQuery(query: QuotaQuery): ApiKey
    }

    /**
     * 绑定官方账号的提供商。
     *
     * 没有 baseUrl 和 apiKey：请求地址由具体类型写死，凭据只能通过账号登录获得，保证 token
     * 不会被发到第三方地址。凭据由 app 层的账号管理负责登录、刷新和写回，界面编辑不应改动它。
     */
    @Serializable
    sealed class Account : ProviderSetting() {
        /** 未登录时为 null */
        abstract val credential: OAuthCredential?

        abstract fun withCredential(credential: OAuthCredential?): Account
    }

    @Serializable
    @SerialName("chatgpt")
    data class ChatGPT(
        override val id: Uuid = Uuid.random(),
        override val enabled: Boolean = true,
        override val name: String = "ChatGPT",
        override val models: List<Model> = emptyList(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        override val credential: OAuthCredential? = null,
    ) : Account() {
        override fun withCredential(credential: OAuthCredential?): Account = copy(credential = credential)

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    @Serializable
    @SerialName("grok")
    data class Grok(
        override val id: Uuid = Uuid.random(),
        override val enabled: Boolean = true,
        override val name: String = "Grok",
        override val models: List<Model> = emptyList(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        override val credential: OAuthCredential? = null,
    ) : Account() {
        override fun withCredential(credential: OAuthCredential?): Account = copy(credential = credential)

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
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
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


