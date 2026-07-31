package me.rerere.rikkahub.browser

/**
 * 浏览器工具定义
 */
object BrowserToolDefaults {

    // ── 搜索引擎 ──

    data class SearchEngine(
        val name: String,
        val urlTemplate: String,
    )

    val SEARCH_ENGINES: List<SearchEngine> = listOf(
        SearchEngine("DuckDuckGo", "https://duckduckgo.com/?q="),
        SearchEngine("Google", "https://www.google.com/search?q="),
        SearchEngine("Bing", "https://www.bing.com/search?q="),
        SearchEngine("Brave", "https://search.brave.com/search?q="),
        SearchEngine("Startpage", "https://www.startpage.com/do/dsearch?query="),
    )

    const val DEFAULT_SEARCH_ENGINE_INDEX = 0

    fun buildSearchUrl(query: String, engineUrlTemplate: String): String =
        "$engineUrlTemplate${java.net.URLEncoder.encode(query, "UTF-8")}"

    // ── 工具常量 ──

    const val NAVIGATE = "navigate"
    const val SCREENSHOT = "screenshot"
    const val CLICK = "click"
    const val TYPE = "type"
    const val GET_TEXT = "get_text"
    const val SCROLL = "scroll"
    const val GET_PAGE_INFO = "get_page_info"
    const val EXECUTE_JS = "execute_js"
    const val FIND_ELEMENTS = "find_elements"
    const val HOVER = "hover"
    const val GET_READABLE = "get_readable"
    const val SET_USER_AGENT = "set_user_agent"
    const val SET_VIEWPORT = "set_viewport"
    const val GET_BACKBONE = "get_backbone"
    const val FETCH = "fetch"
    const val NEW_TAB = "new_tab"
    const val CLOSE_TAB = "close_tab"
    const val LIST_TABS = "list_tabs"
    const val GET_COOKIES = "get_cookies"
    const val SET_COOKIES = "set_cookies"
    const val SCROLL_AND_COLLECT = "scroll_and_collect"
    const val WAIT_FOR_DOM_STABLE = "wait_for_dom_stable"

    // ── 分类 ──

    val READ_TOOLS: Set<String> = setOf(
        NAVIGATE, SCREENSHOT, GET_TEXT, GET_PAGE_INFO, GET_READABLE,
        GET_BACKBONE, FETCH, GET_COOKIES, LIST_TABS,
        FIND_ELEMENTS, WAIT_FOR_DOM_STABLE,
    )

    val WRITE_TOOLS: Set<String> = setOf(
        CLICK, TYPE, SCROLL, EXECUTE_JS,
        HOVER, SET_USER_AGENT, SET_VIEWPORT,
        NEW_TAB, CLOSE_TAB, SET_COOKIES, SCROLL_AND_COLLECT,
    )

    /**
     * 视觉变化动作——执行后页面内容会发生变化，应自动截图。
     */
    val VISUAL_CHANGE_TOOLS: Set<String> = setOf(
        NAVIGATE, CLICK, SCROLL, TYPE, HOVER,
    )

    /**
     * 打开新页面的动作——这些动作可以 fanned out 到一个新标签页。
     * 其他动作（click, get_text 等）必须始终操作当前选中的标签页。
     */
    val OPENS_NEW_PAGE_TOOLS: Set<String> = setOf(
        NAVIGATE,
    )

    val ALL_TOOLS: List<String> = listOf(
        NAVIGATE, SCREENSHOT, GET_TEXT, GET_PAGE_INFO, GET_READABLE,
        GET_BACKBONE, FETCH, GET_COOKIES, LIST_TABS,
        FIND_ELEMENTS, WAIT_FOR_DOM_STABLE,
        CLICK, TYPE, SCROLL, EXECUTE_JS,
        HOVER, SET_USER_AGENT, SET_VIEWPORT,
        NEW_TAB, CLOSE_TAB, SET_COOKIES, SCROLL_AND_COLLECT,
    )

    val DEFAULT_ENABLED: Map<String, Boolean> = buildMap {
        READ_TOOLS.forEach { put(it, true) }
        WRITE_TOOLS.forEach { put(it, false) }
    }

    // ── 超时设置 ──

    const val DEFAULT_PER_TOOL_TIMEOUT_MS = 30_000L
    const val MIN_PER_TOOL_TIMEOUT_MS = 10_000L
    const val MAX_PER_TOOL_TIMEOUT_MS = 600_000L

    const val DEFAULT_SINGLE_TASK_TIMEOUT_MS = 900_000L
    const val MIN_SINGLE_TASK_TIMEOUT_MS = 60_000L
    const val MAX_SINGLE_TASK_TIMEOUT_MS = 14_400_000L

    fun clampPerToolTimeoutMs(ms: Long): Long = ms.coerceIn(MIN_PER_TOOL_TIMEOUT_MS, MAX_PER_TOOL_TIMEOUT_MS)
    fun clampSingleTaskTimeoutMs(ms: Long): Long = ms.coerceIn(MIN_SINGLE_TASK_TIMEOUT_MS, MAX_SINGLE_TASK_TIMEOUT_MS)
    fun clampSearchEngineIndex(index: Int): Int = index.coerceIn(0, SEARCH_ENGINES.size - 1)
}
