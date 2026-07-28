package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.util.KeyRoulette
import kotlin.uuid.Uuid
import kotlin.random.Random

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
    val priority: Int = 5,
)

/**
 * Key picking strategy (mirrors Kelivo/ApiKeyManager).
 * - ROUND_ROBIN: circular round-robin
 * - PRIORITY: sort by priority (1-10, smaller = higher priority)
 * - LEAST_USED: sort by total requests (ascending)
 * - RANDOM: random selection
 */
@Serializable
enum class ProviderKeyStrategy {
    @SerialName("round_robin") ROUND_ROBIN,
    @SerialName("priority") PRIORITY,
    @SerialName("least_used") LEAST_USED,
    @SerialName("random") RANDOM,
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
    else -> ProviderKeyStrategy.ROUND_ROBIN
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

/** Round-robin index tracking per provider (mirrors Kelivo). */
private val roundRobinIndices = mutableMapOf<String, Int>()

/**
 * Pick a key from the structured list using the configured strategy.
 * Mirrors Kelivo's [ApiKeyManager.selectForProvider].
 */
fun ProviderSetting.pickApiKey(
    keyRoulette: KeyRoulette,
    providerId: String = this.id.toString(),
): String {
    val activeKeys = activeApiKeyValuesForRequest()
    if (activeKeys.isEmpty()) return apiKeyValue()
    if (activeKeys.size == 1) return activeKeys[0]

    return when (keyStrategyValue()) {
        ProviderKeyStrategy.ROUND_ROBIN -> {
            val idx = roundRobinIndices.getOrDefault(providerId, 0) % activeKeys.size
            roundRobinIndices[providerId] = (idx + 1) % activeKeys.size
            activeKeys[idx]
        }
        ProviderKeyStrategy.PRIORITY -> {
            val sorted = apiKeysList()
                .filter { it.enabled }
                .sortedBy { it.priority }
            sorted.first().key
        }
        ProviderKeyStrategy.LEAST_USED -> {
            val states = keyRoulette.getKeyStates(providerId)
            val stateMap = states.associateBy { it.key }
            activeKeys.minByOrNull { key ->
                stateMap[key]?.totalRequests ?: 0
            } ?: activeKeys.first()
        }
        ProviderKeyStrategy.RANDOM -> {
            activeKeys[Random.nextInt(activeKeys.size)]
        }
    }
}


