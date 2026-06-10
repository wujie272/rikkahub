package me.rerere.rikkahub.data.key

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * API Key 管理器
 *
 * 使用普通 SharedPreferences 存储 Key 列表。
 * Key 以 JSON 序列化后存储，每个 Provider 独立一条记录。
 * ProviderSetting 不再直接持有 Key 明文，而是通过 apiKeyIds 引用 KeyManager 中的条目。
 */
class KeyManager(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "api_keys", Context.MODE_PRIVATE
    )

    // ── 基础 CRUD ──────────────────────────────────────────

    /** 获取某个 Provider 的所有 Key */
    fun getKeys(providerId: Uuid): List<ApiKeyEntry> {
        val raw = prefs.getString(providerId.toString(), null) ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 保存某个 Provider 的所有 Key */
    fun setKeys(providerId: Uuid, keys: List<ApiKeyEntry>) {
        prefs.edit()
            .putString(providerId.toString(), json.encodeToString(keys))
            .apply()
    }

    /** 添加一个 Key */
    fun addKey(providerId: Uuid, entry: ApiKeyEntry) {
        val keys = getKeys(providerId).toMutableList()
        keys.add(entry)
        setKeys(providerId, keys)
    }

    /** 更新一个 Key */
    fun updateKey(providerId: Uuid, updated: ApiKeyEntry) {
        val keys = getKeys(providerId).map {
            if (it.id == updated.id) updated else it
        }
        setKeys(providerId, keys)
    }

    /** 删除一个 Key */
    fun deleteKey(providerId: Uuid, keyId: Uuid) {
        val keys = getKeys(providerId).filter { it.id != keyId }
        setKeys(providerId, keys)
    }

    /** 获取某个 Key */
    fun getKey(providerId: Uuid, keyId: Uuid): ApiKeyEntry? {
        return getKeys(providerId).find { it.id == keyId }
    }

    // ── 运行时使用 ──────────────────────────────────────────

    /** 获取所有已启用的 Key 值列表（给 KeyRoulette 用） */
    fun resolveEnabledKeys(providerId: Uuid): List<Pair<Uuid, String>> {
        return getKeys(providerId)
            .filter { it.isEnabled }
            .map { it.id to it.keyValue }
    }

    /** 通过 ID 获取单个 Key 明文 */
    fun resolveKeyValue(providerId: Uuid, keyId: Uuid): String? {
        return getKey(providerId, keyId)?.keyValue
    }

    /** 记录 Key 使用 */
    fun recordUsage(providerId: Uuid, keyId: Uuid) {
        updateKey(providerId, getKey(providerId, keyId)?.copy(
            lastUsedAt = System.currentTimeMillis(),
            usageCount = getKey(providerId, keyId)?.usageCount?.plus(1) ?: 1
        ) ?: return)
    }

    /** 记录 Key 错误 */
    fun recordError(providerId: Uuid, keyId: Uuid, error: String) {
        updateKey(providerId, getKey(providerId, keyId)?.copy(
            lastErrorAt = System.currentTimeMillis(),
            lastErrorMessage = error
        ) ?: return)
    }

    // ── 统计 ──────────────────────────────────────────

    /** 统计概览 */
    fun getStats(providerId: Uuid): KeyStats {
        val keys = getKeys(providerId)
        return KeyStats(
            total = keys.size,
            enabled = keys.count { it.isEnabled },
            totalUsage = keys.sumOf { it.usageCount },
        )
    }

    data class KeyStats(
        val total: Int,
        val enabled: Int,
        val totalUsage: Long,
    )
}
