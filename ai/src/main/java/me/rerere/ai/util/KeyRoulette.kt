package me.rerere.ai.util

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

interface KeyRoulette {
    /** 从结构化 keys 中选择一个 */
    fun nextKey(
        keys: List<ApiKeyConfig>,
        config: KeyManagementConfig,
        providerId: String,
    ): ApiKeyConfig

    /** 报告调用结果（用于健康度跟踪） */
    fun reportResult(providerId: String, keyId: kotlin.uuid.Uuid, success: Boolean, error: String? = null)

    /** 旧版兼容：从空格/逗号分隔的 key 字符串中随机选一个 */
    fun next(keys: String, providerId: String = ""): String

    fun reportCallResult(providerId: String, keyId: kotlin.uuid.Uuid, success: Boolean, error: String? = null) {}

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /** LRU 轮询，持久化存储到 cacheDir/lru_key_roulette.json */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)

        /** 结构化 Key 版本（含多策略 + 健康度追踪） */
        fun structured(context: Context): KeyRoulette = StructuredKeyRoulette(context)
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex()

private fun splitKey(key: String): List<String> {
    return key.split(SPLIT_KEY_REGEX).map { it.trim() }.filter { it.isNotBlank() }.distinct()
}

/** 旧版随机策略 */
private class DefaultKeyRoulette : KeyRoulette {
    override fun nextKey(keys: List<ApiKeyConfig>, config: KeyManagementConfig, providerId: String): ApiKeyConfig {
        val active = keys.activeKeys()
        return if (active.isNotEmpty()) active.random() else keys.first()
    }

    override fun reportResult(providerId: String, keyId: kotlin.uuid.Uuid, success: Boolean, error: String?) {}

    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        return if (keyList.isNotEmpty()) keyList.random() else keys
    }
}

/** LRU 轮询（旧版持久化实现） */
private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L
private object LruFileLock
private typealias LruCache = Map<String, Map<String, Long>>

private class LruKeyRoulette(private val context: Context) : KeyRoulette {
    override fun nextKey(keys: List<ApiKeyConfig>, config: KeyManagementConfig, providerId: String): ApiKeyConfig {
        val active = keys.activeKeys()
        if (active.isEmpty()) return keys.first()
        // 回退到旧版逻辑
        return active.random()
    }

    override fun reportResult(providerId: String, keyId: kotlin.uuid.Uuid, success: Boolean, error: String?) {}

    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys
        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val providerCache = (allCache[providerId] ?: emptyMap())
                .filter { (k, lastUsed) -> k in keyList && now - lastUsed < EXPIRE_DURATION_MS }
                .toMutableMap()
            val selected = keyList.firstOrNull { it !in providerCache }
                ?: providerCache.minByOrNull { it.value }!!.key
            providerCache[selected] = now
            allCache[providerId] = providerCache
            allCache.entries.removeIf { (id, cache) ->
                id != providerId && cache.values.all { now - it >= EXPIRE_DURATION_MS }
            }
            saveCache(allCache)
            return selected
        }
    }

    private fun loadCache(): LruCache = try {
        val file = File(context.cacheDir, LRU_CACHE_FILE)
        if (!file.exists()) emptyMap() else Json.decodeFromString(file.readText())
    } catch (_: Exception) { emptyMap() }

    private fun saveCache(cache: LruCache) {
        try { File(context.cacheDir, LRU_CACHE_FILE).writeText(Json.encodeToString(cache)) }
        catch (_: Exception) {}
    }
}

/**
 * 结构化 Key 轮换引擎
 * 支持 RANDOM / ROUND_ROBIN / LEAST_USED / PRIORITY_FIRST 四种策略
 * 自动跟踪健康度，标记 ERROR / RATE_LIMITED 状态
 */
private class StructuredKeyRoulette(private val context: Context) : KeyRoulette {
    // providerId -> AtomicInteger (ROUND_ROBIN 计数器)
    private val roundRobinCounters = mutableMapOf<String, AtomicInteger>()
    private val counterLock = Any()

