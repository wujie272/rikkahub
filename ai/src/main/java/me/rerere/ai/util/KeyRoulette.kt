package me.rerere.ai.util

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "KeyRoulette"

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

            // 找出不在冷却中的 key
            val availableKeys = keyList.filter { key -> (providerCache[key]?.cooldownUntil ?: 0) <= now }

            if (availableKeys.isEmpty()) {
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

            // 优先选从未使用的 key，否则选最久未使用的
            val selected = availableKeys.firstOrNull { it !in providerCache }
                ?: availableKeys.minByOrNull { providerCache[it]!!.lastUsed }!!

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
            providerCache[key] = existing.copy(cooldownUntil = now + cooldownMs)
            allCache[providerId] = providerCache
            saveCache(allCache)

            Log.w(TAG, "reportFailure: key=$key provider=$providerId cooled until ${now + cooldownMs}")
        }
    }

    override fun reportSuccess(key: String, providerId: String) {
        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val providerCache = (allCache[providerId] ?: emptyMap()).toMutableMap()

            val existing = providerCache[key] ?: KeyEntry(lastUsed = now, cooldownUntil = 0)
            providerCache[key] = existing.copy(cooldownUntil = 0)
            allCache[providerId] = providerCache
            saveCache(allCache)
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
