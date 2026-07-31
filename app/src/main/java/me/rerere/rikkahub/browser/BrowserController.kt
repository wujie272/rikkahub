package me.rerere.rikkahub.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.URI

/**
 * 浏览器控制器 —— 单例，管理浏览器会话池。
 *
 * 对标 OpenMinis BrowserTabPool（池化管理）+ BrowserUseManager（会话管理）。
 *
 * 全局配置（超时、搜索引擎、UA 等）直接放在这里；
 * 每个 [BrowserSession] 管理单个 WebView 的所有状态。
 */
object BrowserController {

    private const val TAG = "BrowserController"
    private const val MAX_SESSIONS = 3

    // ── 全局配置 ──
    @Volatile
    var singleTaskTimeoutMs: Long = BrowserToolDefaults.DEFAULT_SINGLE_TASK_TIMEOUT_MS

    @Volatile
    var perToolTimeoutMs: Long = BrowserToolDefaults.DEFAULT_PER_TOOL_TIMEOUT_MS

    @Volatile
    var searchEngineIndex: Int = BrowserToolDefaults.DEFAULT_SEARCH_ENGINE_INDEX

    fun currentSearchEngineUrlTemplate(): String =
        BrowserToolDefaults.SEARCH_ENGINES.getOrNull(searchEngineIndex)?.urlTemplate
            ?: BrowserToolDefaults.SEARCH_ENGINES.first().urlTemplate

    @Volatile
    var desktopMode: Boolean = false

    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    @Volatile
    var mobileUA: String? = null

    // ── 会话池（对标 OpenMinis BrowserTabPool） ──
    // ── 串行锁（对标 OpenMinis BrowserTabPool.tabLocks） ──
    private val sessionLocks = java.util.concurrent.ConcurrentHashMap<Int, Mutex>()

    /**
     * 获取某个会话的串行锁，确保同一标签页的并发工具调用不会踩踏 WebView。
     * 对标 OpenMinis BrowserTabPool.lockForTab
     */
    private fun lockForSession(id: Int): Mutex = sessionLocks.getOrPut(id) { Mutex() }

    /**
     * 在某个会话的串行锁保护下执行操作。
     * 对标 OpenMinis BrowserTabPool.executeSerialized
     */
    suspend fun <T> withSessionLock(sessionId: Int, action: suspend () -> T): T {
        val mutex = lockForSession(sessionId)
        return withTimeoutOrNull(60_000L) {
            mutex.withLock { action() }
        } ?: throw java.util.concurrent.TimeoutException("Session $sessionId lock timed out after 60s")
    }

    /**
     * 在当前选中会话的串行锁下执行操作。
     */
    suspend fun <T> withCurrentSessionLock(action: suspend () -> T): T? {
        val session = selectedSession ?: return null
        return withTimeoutOrNull(60_000L) {
            lockForSession(session.id).withLock { action() }
        }
    }
    private val _sessions = MutableStateFlow<List<BrowserSession>>(emptyList())
    val sessions: StateFlow<List<BrowserSession>> = _sessions.asStateFlow()

    private val _selectedSessionIndex = MutableStateFlow(0)
    val selectedSessionIndex: StateFlow<Int> = _selectedSessionIndex.asStateFlow()

    val selectedSession: BrowserSession?
        get() = _sessions.value.getOrNull(_selectedSessionIndex.value)

    internal fun activeWebView(): WebView? = selectedSession?.webView

    // ── 事件回调（全局） ──
    @Volatile
    var onShowEmbeddedRequest: (() -> Unit)? = null


    // ── 会话持久化（对标 OpenMinis BrowserTabPool.saveState/loadState） ──

    /** 保存的标签页 URL（在空闲回收时保存，重新打开时恢复） */
    private val savedUrls = mutableMapOf<Int, String>()

    /** 持久化文件路径 */
    private var stateFile: java.io.File? = null

    @Volatile
    private var persistenceInitialized = false

