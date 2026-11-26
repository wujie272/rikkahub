package me.rerere.rikkahub.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream

/**
 * 浏览器会话池 —— 管理多个 [BrowserSession] 的生命周期。
 *
 * 职责：
 * - 创建/选择/关闭/释放会话
 * - 空闲回收
 * - 持久化
 * - 头戴模式
 * - 下载管理、历史记录
 *
 * 通过回调 hook [BrowserSession] 的事件，不做紧耦合引用。
 */
class BrowserSessionPool(private val context: Context) {

    companion object {
        private const val TAG = "BrowserSessionPool"
        private const val MAX_SESSIONS = 3
        private const val STREAM_CACHE_SUBDIR = "browser-stream"
        private const val STREAM_DEDUPE_WINDOW_MS = 30_000L
        private const val PAINT_SETTLE_MS = 600L
    }

    // ── 会话状态 ──
    private val _sessions = MutableStateFlow<List<BrowserSession>>(emptyList())
    val sessions: StateFlow<List<BrowserSession>> = _sessions.asStateFlow()

    private val _selectedSessionIndex = MutableStateFlow(0)
    val selectedSessionIndex: StateFlow<Int> = _selectedSessionIndex.asStateFlow()

    val selectedSession: BrowserSession?
        get() = _sessions.value.getOrNull(_selectedSessionIndex.value)

    internal fun activeWebView(): WebView? = selectedSession?.webView

    @Volatile
    var onShowEmbeddedRequest: (() -> Unit)? = null

    // ── 串行锁 ──
    private val sessionLocks = java.util.concurrent.ConcurrentHashMap<Int, Mutex>()

    private fun lockForSession(id: Int): Mutex = sessionLocks.getOrPut(id) { Mutex() }

    suspend fun <T> withSessionLock(sessionId: Int, action: suspend () -> T): T {
        val mutex = lockForSession(sessionId)
        return withTimeoutOrNull(60_000L) {
            mutex.withLock { action() }
        } ?: throw java.util.concurrent.TimeoutException("Session $sessionId lock timed out after 60s")
    }

    // ── 历史记录 ──
    val historyStore = BrowserHistoryStore()
    val historyFlow: StateFlow<List<BrowserHistoryEntry>> get() = historyStore.historyFlow

    fun addHistoryStatic(url: String, title: String) = historyStore.add(url, title)
    fun searchHistory(query: String): List<BrowserHistoryEntry> = historyStore.search(query)
    fun uniqueHistoryDomains(): List<String> = historyStore.uniqueDomains()
    fun clearHistory() = historyStore.clear()
    fun removeHistoryEntry(url: String) = historyStore.remove(url)

    // ── 下载管理 ──
    val downloadManager = BrowserDownloadManager()
    val downloadsFlow: StateFlow<List<BrowserDownloadManager.DownloadState>> get() = downloadManager.downloadsFlow

    fun startDownload(url: String, filename: String, totalBytes: Long = -1L) {
        downloadManager.start(url, filename, totalBytes)
    }

    fun updateDownloadProgress(url: String, bytesDone: Long, totalBytes: Long) =
        downloadManager.updateProgress(url, bytesDone, totalBytes)

    fun finishDownload(url: String, formattedSize: String) =
        downloadManager.finish(url, formattedSize)

    fun unreadDownloadCount(): Int = downloadManager.unreadCount()
    fun clearCompletedDownloads() = downloadManager.clearCompleted()

    // ── 持久化 ──
    private val savedUrls = mutableMapOf<Int, String>()
    private var stateFile: File? = null
    private var persistenceInitialized = false

    private fun ensurePersistence() {
        if (!persistenceInitialized) {
            persistenceInitialized = true
            stateFile = File(context.filesDir, "browser_sessions.json")
            loadState()
        }
    }

