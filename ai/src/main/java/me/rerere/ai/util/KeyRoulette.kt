package me.rerere.ai.util

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "KeyRoulette"

data class KeyState(
    val key: String,
    val lastUsed: Long = 0,
    val cooldownUntil: Long = 0,
    val cooldownDurationMs: Long = 0,
    val consecutiveFailures: Int = 0,
    val totalRequests: Int = 0,
    val successfulRequests: Int = 0,
    val failedRequests: Int = 0,
    val disabled: Boolean = false,
) {
    val isCoolingDown: Boolean get() = !disabled && cooldownUntil > System.currentTimeMillis()
    val remainingCooldownMs: Long get() = (cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0)
    val cooldownProgress: Float get() {
        if (cooldownDurationMs <= 0) return 0f
        val elapsed = cooldownDurationMs - remainingCooldownMs
        return (elapsed.toFloat() / cooldownDurationMs).coerceIn(0f, 1f)
    }
}

interface KeyRoulette {
    fun next(keys: String, providerId: String = ""): String

    /**
     * 报告某个 key 请求失败，触发冷却
     * @param cooldownMs 冷却时长（毫秒），默认 60s
     */
    fun reportFailure(key: String, providerId: String, cooldownMs: Long = 60_000L)

    /**
     * 报告某个 key 请求成功，重置冷却状态
     */
    fun reportSuccess(key: String, providerId: String)

    /**
     * 获取指定 provider 的所有 key 状态
     */
    fun getKeyStates(providerId: String): List<KeyState>

    fun setKeyEnabled(key: String, providerId: String, enabled: Boolean)

    fun thawKey(key: String, providerId: String)

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * 状态追踪 + 冷却，持久化存储到 cacheDir/key_state_cache.json
         * 通过 providerId 区分同类型的多个 provider 实例，在 next() 调用时传入
         */
        fun tracked(context: Context): KeyRoulette = KeyStateTrackerImpl(context)
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex() // 空格换行和逗号

private fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private class DefaultKeyRoulette : KeyRoulette {
    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        return if (keyList.isNotEmpty()) {
            keyList.random()
        } else {
            keys
        }
    }

    override fun reportFailure(key: String, providerId: String, cooldownMs: Long) {}
    override fun reportSuccess(key: String, providerId: String) {}
    override fun getKeyStates(providerId: String): List<KeyState> = emptyList()
    override fun setKeyEnabled(key: String, providerId: String, enabled: Boolean) {}
    override fun thawKey(key: String, providerId: String) {}
}

private const val STATE_CACHE_FILE = "key_state_cache.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 天

// 全局文件锁，防止多个 provider 实例并发读写同一文件
private object StateFileLock

// 文件结构: Map<providerId, Map<apiKey, KeyEntry>>
private typealias StateCache = Map<String, Map<String, KeyEntry>>

@kotlinx.serialization.Serializable
private data class KeyEntry(
    val lastUsed: Long,
    val cooldownUntil: Long = 0,
    val cooldownDurationMs: Long = 0,
    val consecutiveFailures: Int = 0,
    val maxFailuresBeforeDisable: Int = 3,
    val totalRequests: Int = 0,
    val successfulRequests: Int = 0,
    val failedRequests: Int = 0,
    val disabled: Boolean = false,
)