    private fun ensurePersistence(context: Context) {
        if (!persistenceInitialized) {
            persistenceInitialized = true
            initPersistence(context)
        }
    }

    /** 初始化持久化 */
    fun initPersistence(context: Context) {
        stateFile = java.io.File(context.filesDir, "browser_sessions.json")
        loadState()
    }

    /** 保存当前标签页状态到文件 */
    private fun saveState() {
        val file = stateFile ?: return
        try {
            val json = buildJsonObject {
                put("selectedIndex", _selectedSessionIndex.value)
                put("tabs", kotlinx.serialization.json.buildJsonArray {
                    _sessions.value.forEach { session ->
                        val url = session.currentUrl.value.ifEmpty { null }
                        if (url != null) {
                            add(buildJsonObject {
                                put("id", session.id)
                                put("url", url)
                                put("title", session.pageTitle.value)
                            })
                        }
                    }
                    savedUrls.forEach { (id, url) ->
                        if (_sessions.value.none { it.id == id }) {
                            add(buildJsonObject {
                                put("id", id)
                                put("url", url)
                                put("title", "")
                            })
                        }
                    }
                })
            }
            file.writeText(json.toString())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to save session state: ${e.message}")
        }
    }

    /** 从文件加载标签页状态 */
    private fun loadState() {
        val file = stateFile ?: return
        try {
            if (!file.exists()) return
            val text = file.readText()
            val json = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
            val tabsArray = json["tabs"]?.jsonArray ?: return
            savedUrls.clear()
            for (element in tabsArray) {
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.intOrNull ?: continue
                val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: continue
                savedUrls[id] = url
            }
            _selectedSessionIndex.value = json["selectedIndex"]?.jsonPrimitive?.intOrNull ?: 0
            android.util.Log.i(TAG, "Loaded ${savedUrls.size} saved sessions")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to load session state: ${e.message}")
        }
    }

    // ── 会话管理（对标 OpenMinis BrowserTabPool.createTab/closeTab/selectTab） ──

    fun createSession(context: Context): BrowserSession? {
        ensurePersistence(context)
        val current = _sessions.value.toMutableList()
        if (current.size >= MAX_SESSIONS) return null
        val id = (current.maxOfOrNull { it.id } ?: 0) + 1
        // 如果有保存的 URL，恢复
        val savedUrl = savedUrls.remove(id)
        val session = BrowserSession(id, context)
        if (savedUrl != null) {
            session.loadUrl(savedUrl)
        }
        current.add(session)
        _sessions.value = current
        _selectedSessionIndex.value = current.size - 1
        saveState()
        return session
    }

    fun selectSession(index: Int) {
        if (index in _sessions.value.indices) {
            _selectedSessionIndex.value = index
            _sessions.value = _sessions.value.toList()
        }
    }

    fun closeSession(index: Int) {
        val current = _sessions.value.toMutableList()
        if (index !in current.indices) return
        val session = current.removeAt(index)
        // 保存 URL 以便后续恢复
        val url = session.currentUrl.value
        if (url.isNotEmpty()) savedUrls[session.id] = url
        session.destroy()
        _sessions.value = current
        if (current.isEmpty()) {
            _selectedSessionIndex.value = 0
        } else if (index <= _selectedSessionIndex.value) {
            _selectedSessionIndex.value = (_selectedSessionIndex.value - 1).coerceAtLeast(0)
        }
        saveState()
    }

    fun releaseAllSessions() {
        // 释放前保存 URL
        _sessions.value.forEach { session ->
            val url = session.currentUrl.value
            if (url.isNotEmpty()) savedUrls[session.id] = url
            session.destroy()
        }
        _sessions.value = emptyList()
        _selectedSessionIndex.value = 0
        saveState()
    }

    // ── 浏览历史（全局，跨会话共享，对标 OpenMinis BrowserHistoryStore） ──
    private val _history = MutableStateFlow<List<BrowserHistoryEntry>>(emptyList())
    val historyFlow: StateFlow<List<BrowserHistoryEntry>> = _history.asStateFlow()

