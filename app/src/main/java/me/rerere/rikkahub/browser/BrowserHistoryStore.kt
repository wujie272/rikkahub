package me.rerere.rikkahub.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/**
 * 浏览历史存储 —— 全局跨会话共享。
 *
 * 从 [BrowserController] 中拆出，职责单一：管理历史记录列表。
 * 支持添加、搜索、去重、按天清理、上限裁剪。
 */
class BrowserHistoryStore {

    private val _history = MutableStateFlow<List<BrowserHistoryEntry>>(emptyList())
    val historyFlow: StateFlow<List<BrowserHistoryEntry>> = _history.asStateFlow()

    companion object {
        private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 天
        private const val MAX_ENTRIES = 500
    }

    /**
     * 添加一条浏览记录。自动去重（连续访问相同 URL 不重复记录）、
     * 清理超过 7 天的记录、裁剪到上限 500 条。
     */
    fun add(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        val current = _history.value
        if (current.firstOrNull()?.url == url) return
        val domain = runCatching { URI(url).host }.getOrNull() ?: ""
        val entry = BrowserHistoryEntry(url, title, System.currentTimeMillis(), domain)
        val pruned = (listOf(entry) + current.filter { it.url != url })
            .filter { System.currentTimeMillis() - it.timestamp < MAX_AGE_MS }
            .take(MAX_ENTRIES)
        _history.value = pruned
    }

    /**
     * 搜索历史记录（按标题/URL 模糊匹配）。
     */
    fun search(query: String): List<BrowserHistoryEntry> {
        if (query.isBlank()) return _history.value
        val q = query.lowercase()
        return _history.value.filter {
            it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
        }
    }

    /**
     * 获取历史记录中的唯一域名列表。
     */
    fun uniqueDomains(): List<String> =
        _history.value.map { it.domain }.filter { it.isNotEmpty() }.distinct().sorted()

    /** 清空所有历史记录 */
    fun clear() {
        _history.value = emptyList()
    }

    /** 删除单条历史记录 */
    fun remove(url: String) {
        _history.value = _history.value.filter { it.url != url }
    }
}