private class KeyStateTrackerImpl(
    private val context: Context,
) : KeyRoulette {
    // 内存覆盖层：reportFailure/reportSuccess 只更新这里，不碰文件
    // 文件只持久化 lastUsed 和 key 列表
    private val memoryOverlay = mutableMapOf<String, MutableMap<String, KeyEntry>>()

    /** 合并文件层 + 内存层的 entry，内存层优先覆盖 */
    private fun mergedEntry(providerId: String, key: String, fileEntry: KeyEntry?): KeyEntry {
        val memEntry = memoryOverlay[providerId]?.get(key)
        if (memEntry == null) return fileEntry ?: KeyEntry(lastUsed = 0)
        val base = fileEntry ?: KeyEntry(lastUsed = 0)
        return base.copy(
            cooldownUntil = memEntry.cooldownUntil,
            cooldownDurationMs = memEntry.cooldownDurationMs,
            consecutiveFailures = memEntry.consecutiveFailures,
            totalRequests = memEntry.totalRequests,
            successfulRequests = memEntry.successfulRequests,
            failedRequests = memEntry.failedRequests,
            disabled = memEntry.disabled || (fileEntry?.disabled == true),
        )
    }

    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys
        if (keyList.size == 1) return keyList[0]

        synchronized(StateFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val providerCache = (allCache[providerId] ?: emptyMap()).toMutableMap()

            // 过滤：只保留在 key 列表中的条目
            providerCache.keys.retainAll(keyList)

            // 按 keyList 顺序找第一个不在冷却中且未禁用的 key（使用 mergedEntry 检查内存层）
            val selected = keyList.firstOrNull { key ->
                val entry = mergedEntry(providerId, key, providerCache[key])
                !entry.disabled && entry.cooldownUntil <= now
            }

            if (selected == null) {
                // 所有 key 都在冷却中，选最早结束冷却的（检查内存层）
                val earliestKey = keyList.minByOrNull { key ->
                    mergedEntry(providerId, key, providerCache[key]).cooldownUntil
                } ?: keyList.first()
                providerCache[earliestKey] = (providerCache[earliestKey] ?: KeyEntry(lastUsed = now)).copy(
                    lastUsed = now,
                )
                allCache[providerId] = providerCache
                saveCache(allCache)
                return earliestKey
            }

            providerCache[selected] = (providerCache[selected] ?: KeyEntry(lastUsed = now)).copy(
                lastUsed = now,
            )
            allCache[providerId] = providerCache

            // 清理其他 provider 的过期记录
            allCache.entries.removeIf { (id, cache) ->
                id != providerId && cache.values.all { now - it.lastUsed >= EXPIRE_DURATION_MS }
            }

            saveCache(allCache)
            return selected
        }
    }

    override fun reportFailure(key: String, providerId: String, cooldownMs: Long) {
        val now = System.currentTimeMillis()
        val providerMem = memoryOverlay.getOrPut(providerId) { mutableMapOf() }
        val existing = providerMem[key] ?: KeyEntry(lastUsed = now)
        val newFailures = existing.consecutiveFailures + 1

        // 冷却策略：前 2 次失败只计数不冷却，第 3 次开始逐步加重
        val actualCooldown = when {
            newFailures <= 2 -> 0L
            newFailures == 3 -> cooldownMs
            newFailures == 4 -> cooldownMs * 2
            else -> cooldownMs * 5
        }

        providerMem[key] = existing.copy(
            cooldownUntil = if (actualCooldown > 0) now + actualCooldown else 0L,
            cooldownDurationMs = actualCooldown,
            consecutiveFailures = newFailures,
            totalRequests = existing.totalRequests + 1,
            failedRequests = existing.failedRequests + 1,
        )

        if (actualCooldown > 0) {
            Log.w(TAG, "reportFailure: key=$key provider=$providerId failures=$newFailures cooled ${actualCooldown/1000}s (memory)")
        } else {
            Log.w(TAG, "reportFailure: key=$key provider=$providerId failures=$newFailures (tolerated, memory)")
        }
    }

    override fun reportSuccess(key: String, providerId: String) {
        val now = System.currentTimeMillis()
        val providerMem = memoryOverlay.getOrPut(providerId) { mutableMapOf() }
        val existing = providerMem[key] ?: KeyEntry(lastUsed = now)
        providerMem[key] = existing.copy(
            cooldownUntil = 0,
            consecutiveFailures = 0,
            totalRequests = existing.totalRequests + 1,
            successfulRequests = existing.successfulRequests + 1,
        )
    }

    override fun getKeyStates(providerId: String): List<KeyState> {
        synchronized(StateFileLock) {
            val allCache = loadCache()
            val providerCache = allCache[providerId] ?: emptyMap()
            val allKeys = (providerCache.keys + (memoryOverlay[providerId]?.keys ?: emptySet())).distinct()
            return allKeys.map { key ->
                val merged = mergedEntry(providerId, key, providerCache[key])
                KeyState(
                    key = key,
                    lastUsed = merged.lastUsed,
                    cooldownUntil = merged.cooldownUntil,
                    cooldownDurationMs = merged.cooldownDurationMs,
                    consecutiveFailures = merged.consecutiveFailures,
                    totalRequests = merged.totalRequests,
                    successfulRequests = merged.successfulRequests,
                    failedRequests = merged.failedRequests,
                    disabled = merged.disabled,
                )
            }.sortedByDescending { it.lastUsed }
        }
    }

    override fun setKeyEnabled(key: String, providerId: String, enabled: Boolean) {
        synchronized(StateFileLock) {
            val allCache = loadCache().toMutableMap()
            val providerCache = (allCache[providerId] ?: emptyMap()).toMutableMap()
            val existing = providerCache[key] ?: KeyEntry(lastUsed = System.currentTimeMillis())
            providerCache[key] = existing.copy(disabled = !enabled)
            allCache[providerId] = providerCache
            saveCache(allCache)
        }
    }

    override fun thawKey(key: String, providerId: String) {
        val providerMem = memoryOverlay[providerId]
        providerMem?.remove(key)
        Log.w(TAG, "thawKey: key=$key provider=$providerId cooldown cleared (memory)")
    }

    private fun loadCache(): StateCache {
        return try {
            val file = File(context.cacheDir, STATE_CACHE_FILE)
            if (!file.exists()) return emptyMap()
            Json.decodeFromString(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "loadCache: failed to read key state cache, starting empty", e)
            emptyMap()
        }
    }

    private fun saveCache(cache: StateCache) {
        try {
            File(context.cacheDir, STATE_CACHE_FILE).writeText(Json.encodeToString(cache))
        } catch (e: Exception) {
            Log.w(TAG, "saveCache: failed to persist key state cache", e)
        }
    }
}

/** 脱敏显示 API Key：sk-xxx...xxx */
private fun String.maskApiKey(): String {
    return when {
        length <= 8 -> this
        else -> substring(0, 4) + "..." + substring(length - 4)
    }
}
