package me.rerere.rikkahub.data.ai.tools.local

import java.util.concurrent.ConcurrentHashMap

class DnsCache(
    private val ttlMs: Long = 60_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(val ip: String, val expiresAtMs: Long)
    private val map = ConcurrentHashMap<String, Entry>()

    fun get(host: String): String? {
        val entry = map[host] ?: return null
        if (nowMs() >= entry.expiresAtMs) {
            map.remove(host, entry)
            return null
        }
        return entry.ip
    }

    fun put(host: String, ip: String) {
        map[host] = Entry(ip, nowMs() + ttlMs)
    }

    fun invalidateAll() {
        map.clear()
    }

    fun size(): Int = map.size
}