    override fun nextKey(
        keys: List<ApiKeyConfig>,
        config: KeyManagementConfig,
        providerId: String,
    ): ApiKeyConfig {
        val active = keys.activeKeys()
        if (active.isEmpty()) {
            val fallback = keys.firstOrNull { it.status != ApiKeyStatus.DISABLED }
                ?: keys.first()
            return fallback
        }

        // 跳过连续失败超过 maxFailures 的 Key
        val maxFail = config.maxFailures
        val candidates = synchronized(trackerLock) {
            val tracker = failureTracker[providerId] ?: emptyMap()
            active.filter { (tracker[it.id] ?: 0) < maxFail }
        }
        val pool = if (candidates.isNotEmpty()) candidates else active

        // 按策略选择
        val selected = when (config.strategy) {
            LoadBalanceStrategy.RANDOM -> pool.random()
            LoadBalanceStrategy.ROUND_ROBIN -> roundRobinPick(pool, providerId)
            LoadBalanceStrategy.LEAST_USED -> pool.minByOrNull { it.usage.totalCalls } ?: pool.first()
            LoadBalanceStrategy.PRIORITY_FIRST -> pool.first()
        }

        // 在内存中递增用量
        synchronized(trackerLock) {
            val counts = usageCounts.getOrPut(providerId) { mutableMapOf() }
            counts[selected.id] = (counts[selected.id] ?: 0L) + 1
        }

        return selected
    }

    private fun roundRobinPick(active: List<ApiKeyConfig>, providerId: String): ApiKeyConfig {
        val counter = synchronized(counterLock) {
            roundRobinCounters.getOrPut(providerId) { AtomicInteger(0) }
        }
        val raw = counter.getAndIncrement()
        val index = (if (raw < 0) -(raw % active.size) else raw % active.size)
        return active[index]
    }

    // providerId -> keyId -> consecutiveFailures
    private val failureTracker = mutableMapOf<String, MutableMap<String, Int>>()
    private val trackerLock = Any()

    override fun reportCallResult(
        providerId: String,
        keyId: kotlin.uuid.Uuid,
        success: Boolean,
        error: String?,
    ) {
        synchronized(trackerLock) {
            val providerTracker = failureTracker.getOrPut(providerId) { mutableMapOf() }
            val keyStr = keyId.toString()
            if (success) {
                providerTracker.remove(keyStr)
            } else {
                providerTracker[keyStr] = (providerTracker[keyStr] ?: 0) + 1
            }
        }
    }

    override fun reportResult(
        providerId: String,
        keyId: kotlin.uuid.Uuid,
        success: Boolean,
        error: String?,
    ) {
        reportCallResult(providerId, keyId, success, error)
    }

    /** 获取某个 Key 的连续失败次数（用于判断是否需要跳过） */
    fun getConsecutiveFailures(providerId: String, keyId: kotlin.uuid.Uuid): Int {
        synchronized(trackerLock) {
            return failureTracker[providerId]?.get(keyId.toString()) ?: 0
        }
    }

    override fun next(keys: String, providerId: String): String {
        // 旧版兼容
        return DefaultKeyRoulette().next(keys, providerId)
    }
}


/**
 * 从 ProviderSetting 中选取一个有效 Key 并返回完整 ApiKeyConfig
 * 优先使用结构化 apiKeys，若为空则 fallback 到 apiKey 字符串
 */
fun KeyRoulette.resolveKey(ps: me.rerere.ai.provider.ProviderSetting): ApiKeyConfig {
    val effective = ps.getEffectiveApiKeys()
    if (effective.isEmpty()) {
        // fallback: 从旧字符串创建一个临时对象
        return ApiKeyConfig(key = ps.getLegacyApiKey(), name = "Default")
    }
    return nextKey(effective, ps.keyManagement, ps.id.toString())
}
