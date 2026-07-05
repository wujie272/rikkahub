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
    @SerialName("none")
    data object None : ProviderProxy()

    @SerialName("http")
    data class Http(
        val address: String,
        val port: Int,
        val username: String = "",
        val password: String = "",
    ) : ProviderProxy()
}

/**
 * Migrate old comma/newline-separated apiKey to the new structured format.
 */
fun ProviderSetting.normalizeProviderApiKeys(): List<ProviderApiKey> {
    val raw = when (this) {
        is ProviderSetting.OpenAI -> this.apiKey
        is ProviderSetting.Google -> this.apiKey
        is ProviderSetting.Claude -> this.apiKey
        else -> ""
    }
    return raw.split("\n")
        .flatMap { it.split(",") }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .map { ProviderApiKey(key = it) }
}

/**
 * Sync the enabled Keys back to the legacy `apiKey` field so old
 * code (conversations, request handling) continues to work.
 */
fun ProviderSetting.syncEnabledApiKeysToLegacyField(): String {
    return this.apiKeys
        .filter { it.enabled }
        .joinToString("\n")
}

/**
 * Get active (enabled) API key values as a flat list of strings.
 * Used by the AI provider layer to feed into KeyRoulette.next().
 */
fun ProviderSetting.activeApiKeyValuesForRequest(): List<String> {
    return this.apiKeys
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
    val activeKeys = this.activeApiKeyValuesForRequest()
    if (activeKeys.isEmpty()) return when (this) {
        is ProviderSetting.OpenAI -> this.apiKey
        is ProviderSetting.Google -> this.apiKey
        is ProviderSetting.Claude -> this.apiKey
        else -> ""
    }
    return when (this.keyStrategy) {
        ProviderKeyStrategy.LRU -> {
            keyRoulette.next(activeKeys.joinToString("\n"), providerId)
        }
        ProviderKeyStrategy.RANDOM -> activeKeys.random()
        ProviderKeyStrategy.ROUND_ROBIN -> {
            keyRoulette.next(activeKeys.joinToString("\n"), providerId)
        }
    }
}

/**
 * Migration wrapper: call on load to ensure old data is converted.
 */
fun ProviderSetting.prepareMultiKey(): ProviderSetting {
    if (this.multiKeyEnabled && this.apiKeys.isEmpty()) {
        // First-time migration: old format -> new structured
        return this.copyProvider(apiKeys = this.normalizeProviderApiKeys())
    }
    return this
}
