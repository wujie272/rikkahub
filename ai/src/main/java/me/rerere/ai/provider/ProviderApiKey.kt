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
    return this.normalizeProviderApiKeys()
}

/**
 * Split a raw string of API keys by whitespace, newline, or comma.
 */
fun splitProviderApiKeys(raw: String): List<String> {
    val regex = "[\\s,]+".toRegex()
    return raw
        .split(regex)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

/**
 * Normalize a list of ProviderApiKey: trim, deduplicate, filter blank.
 */
fun List<ProviderApiKey>.normalizedProviderApiKeys(): List<ProviderApiKey> {
    val seen = mutableSetOf<String>()
    return map { key ->
        key.copy(
            key = key.key.trim(),
            alias = key.alias.trim(),
        )
    }
        .filter { it.key.isNotBlank() }
        .filter { seen.add(it.key) }
}

// ── Convenience accessors (avoid repeated `when` in UI code) ──

fun ProviderSetting.getApiKeyValue(): String = when (this) {
    is ProviderSetting.OpenAI -> apiKey
    is ProviderSetting.Google -> apiKey
    is ProviderSetting.Claude -> apiKey
    else -> ""
}

fun ProviderSetting.isMultiKeyEnabled(): Boolean = when (this) {
    is ProviderSetting.OpenAI -> multiKeyEnabled
    is ProviderSetting.Google -> multiKeyEnabled
    is ProviderSetting.Claude -> multiKeyEnabled
    else -> false
}

fun ProviderSetting.getProviderApiKeys(): List<ProviderApiKey> = when (this) {
    is ProviderSetting.OpenAI -> apiKeys
    is ProviderSetting.Google -> apiKeys
    is ProviderSetting.Claude -> apiKeys
    else -> emptyList()
}

fun ProviderSetting.getProviderKeyStrategy(): ProviderKeyStrategy = when (this) {
    is ProviderSetting.OpenAI -> keyStrategy
    is ProviderSetting.Google -> keyStrategy
    is ProviderSetting.Claude -> keyStrategy
    else -> ProviderKeyStrategy.LRU
}

fun ProviderSetting.getLegacyApiKeyBackup(): String = when (this) {
    is ProviderSetting.OpenAI -> legacyApiKeyBackup
    is ProviderSetting.Google -> legacyApiKeyBackup
    is ProviderSetting.Claude -> legacyApiKeyBackup
    else -> ""
}

/**
 * Copy only the API-key-related fields of a ProviderSetting.
 */
fun ProviderSetting.copyWithApiKeyConfig(
    apiKey: String = getApiKeyValue(),
    multiKeyEnabled: Boolean = isMultiKeyEnabled(),
    apiKeys: List<ProviderApiKey> = getProviderApiKeys(),
    keyStrategy: ProviderKeyStrategy = getProviderKeyStrategy(),
    legacyApiKeyBackup: String = getLegacyApiKeyBackup(),
): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(
        apiKey = apiKey,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys,
        keyStrategy = keyStrategy,
        legacyApiKeyBackup = legacyApiKeyBackup,
    )
    is ProviderSetting.Google -> copy(
        apiKey = apiKey,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys,
        keyStrategy = keyStrategy,
        legacyApiKeyBackup = legacyApiKeyBackup,
    )
    is ProviderSetting.Claude -> copy(
        apiKey = apiKey,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys,
        keyStrategy = keyStrategy,
        legacyApiKeyBackup = legacyApiKeyBackup,
    )
    else -> this
}

/**
 * Enable multi-key from the current legacy apiKey value.
 * Imports existing keys into the structured format.
 */
fun ProviderSetting.enableMultiKeyFromCurrentValue(): ProviderSetting {
    val existingKeys = getProviderApiKeys().normalizedProviderApiKeys()
    val importedKeys = splitProviderApiKeys(getApiKeyValue()).map { value ->
        ProviderApiKey(key = value)
    }
    return copyWithApiKeyConfig(
        multiKeyEnabled = true,
        apiKeys = (existingKeys.ifEmpty { importedKeys }).normalizedProviderApiKeys(),
    )
}

/**
 * Sync enabled structured keys back to legacy apiKey field (comma-separated).
 * Returns the updated ProviderSetting.
 */
fun ProviderSetting.syncEnabledApiKeysToLegacy(): ProviderSetting {
    if (!isMultiKeyEnabled()) return this
    val normalizedKeys = getProviderApiKeys().normalizedProviderApiKeys()
    val enabledKeys = normalizedKeys
        .filter { it.enabled }
        .joinToString(",") { it.key }
    return copyWithApiKeyConfig(
        apiKey = enabledKeys,
        apiKeys = normalizedKeys,
    )
}

/**
 * Auto-migration: normalize apiKeys, auto-enable multiKey, preserve backup.
 * Call on load to ensure old data is converted.
 */
fun ProviderSetting.normalizeProviderApiKeys(): ProviderSetting {
    val rawApiKey = getApiKeyValue().trim()
    val existingKeys = getProviderApiKeys().normalizedProviderApiKeys()
    val legacyKeys = splitProviderApiKeys(rawApiKey)
    val shouldImportLegacy = existingKeys.isEmpty() && (isMultiKeyEnabled() || legacyKeys.size > 1)
    val normalizedKeys = if (shouldImportLegacy) {
        legacyKeys.map { ProviderApiKey(key = it) }
    } else {
        existingKeys
    }
    val shouldEnableMultiKey = isMultiKeyEnabled() || (shouldImportLegacy && normalizedKeys.size > 1)
    val backup = getLegacyApiKeyBackup().ifBlank {
        rawApiKey.takeIf { legacyKeys.size > 1 }.orEmpty()
    }
    return copyWithApiKeyConfig(
        apiKey = rawApiKey,
        multiKeyEnabled = shouldEnableMultiKey,
        apiKeys = normalizedKeys,
        legacyApiKeyBackup = backup,
    ).syncEnabledApiKeysToLegacy()
}

/**
 * Get active (enabled) API key values for request layer.
 * Returns emptyList() if multi-key is not enabled (caller falls back to apiKey).
 */
fun ProviderSetting.activeApiKeyValuesForRequest(): List<String> {
    if (!isMultiKeyEnabled()) return emptyList()
    return getProviderApiKeys()
        .normalizedProviderApiKeys()
        .filter { it.enabled }
        .map { it.key }
}

/**
 * Create a copy with a single API key (for testing individual keys).
 */
fun ProviderSetting.withSingleApiKeyForRequest(apiKey: String): ProviderSetting {
    return copyWithApiKeyConfig(
        apiKey = apiKey.trim(),
        multiKeyEnabled = false,
        apiKeys = emptyList(),
    )
}
