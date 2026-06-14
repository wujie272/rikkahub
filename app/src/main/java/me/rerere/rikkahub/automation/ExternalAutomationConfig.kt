package me.rerere.rikkahub.automation

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ExternalAutomationConfig(context: Context) {

    private val dataStore = context.applicationContext.externalAutomationDataStore
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class InvocationLog(
        val timestampMs: Long,
        val callerPackage: String,
        val action: String,
        val status: String,
        val requestId: String? = null,
    )

    val enabledFlow: Flow<Boolean> = dataStore.data.map { it[KEY_ENABLED] ?: false }

    val trustedPackagesFlow: Flow<Set<String>> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_TRUSTED_PACKAGES] ?: return@map emptySet()
        runCatching { json.decodeFromString<Set<String>>(raw) }.getOrDefault(emptySet())
    }

    val recentInvocationsFlow: Flow<List<InvocationLog>> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_RECENT_INVOCATIONS] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<InvocationLog>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun isEnabled(): Boolean = enabledFlow.first()
    suspend fun trustedPackages(): Set<String> = trustedPackagesFlow.first()

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun addTrustedPackage(pkg: String) {
        val cleaned = pkg.trim()
        if (cleaned.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_TRUSTED_PACKAGES]
                ?.let { runCatching { json.decodeFromString<Set<String>>(it) }.getOrDefault(emptySet()) }
                ?: emptySet()
            prefs[KEY_TRUSTED_PACKAGES] = json.encodeToString(current + cleaned)
        }
    }

    suspend fun removeTrustedPackage(pkg: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_TRUSTED_PACKAGES]
                ?.let { runCatching { json.decodeFromString<Set<String>>(it) }.getOrDefault(emptySet()) }
                ?: emptySet()
            prefs[KEY_TRUSTED_PACKAGES] = json.encodeToString(current - pkg)
        }
    }

    suspend fun logInvocation(entry: InvocationLog) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_RECENT_INVOCATIONS]
                ?.let { runCatching { json.decodeFromString<List<InvocationLog>>(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            val next = (current + entry).takeLast(LOG_CAP)
            prefs[KEY_RECENT_INVOCATIONS] = json.encodeToString(next)
        }
    }

    companion object {
        const val LOG_CAP = 20
        private val KEY_ENABLED = booleanPreferencesKey("external_automation_enabled")
        private val KEY_TRUSTED_PACKAGES = stringPreferencesKey("external_automation_trusted_packages")
        private val KEY_RECENT_INVOCATIONS = stringPreferencesKey("external_automation_recent_invocations")
    }
}

private val Context.externalAutomationDataStore by preferencesDataStore(name = "external_automation")
