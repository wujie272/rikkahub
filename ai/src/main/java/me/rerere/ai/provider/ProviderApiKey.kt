package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.util.KeyRoulette
import kotlin.uuid.Uuid

/**
 * Structured API Key entry with alias, enable/disable support.
 * Looks like LastChat's provider_api_keys table structure.
 */
@Serializable
data class ProviderApiKey(
    val id: String = Uuid.random().toString(),
    val key: String,
    val alias: String = "",
    val enabled: Boolean = true,
)

/**
 * Key picking strategy.
 * - LRU: RikkaHub native (least recently used, cooling support)
 * - RANDOM: LastChat style (random selection)
 * - ROUND_ROBIN: LastChat style (circular round-robin)
 */
@Serializable
enum class ProviderKeyStrategy {
    @SerialName("lru") LRU,
    @SerialName("random") RANDOM,
    @SerialName("round_robin") ROUND_ROBIN,
}

/**
 * Proxy configuration for a provider.
 * Inspired by LastChat's ProviderProxy.
 */
@Serializable
sealed class ProviderProxy {
    @Serializable
    @SerialName("none")
    object None : ProviderProxy()

    @Serializable
    @SerialName("http")
    data class Http(
        val address: String,
        val port: Int,
        val username: String = "",
        val password: String = "",
    ) : ProviderProxy()
}

// ── Helpers ──────────────────────────────────────────────────────────────

/** Get the current single-key value from the concrete subtype. */
internal fun ProviderSetting.apiKeyValue(): String = when (this) {
    is ProviderSetting.OpenAI -> apiKey
    is ProviderSetting.Google -> apiKey
    is ProviderSetting.Claude -> apiKey
    else -> ""
}

/** Get the structured apiKeys list. */
internal fun ProviderSetting.apiKeysList(): List<ProviderApiKey> = when (this) {
    is ProviderSetting.OpenAI -> apiKeys
    is ProviderSetting.Google -> apiKeys
    is ProviderSetting.Claude -> apiKeys
    else -> emptyList()
}

/** Get the multiKey flag. */
internal fun ProviderSetting.multiKeyFlag(): Boolean = when (this) {
    is ProviderSetting.OpenAI -> multiKeyEnabled
    is ProviderSetting.Google -> multiKeyEnabled
    is ProviderSetting.Claude -> multiKeyEnabled
    else -> false
}

/** Get the key strategy. */
internal fun ProviderSetting.keyStrategyValue(): ProviderKeyStrategy = when (this) {
    is ProviderSetting.OpenAI -> keyStrategy
    is ProviderSetting.Google -> keyStrategy
    is ProviderSetting.Claude -> keyStrategy
    else -> ProviderKeyStrategy.LRU
}

// ── Unified update entry (like LastChat's copyWithApiKeyConfig) ──────────

/**
 * Create a copy of this [ProviderSetting] with key-related fields updated.
 * This is the single entry point for all Key configuration changes —
 * mirrors LastChat's `copyWithApiKeyConfig`.
 */
fun ProviderSetting.copyWithApiKeyConfig(
    apiKey: String = apiKeyValue(),
    multiKeyEnabled: Boolean = multiKeyFlag(),
    apiKeys: List<ProviderApiKey> = apiKeysList(),
    keyStrategy: ProviderKeyStrategy = keyStrategyValue(),
): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(
        apiKey = apiKey,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys,
        keyStrategy = keyStrategy,
    )
    is ProviderSetting.Google -> copy(
        apiKey = apiKey,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys,
        keyStrategy = keyStrategy,
    )
    is ProviderSetting.Claude -> copy(
        apiKey = apiKey,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys,
        keyStrategy = keyStrategy,
    )
    else -> this
}

// ── Sync enabled keys back to legacy apiKey ──────────────────────────────

/**
 * Sync enabled keys from structured [apiKeys] back to the legacy [apiKey]
 * field, so existing code (conversations, request handling) continues to work.
 *
 * Returns the updated [ProviderSetting]; no-op if multi-key is disabled.
 *
 * Mirrors LastChat's [syncEnabledApiKeysToLegacyField].
 */
fun ProviderSetting.syncEnabledApiKeysToLegacyField(): ProviderSetting {
    if (!multiKeyFlag()) return this

    val enabledKeys = apiKeysList()
        .filter { it.enabled }
        .joinToString("\n") { it.key }

    return copyWithApiKeyConfig(apiKey = enabledKeys)
}

// ── Active key values for request ────────────────────────────────────────

/**
 * Get active (enabled) API key values as a flat list of strings.
 * Used by the AI provider layer to feed into [KeyRoulette.next].
 */
fun ProviderSetting.activeApiKeyValuesForRequest(): List<String> {
    return apiKeysList()
        .filter { it.enabled }
        .map { it.key }
}

/**
 * Pick a key from the structured list using the configured strategy.
 */
fun ProviderSetting.pickApiKey(
    keyRoulette: KeyRoulette,
    providerId: String = this.id.toString(),
): String {
    val activeKeys = activeApiKeyValuesForRequest()
    if (activeKeys.isEmpty()) return apiKeyValue()
    return when (keyStrategyValue()) {
        ProviderKeyStrategy.LRU ->
            keyRoulette.next(activeKeys.joinToString("\n"), providerId)
        ProviderKeyStrategy.RANDOM -> activeKeys.random()
        ProviderKeyStrategy.ROUND_ROBIN ->
            keyRoulette.next(activeKeys.joinToString("\n"), providerId)
    }
}