    private const val HISTORY_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 天
    private const val HISTORY_MAX_ENTRIES = 500

    fun addHistoryStatic(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        // 去重连续访问
        val current = _history.value
        if (current.firstOrNull()?.url == url) return
        val domain = runCatching { URI(url).host }.getOrNull() ?: ""
        val entry = BrowserHistoryEntry(url, title, System.currentTimeMillis(), domain)
        // 去重 + 7天清理 + 上限
        val pruned = (listOf(entry) + current.filter { it.url != url })
            .filter { System.currentTimeMillis() - it.timestamp < HISTORY_MAX_AGE_MS }
            .take(HISTORY_MAX_ENTRIES)
        _history.value = pruned
    }

    /**
     * 搜索历史记录。
     * 对标 OpenMinis BrowserHistoryStore.search
     */
    fun searchHistory(query: String): List<BrowserHistoryEntry> {
        if (query.isBlank()) return _history.value
        val q = query.lowercase()
        return _history.value.filter {
            it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
        }
    }

    /**
     * 获取历史记录中的唯一域名列表（用于 cookie 管理）。
     * 对标 OpenMinis BrowserHistoryStore.uniqueDomains
     */
    fun uniqueHistoryDomains(): List<String> {
        return _history.value.map { it.domain }.filter { it.isNotEmpty() }.distinct().sorted()
    }

    fun clearHistory() {
        _history.value = emptyList()
    }

    fun removeHistoryEntry(url: String) {
        _history.value = _history.value.filter { it.url != url }
    }

    // ── 状态读取（委托给当前会话） ──

    fun isBound(): Boolean = selectedSession != null
    fun isEmbeddedMode(): Boolean = _sessions.value.isNotEmpty()
    fun currentUrl(): String? = selectedSession?.webView?.url
    fun currentTitle(): String? = selectedSession?.webView?.title
    fun hasActivePage(): Boolean = selectedSession?.hasActivePage == true

    // ── 流（委托给当前会话，安全返回空值） ──

    val recentActions: StateFlow<List<String>>
        get() = selectedSession?.recentActions ?: MutableStateFlow(emptyList())

    val screenshotEvent: SharedFlow<Unit>
        get() = selectedSession?.screenshotEvent ?: MutableSharedFlow()

    val tabs: StateFlow<List<BrowserSession>>
        get() = _sessions

    val selectedTabIndex: StateFlow<Int>
        get() = _selectedSessionIndex

    val selectedTab: BrowserSession?
        get() = selectedSession

    fun taskWindowActiveFlow(): StateFlow<Boolean> =
        selectedSession?.taskWindowActive ?: MutableStateFlow(false)

    fun recentActionsFlow(): StateFlow<List<String>> =
        selectedSession?.recentActions ?: MutableStateFlow(emptyList())

    // ── 动作委托 ──

    /**
     * 判断某个工具是否属于「打开新页面」类型。
     * 对标 OpenMinis BrowserAction.opensNewPage
     */
    fun isOpensNewPage(toolName: String): Boolean = toolName in BrowserToolDefaults.OPENS_NEW_PAGE_TOOLS


    /**
     * 判断某个工具是否属于「视觉变化」类型（执行后应自动截图）。
     * 对标 OpenMinis BrowserAction.visualChangeActions
     */
    fun isVisualChange(toolName: String): Boolean = toolName in BrowserToolDefaults.VISUAL_CHANGE_TOOLS

    fun appendAction(label: String) {
        selectedSession?.appendAction(label)
    }

    fun touchActivity() {
        selectedSession?.let { it.lastActivityDate = System.currentTimeMillis() }
    }

    fun startTaskWindow() {
        selectedSession?.startTaskWindow()
    }

    fun clearTaskWindow() {
        selectedSession?.clearTaskWindow()
    }

    fun isWithinTaskWindow(): Boolean = selectedSession?.isWithinTaskWindow() == true

    fun stopCurrentTask() {
        selectedSession?.stopCurrentTask()
    }

