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
) {
    val isCoolingDown: Boolean get() = cooldownUntil > System.currentTimeMillis()
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

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * LRU 轮询 + 冷却，持久化存储到 cacheDir/lru_key_roulette.json
         * 通过 providerId 区分同类型的多个 provider 实例，在 next() 调用时传入
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)
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
}

private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 天

// 全局文件锁，防止多个 provider 实例并发读写同一文件
private object LruFileLock

// 文件结构: Map<providerId, Map<apiKey, KeyEntry>>
private typealias LruCache = Map<String, Map<String, KeyEntry>>

@kotlinx.serialization.Serializable
private data class KeyEntry(
    val lastUsed: Long,
    val cooldownUntil: Long = 0, // 冷却到什么时候（毫秒时间戳），0=未冷却
    val cooldownDurationMs: Long = 0, // 本次冷却总时长（毫秒），用于进度条
    val consecutiveFailures: Int = 0,
    val maxFailuresBeforeDisable: Int = 3, // 连续失败多少次后开始冷却
    val totalRequests: Int = 0,
    val successfulRequests: Int = 0,
    val failedRequests: Int = 0,
)

private class LruKeyRoulette(
    private val context: Context,
) : KeyRoulette {

    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys
        if (keyList.size == 1) return keyList[0]

        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val providerCache = (allCache[providerId] ?: emptyMap()).toMutableMap()

            // 过滤：只保留在 key 列表中的条目
            providerCache.keys.retainAll(keyList)

            // 按 keyList 顺序（优先级）找第一个不在冷却中的 key
            val selected = keyList.firstOrNull { key ->
                (providerCache[key]?.cooldownUntil ?: 0) <= now
            }

            if (selected == null) {
                // 所有 key 都在冷却中，选最早结束冷却的
                val earliestKey = providerCache.minByOrNull { it.value.cooldownUntil }?.key
                    ?: keyList.first()
                providerCache[earliestKey] = KeyEntry(
                    lastUsed = now,
                    cooldownUntil = providerCache[earliestKey]?.cooldownUntil ?: 0,
                )
                allCache[providerId] = providerCache
                saveCache(allCache)
                return earliestKey
            }

            providerCache[selected] = KeyEntry(lastUsed = now, cooldownUntil = 0)
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
        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val providerCache = (allCache[providerId] ?: emptyMap()).toMutableMap()

            val existing = providerCache[key] ?: KeyEntry(lastUsed = now, cooldownUntil = 0)
            val newFailures = existing.consecutiveFailures + 1

            // 冷却策略：前 2 次失败只计数不冷却，第 3 次开始逐步加重
            val actualCooldown = when {
                newFailures <= 2 -> 0L           // 小故障，容忍
                newFailures == 3 -> cooldownMs    // 第 3 次：冷 60s
                newFailures == 4 -> cooldownMs * 2 // 第 4 次：冷 120s
                else -> cooldownMs * 5            // ≥5 次：冷 300s
            }
            val cooldownUntil = if (actualCooldown > 0) now + actualCooldown else 0L

            providerCache[key] = existing.copy(
                cooldownUntil = cooldownUntil,
                cooldownDurationMs = actualCooldown,
                consecutiveFailures = newFailures,
                totalRequests = existing.totalRequests + 1,
                failedRequests = existing.failedRequests + 1,
            )
            allCache[providerId] = providerCache
            saveCache(allCache)

            if (actualCooldown > 0) {
                Log.w(TAG, "reportFailure: key=$key provider=$providerId failures=$newFailures cooled ${actualCooldown/1000}s")
            } else {
                Log.w(TAG, "reportFailure: key=$key provider=$providerId failures=$newFailures (tolerated)")
            }
        }
    }

    override fun reportSuccess(key: String, providerId: String) {
        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val providerCache = (allCache[providerId] ?: emptyMap()).toMutableMap()

            val existing = providerCache[key] ?: KeyEntry(lastUsed = now, cooldownUntil = 0)
            providerCache[key] = existing.copy(
                cooldownUntil = 0,
                consecutiveFailures = 0, // 成功一次就重置连续失败计数
                totalRequests = existing.totalRequests + 1,
                successfulRequests = existing.successfulRequests + 1,
            )
            allCache[providerId] = providerCache
            saveCache(allCache)
        }
    }

    override fun getKeyStates(providerId: String): List<KeyState> {
        synchronized(LruFileLock) {
            val allCache = loadCache()
            val providerCache = allCache[providerId] ?: return emptyList()
            return providerCache.map { (key, entry) ->
                KeyState(
                    key = key,
                    lastUsed = entry.lastUsed,
                    cooldownUntil = entry.cooldownUntil,
                    cooldownDurationMs = entry.cooldownDurationMs,
                    consecutiveFailures = entry.consecutiveFailures,
                    totalRequests = entry.totalRequests,
                    successfulRequests = entry.successfulRequests,
                    failedRequests = entry.failedRequests,
                )
            }.sortedByDescending { it.lastUsed }
        }
    }

    private fun loadCache(): LruCache {
        return try {
            val file = File(context.cacheDir, LRU_CACHE_FILE)
            if (!file.exists()) return emptyMap()
            Json.decodeFromString(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "loadCache: failed to read LRU key cache, starting empty", e)
            emptyMap()
        }
    }

    private fun saveCache(cache: LruCache) {
        try {
            File(context.cacheDir, LRU_CACHE_FILE).writeText(Json.encodeToString(cache))
        } catch (e: Exception) {
            Log.w(TAG, "saveCache: failed to persist LRU key cache", e)
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