    private fun saveState() {
        val file = stateFile ?: return
        try {
            val json = buildJsonObject {
                put("selectedIndex", _selectedSessionIndex.value)
                put("tabs", buildJsonArray {
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

    // ── 会话管理 ──

    fun createSession(): BrowserSession? {
        ensurePersistence()
        val current = _sessions.value.toMutableList()
        if (current.size >= MAX_SESSIONS) return null
        val id = (current.maxOfOrNull { it.id } ?: 0) + 1
        val savedUrl = savedUrls.remove(id)
        val session = BrowserSession(id, context, pool = this@BrowserSessionPool)
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
        _sessions.value.forEach { session ->
            val url = session.currentUrl.value
            if (url.isNotEmpty()) savedUrls[session.id] = url
            session.destroy()
        }
        _sessions.value = emptyList()
        _selectedSessionIndex.value = 0
        saveState()
    }

    // ── 状态查询 ──

    fun isBound(): Boolean = selectedSession != null
    fun isEmbeddedMode(): Boolean = _sessions.value.isNotEmpty()
    fun currentUrl(): String? = selectedSession?.webView?.url
    fun currentTitle(): String? = selectedSession?.webView?.title
    fun hasActivePage(): Boolean = selectedSession?.hasActivePage == true

    // ── 动作代理 ──

    fun isOpensNewPage(toolName: String): Boolean = toolName in BrowserToolDefaults.OPENS_NEW_PAGE_TOOLS
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

    fun stopCurrentTask() {
        selectedSession?.stopCurrentTask()
    }

    fun isWithinTaskWindow(): Boolean = selectedSession?.taskWindowActive?.value == true

    var lastActivityDate: Long
        get() = selectedSession?.lastActivityDate ?: System.currentTimeMillis()
        set(value) { selectedSession?.lastActivityDate = value }

    var pendingTaskJob: Job?
        get() = selectedSession?.pendingTaskJob
        set(value) { selectedSession?.pendingTaskJob = value }

    val recentActions: StateFlow<List<String>>
        get() = selectedSession?.recentActions ?: MutableStateFlow(emptyList())

    val screenshotEvent: SharedFlow<Unit>
        get() = selectedSession?.screenshotEvent ?: MutableSharedFlow()

    fun taskWindowActiveFlow(): StateFlow<Boolean> =
        selectedSession?.taskWindowActive ?: MutableStateFlow(false)

    fun recentActionsFlow(): StateFlow<List<String>> =
        selectedSession?.recentActions ?: MutableStateFlow(emptyList())

    // ── 空闲回收 ──

    private var evictionJob: Job? = null
    private val sweepScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun startIdleSweep() {
        if (evictionJob?.isActive == true) return
        evictionJob = sweepScope.launch {
            while (isActive) {
                delay(60_000L)
                evictIdleSessions()
            }
        }
    }

    fun stopIdleSweep() {
        evictionJob?.cancel()
        evictionJob = null
    }

    private fun evictIdleSessions() {
        val now = System.currentTimeMillis()
        val idleTimeoutMs = BrowserConfig.idleTimeoutMs
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

    fun releaseAll() {
        stopIdleSweep()
        releaseAllSessions()
    }

    // ── 头戴模式 ──

    private val streamDedupe = mutableMapOf<String, StreamMark>()
    private data class StreamMark(val url: String?, val atMs: Long)

    private val bindLock = Any()
    private var bindDeferred: CompletableDeferred<Unit> = CompletableDeferred()

    fun bindHeadless(callerConvId: String, webView: WebView): Boolean {
        synchronized(bindLock) {
            if (_sessions.value.isNotEmpty()) return false
            val session = BrowserSession(1, webView.context, pool = this@BrowserSessionPool)
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
        val appContext = webView.context.applicationContext ?: return

        val currentUrl = runCatching {
            withContext(Dispatchers.Main) { webView.url }
        }.getOrNull()

        val now = System.currentTimeMillis()
        val lastMark = synchronized(bindLock) { streamDedupe["headless"] }
        if (currentUrl != null && currentUrl == lastMark?.url &&
            (now - lastMark.atMs) < STREAM_DEDUPE_WINDOW_MS
        ) { return }

        delay(PAINT_SETTLE_MS)

        data class Capture(val path: String, val url: String?)
        val capture = runCatching {
            withContext(Dispatchers.Main) {
                val w = webView.width.coerceAtLeast(1)
                val h = webView.height.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                val canvas = Canvas(bitmap)
                webView.draw(canvas)
                val cacheDir = File(appContext.cacheDir, STREAM_CACHE_SUBDIR).apply { mkdirs() }
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

    // ── 截图 ──

    suspend fun captureLiveSnapshot(): Bitmap? = selectedSession?.captureLiveSnapshot()

    // ── 下载逻辑 ──

    /**
     * 处理 blob: 下载（通过 JS bridge 读取）。
     */
    fun handleBlobDownload(blobUrl: String, filename: String) {
        startDownload(blobUrl, filename)
        val session = selectedSession ?: return
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
     * 在 WebView 中设置 DownloadListener。
     * 由 BrowserSession 在 init 中通过回调调用。
     */
    fun setupDownloadListener(webView: WebView) {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
            when {
                url.startsWith("blob:") -> handleBlobDownload(url, filename)
                else -> {
                    startDownload(url, filename, contentLength)
                    Thread {
                        try {
                            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 15_000
                            conn.readTimeout = 30_000
                            conn.instanceFollowRedirects = true
                            userAgent?.takeIf { it.isNotEmpty() }?.let { conn.setRequestProperty("User-Agent", it) }
                            val total = if (contentLength > 0) contentLength else conn.contentLengthLong
                            val workspaceDir = File(webView.context.filesDir, "workspace").apply { mkdirs() }
                            val dest = File(workspaceDir, filename).let { f ->
                                var i = 1; var file = f
                                while (file.exists()) { file = File(workspaceDir, "${filename.substringBeforeLast('.')}-$i.${filename.substringAfterLast('.')}"); i++ }
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

    // ── Viewport 管理 ──

    /**
     * 设置全局 Viewport，并应用到当前会话。
     */
    fun setGlobalViewport(width: Int, height: Int) {
        BrowserConfig.setCustomViewport(width, height)
        val session = selectedSession ?: return
        if (width > 0 && height > 0) {
            session.applyViewport(width, height)
        }
    }

    // ── 兼容别名 ──

    fun createTab(): BrowserSession? = createSession()
    fun selectTab(index: Int) { selectSession(index) }
    fun closeTab(index: Int) { closeSession(index) }
    fun releaseAllTabs() { releaseAllSessions() }
    fun ensureTab(): BrowserSession {
        return selectedSession ?: createSession() ?: error("Failed to create session")
    }
}