    var lastActivityDate: Long
        get() = selectedSession?.lastActivityDate ?: System.currentTimeMillis()
        set(value) { selectedSession?.lastActivityDate = value }

    var currentTaskStartedAt: Long?
        get() = selectedSession?.currentTaskStartedAt
        set(value) { selectedSession?.currentTaskStartedAt = value }

    var pendingTaskJob: Job?
        get() = selectedSession?.pendingTaskJob
        set(value) { selectedSession?.pendingTaskJob = value }

    // ── 兼容旧接口（供 BrowserPreviewSheet 等调用） ──

    fun createTab(context: Context): BrowserSession? = createSession(context)
    fun selectTab(index: Int) { selectSession(index) }
    fun closeTab(index: Int) { closeSession(index) }
    fun releaseAllTabs() { releaseAllSessions() }
    fun ensureTab(context: Context): BrowserSession {
        return selectedSession ?: createSession(context) ?: error("Failed to create session")
    }

    // ── 空闲回收（对标 OpenMinis BrowserTabPool.evictionJob） ──

    private var evictionJob: Job? = null

    /** 空闲超时（毫秒），默认 15 分钟 */
    @Volatile
    var idleTimeoutMs: Long = 15 * 60 * 1000L

    /**
     * 启动空闲回收定时器（每 60 秒检查一次）。
     * 对标 OpenMinis BrowserTabPool.evictionJob
     */
    fun startIdleSweep() {
        if (evictionJob?.isActive == true) return
        evictionJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (isActive) {
                delay(60_000L)
                evictIdleSessions()
            }
        }
    }

    /**
     * 回收空闲标签页。
     * 对标 OpenMinis BrowserTabPool.evictIdleTabs
     */
    private fun evictIdleSessions() {
        val now = System.currentTimeMillis()
        val current = _sessions.value.toMutableList()
        val toRemove = current.filter { (now - it.lastActivityDate) >= idleTimeoutMs }
        if (toRemove.isEmpty()) return
        for (session in toRemove) {
            val url = session.currentUrl.value
            if (url.isNotEmpty()) savedUrls[session.id] = url
            session.destroy()
            current.remove(session)
            android.util.Log.i(TAG, "Evicted idle session ${session.id}")
        }
        _sessions.value = current
        if (current.isNotEmpty() && current.none { it.id == _sessions.value.getOrNull(_selectedSessionIndex.value)?.id }) {
            _selectedSessionIndex.value = 0
        }
        saveState()
    }

    /**
     * 设置空闲超时（分钟）。
     * 对标 OpenMinis BrowserTabPool.setIdleTimeoutMinutes
     */
    fun setIdleTimeoutMinutes(minutes: Int) {
        idleTimeoutMs = (minutes.coerceIn(1, 240) * 60_000L).coerceAtLeast(60_000L)
    }

    // ── 截图 ──

    // ── Viewport 管理（对标 OpenMinis BrowserTabPool.setGlobalViewport / resolvedViewportSize） ──

    private val _customViewportWidth = MutableStateFlow(0)
    val customViewportWidth: StateFlow<Int> = _customViewportWidth.asStateFlow()

    private val _customViewportHeight = MutableStateFlow(0)
    val customViewportHeight: StateFlow<Int> = _customViewportHeight.asStateFlow()

    /**
     * 设置全局 Viewport。宽/高为 0 时恢复为 UA 默认值。
     * 对标 OpenMinis: tabPool.setGlobalViewport(width, height)
     */
    fun setGlobalViewport(width: Int, height: Int) {
        _customViewportWidth.value = width
        _customViewportHeight.value = height
        val session = selectedSession ?: return
        if (width > 0 && height > 0) {
            session.applyViewport(width, height)
        }
    }

    /**
     * 返回当前生效的 Viewport 尺寸（自定义优先，否则 UA 默认）。
     * 对标 OpenMinis: tabPool.resolvedViewportSize()
     */
    fun resolvedViewportSize(): Pair<Int, Int> {
        val cw = _customViewportWidth.value
        val ch = _customViewportHeight.value
        if (cw > 0 && ch > 0) return cw to ch
        return 412 to 915
    }

    /**
     * 根据 UA 配置返回默认 Viewport
     */
    fun defaultViewportForUA(profile: UserAgentProfile): Pair<Int, Int> = profile.viewportSize

    /**
     * 是否有自定义 Viewport
     */
    fun hasCustomViewport(): Boolean = _customViewportWidth.value > 0 && _customViewportHeight.value > 0

    suspend fun captureLiveSnapshot(): Bitmap? = selectedSession?.captureLiveSnapshot()

    // ── 头戴模式 ──

    private val streamDedupe = mutableMapOf<String, StreamMark>()
    private data class StreamMark(val url: String?, val atMs: Long)
    private const val STREAM_CACHE_SUBDIR = "browser-stream"
    private const val STREAM_DEDUPE_WINDOW_MS = 30_000L
    private const val PAINT_SETTLE_MS = 600L

    private val bindLock = Any()
    @Volatile
    private var bindDeferred: CompletableDeferred<Unit> = CompletableDeferred()

    fun bindHeadless(callerConvId: String, webView: WebView): Boolean {
        synchronized(bindLock) {
            if (_sessions.value.isNotEmpty()) return false
            val session = BrowserSession(1, webView.context)
            _sessions.value = listOf(session)
            _selectedSessionIndex.value = 0
            streamDedupe.remove(callerConvId)
        }
        if (!bindDeferred.isCompleted) {
            bindDeferred.complete(Unit)
        }
        runCatching { BrowserCacheSweeper.sweep(webView.context.applicationContext) }
        return true
    }

    fun canBindHeadless(callerConvId: String): Boolean = synchronized(bindLock) {
        _sessions.value.isEmpty()
    }

    fun unbindHeadless(callerConvId: String) {
        synchronized(bindLock) {
            releaseAllSessions()
            streamDedupe.remove(callerConvId)
            bindDeferred = CompletableDeferred()
        }
    }

    fun clearSession(callerConvId: String) {
        synchronized(bindLock) {
            releaseAllSessions()
            streamDedupe.remove(callerConvId)
            bindDeferred = CompletableDeferred()
        }
    }

    suspend fun awaitBind(timeoutMs: Long = 5_000L): Boolean {
        if (isBound()) return true
        return withTimeoutOrNull(timeoutMs) { bindDeferred.await(); true } ?: false
    }

    suspend fun streamScreenshotIfHeadless(actionLabel: String) {
        val session = selectedSession ?: return
        val webView = session.webView
        val context = webView.context.applicationContext ?: return

        val currentUrl = runCatching {
            withContext(Dispatchers.Main) { webView.url }
        }.getOrNull()

        val now = System.currentTimeMillis()
        val lastMark = synchronized(bindLock) { streamDedupe["headless"] }
        if (currentUrl != null && currentUrl == lastMark?.url &&
            (now - lastMark.atMs) < STREAM_DEDUPE_WINDOW_MS
        ) { return }

        kotlinx.coroutines.delay(PAINT_SETTLE_MS)

        data class Capture(val path: String, val url: String?)
        val capture = runCatching {
            withContext(Dispatchers.Main) {
                val w = webView.width.coerceAtLeast(1)
                val h = webView.height.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                webView.draw(canvas)
                val cacheDir = File(context.cacheDir, STREAM_CACHE_SUBDIR).apply { mkdirs() }
                val out = File(cacheDir, "stream-${System.currentTimeMillis()}.webp")
                try {
                    FileOutputStream(out).use { os ->
                        bitmap.compress(Bitmap.CompressFormat.WEBP, 85, os)
                    }
                } finally { bitmap.recycle() }
                Capture(out.absolutePath, currentUrl)
            }
        }.getOrNull() ?: return

        synchronized(bindLock) { streamDedupe["headless"] = StreamMark(capture.url, now) }

        val streamer: BrowserScreenshotStreamer? = runCatching {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<BrowserScreenshotStreamer>()
        }.getOrNull()
        runCatching {
            (streamer ?: BrowserScreenshotStreamer.NoOp)
                .send("headless", capture.path, actionLabel, capture.url)
        }
    }

    // ── 错误信封 ──

    // ── 下载管理（对标 OpenMinis BrowserTabPool.downloads） ──

    data class DownloadState(
        val url: String,
        val filename: String,
        val progress: Float = 0f,       // 0..1, -1 = indeterminate
        val completed: Boolean = false,
        val formattedSize: String = "",
        val bytesDone: Long = 0L,
        val totalBytes: Long = 0L,
    )

    private val _downloads = MutableStateFlow<List<DownloadState>>(emptyList())
    val downloadsFlow: StateFlow<List<DownloadState>> = _downloads.asStateFlow()

    /**
     * 注册一个下载（从 WebView 的 DownloadListener 触发）。
     */
    fun startDownload(url: String, filename: String, totalBytes: Long = -1L) {
        val entry = DownloadState(
            url = url,
            filename = filename,
            progress = if (totalBytes > 0) 0f else -1f,
            totalBytes = totalBytes,
        )
        _downloads.value = listOf(entry) + _downloads.value
        appendAction("Download: $filename")
    }

    /**
     * 更新下载进度。
     */
    fun updateDownloadProgress(url: String, bytesDone: Long, totalBytes: Long) {
        _downloads.value = _downloads.value.map {
            if (it.url == url) {
                val progress = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f) else -1f
                it.copy(progress = progress, bytesDone = bytesDone, totalBytes = totalBytes)
            } else it
        }
    }

    /**
     * 完成下载。
     */
    fun finishDownload(url: String, formattedSize: String) {
        _downloads.value = _downloads.value.map {
            if (it.url == url) it.copy(completed = true, progress = 1f, formattedSize = formattedSize)
            else it
        }
    }

    /**
     * 获取未读下载数量（用于 badge）。
     */
    fun unreadDownloadCount(): Int = _downloads.value.count { !it.completed }

    /**
     * 清除已完成下载。
     */
    fun clearCompletedDownloads() {
        _downloads.value = _downloads.value.filter { !it.completed }
    }

    /**
     * 处理 blob: 下载（通过 JS bridge 读取）。
     * 对标 OpenMinis BrowserUseManager.fetchBlobDownload
     */
    fun handleBlobDownload(blobUrl: String, filename: String) {
        startDownload(blobUrl, filename)
        val session = selectedSession ?: return
        // 通过 JS bridge 读取 blob（不需要 withContext，evaluateJavascript 可以直接在主线程调用）
        val js = """
            (async function() {
                try {
                    const resp = await fetch(${kotlinx.serialization.json.JsonPrimitive(blobUrl)});
                    const blob = await resp.blob();
                    const reader = new FileReader();
                    reader.onloadend = function() {
                        __rikkahub__.saveBlobDownload(reader.result, ${kotlinx.serialization.json.JsonPrimitive(filename)});
                    };
                    reader.onerror = function() { __rikkahub__.blobDownloadError('FileReader error'); };
                    reader.readAsDataURL(blob);
                } catch(e) {
                    __rikkahub__.blobDownloadError(String(e));
                }
            })();
        """.trimIndent()
        session.webView.post { session.webView.evaluateJavascript(js, null) }
    }

    /**
     * 在 WebView 中设置 DownloadListener，拦截下载请求。
     * 在创建会话时调用。
     */
    fun setupDownloadListener(webView: WebView) {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
            when {
                url.startsWith("blob:") -> handleBlobDownload(url, filename)
                else -> {
                    startDownload(url, filename, contentLength)
                    // 启动后台下载线程，保存到 workspace
                    Thread {
                        try {
                            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 15_000
                            conn.readTimeout = 30_000
                            conn.instanceFollowRedirects = true
                            userAgent?.takeIf { it.isNotEmpty() }?.let { conn.setRequestProperty("User-Agent", it) }
                            val total = if (contentLength > 0) contentLength else conn.contentLengthLong
                            // 保存到 workspace 目录（~ 或 filesDir/workspace/）
                            val workspaceDir = java.io.File(webView.context.filesDir, "workspace").apply { mkdirs() }
                            val dest = java.io.File(workspaceDir, filename).let { f ->
                                var i = 1; var file = f
                                while (file.exists()) { file = java.io.File(workspaceDir, "${filename.substringBeforeLast('.')}-$i.${filename.substringAfterLast('.')}"); i++ }
                                file
                            }
                            conn.inputStream.use { input ->
                                dest.outputStream().use { out ->
                                    val buf = ByteArray(64 * 1024)
                                    var copied = 0L
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        copied += n
                                        updateDownloadProgress(url, copied, if (total > 0) total else copied)
                                    }
                                }
                            }
                            conn.disconnect()
                            val sizeText = android.text.format.Formatter.formatShortFileSize(webView.context, dest.length())
                            finishDownload(url, sizeText)
                            appendAction("Downloaded: $filename ($sizeText) → workspace/")
                        } catch (e: Exception) {
                            android.util.Log.w(TAG, "Download failed: ${e.message}")
                        }
                    }.start()
                }
            }
        }
    }

    fun notOpenEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_not_open")
        put("recovery", "Call browser_open with a URL to launch the browser before invoking this tool.")
    }

    fun taskTimeoutEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_task_timeout")
        put("recovery", "Call browser_done with a summary; the per-task 5-minute cap has been reached.")
    }

    fun sessionLostEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_session_lost")
        put("recovery", "The headless browser session ended (the calling foreground service was killed). Ask the user to retry.")
    }

    fun bindBusyEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_busy")
        put("recovery", "Another conversation is currently driving the browser. Wait for it to finish (it calls browser_done), then retry browser_open.")
    }
}

