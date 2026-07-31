package me.rerere.rikkahub.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 浏览器全局配置 —— 纯数据，无 context 依赖，不涉及会话管理。
 * 保持 object 单例，因为配置本身就是全局的。
 */
object BrowserConfig {

    // ── 超时 ──
    @Volatile
    var singleTaskTimeoutMs: Long = BrowserToolDefaults.DEFAULT_SINGLE_TASK_TIMEOUT_MS

    @Volatile
    var perToolTimeoutMs: Long = BrowserToolDefaults.DEFAULT_PER_TOOL_TIMEOUT_MS

    // ── 搜索引擎 ──
    @Volatile
    var searchEngineIndex: Int = BrowserToolDefaults.DEFAULT_SEARCH_ENGINE_INDEX

    fun currentSearchEngineUrlTemplate(): String =
        BrowserToolDefaults.SEARCH_ENGINES.getOrNull(searchEngineIndex)?.urlTemplate
            ?: BrowserToolDefaults.SEARCH_ENGINES.first().urlTemplate

    // ── UA ──
    @Volatile
    var desktopMode: Boolean = false

    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    @Volatile
    var mobileUA: String? = null

    // ── Viewport（UI 层可观察） ──
    private val _customViewportWidth = MutableStateFlow(0)
    val customViewportWidth: StateFlow<Int> = _customViewportWidth.asStateFlow()

    private val _customViewportHeight = MutableStateFlow(0)
    val customViewportHeight: StateFlow<Int> = _customViewportHeight.asStateFlow()

    fun setCustomViewport(width: Int, height: Int) {
        _customViewportWidth.value = width
        _customViewportHeight.value = height
    }

    fun resolvedViewportSize(): Pair<Int, Int> {
        val cw = _customViewportWidth.value
        val ch = _customViewportHeight.value
        if (cw > 0 && ch > 0) return cw to ch
        return 412 to 915
    }

    fun defaultViewportForUA(profile: UserAgentProfile): Pair<Int, Int> = profile.viewportSize

    fun hasCustomViewport(): Boolean = _customViewportWidth.value > 0 && _customViewportHeight.value > 0

    // ── 空闲超时 ──
    @Volatile
    var idleTimeoutMs: Long = 15 * 60 * 1000L

    fun setIdleTimeoutMinutes(minutes: Int) {
        idleTimeoutMs = (minutes.coerceIn(1, 240) * 60_000L).coerceAtLeast(60_000L)
    }
}