object BrowserControllerHandle {

    /**
     * 在串行锁保护下执行浏览器操作。
     * 对标 OpenMinis BrowserTabPool.runAcquiredAction
     */
    suspend fun withSerializedController(
        block: suspend WithControllerScope.() -> JsonObject,
    ): JsonObject {
        val session = BrowserController.selectedSession
        if (session == null) return BrowserController.notOpenEnvelope()
        if (!BrowserController.isWithinTaskWindow()) {
            return BrowserController.taskTimeoutEnvelope()
        }
        return BrowserController.withSessionLock(session.id) {
            withContext(Dispatchers.Main) {
                WithControllerScope(BrowserController, session.webView).block()
            }
        }
    }

    data class WithControllerScope(
        val controller: BrowserController,
        val webView: WebView,
    )

    suspend fun withController(
        block: suspend WithControllerScope.() -> JsonObject,
    ): JsonObject {
        val wv = BrowserController.activeWebView() ?: return BrowserController.notOpenEnvelope()
        if (!BrowserController.isWithinTaskWindow()) {
            return BrowserController.taskTimeoutEnvelope()
        }
        return withContext(Dispatchers.Main) {
            WithControllerScope(BrowserController, wv).block()
        }
    }
}

suspend fun WebView.evaluateJavascriptAsync(code: String, timeoutMs: Long = 8_000L): String? {
    val deferred = CompletableDeferred<String?>()
    withContext(Dispatchers.Main) {
        try {
            evaluateJavascript(code) { result -> deferred.complete(result) }
        } catch (e: Exception) {
            android.util.Log.w("BrowserController", "evaluateJavascriptAsync: evaluateJavascript threw", e)
            deferred.complete(null)
        }
    }
    return withTimeoutOrNull(timeoutMs) { deferred.await() }
}

suspend fun WebView.awaitReadyState(timeoutMs: Long = 8_000L): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val raw = evaluateJavascriptAsync("(function(){return document.readyState;})()", 1_500L)
        if (raw != null && raw.trim() == "\"complete\"") return true
        kotlinx.coroutines.delay(200)
    }
    return false
}
