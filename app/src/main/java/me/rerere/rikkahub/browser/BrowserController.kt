package me.rerere.rikkahub.browser

import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 浏览器控制器 —— Facade，向后兼容。
 *
 * 内部委托给 [BrowserConfig]（全局配置）和 [BrowserSessionPool]（会话池）。
 * 所有公开 API 保持不变，调用方零改动。
 */
object BrowserController {

    private const val TAG = "BrowserController"

    /**
     * 懒初始化会话池。首次调用任何需要池的方法时创建。
     * 使用 applicationContext 避免 Activity 泄漏。
     */
    @Volatile
    private var _pool: BrowserSessionPool? = null

    private fun pool(context: Context): BrowserSessionPool {
        val existing = _pool
        if (existing != null) return existing
        return synchronized(this) {
            _pool?.let { return@synchronized it }
            BrowserSessionPool(context.applicationContext).also { p ->
                _pool = p
                // 同步之前设置的 onShowEmbeddedRequest
                _onShowEmbeddedRequest?.let { p.onShowEmbeddedRequest = it }
            }
        }
    }

    private fun requirePool(): BrowserSessionPool =
        _pool ?: error("BrowserSessionPool not initialized. Call createSession(context) first.")

    // ── 全局配置（委托给 BrowserConfig） ──

    var singleTaskTimeoutMs: Long
        get() = BrowserConfig.singleTaskTimeoutMs
        set(v) { BrowserConfig.singleTaskTimeoutMs = v }

    var perToolTimeoutMs: Long
        get() = BrowserConfig.perToolTimeoutMs
        set(v) { BrowserConfig.perToolTimeoutMs = v }

    var searchEngineIndex: Int
        get() = BrowserConfig.searchEngineIndex
        set(v) { BrowserConfig.searchEngineIndex = v }

    fun currentSearchEngineUrlTemplate(): String = BrowserConfig.currentSearchEngineUrlTemplate()

    var desktopMode: Boolean
        get() = BrowserConfig.desktopMode
        set(v) { BrowserConfig.desktopMode = v }

    const val DESKTOP_UA = BrowserConfig.DESKTOP_UA

    var mobileUA: String?
        get() = BrowserConfig.mobileUA
        set(v) { BrowserConfig.mobileUA = v }

    val customViewportWidth: StateFlow<Int> get() = BrowserConfig.customViewportWidth
    val customViewportHeight: StateFlow<Int> get() = BrowserConfig.customViewportHeight

    fun resolvedViewportSize(): Pair<Int, Int> = BrowserConfig.resolvedViewportSize()
    fun defaultViewportForUA(profile: UserAgentProfile): Pair<Int, Int> = BrowserConfig.defaultViewportForUA(profile)
    fun hasCustomViewport(): Boolean = BrowserConfig.hasCustomViewport()

    var idleTimeoutMs: Long
        get() = BrowserConfig.idleTimeoutMs
        set(v) { BrowserConfig.idleTimeoutMs = v }

    fun setIdleTimeoutMinutes(minutes: Int) = BrowserConfig.setIdleTimeoutMinutes(minutes)

    // ── 会话池（委托给 BrowserSessionPool，未初始化时返回空值） ──

    private val emptySessions = MutableStateFlow<List<BrowserSession>>(emptyList())
    private val emptyIndex = MutableStateFlow(0)

    val sessions: StateFlow<List<BrowserSession>>
        get() = _pool?.sessions ?: emptySessions

    val selectedSessionIndex: StateFlow<Int>
        get() = _pool?.selectedSessionIndex ?: emptyIndex

    val selectedSession: BrowserSession?
        get() = _pool?.selectedSession

    val historyStore: BrowserHistoryStore
        get() = _pool?.historyStore ?: BrowserHistoryStore()

    val historyFlow: StateFlow<List<BrowserHistoryEntry>>
        get() = _pool?.historyFlow ?: MutableStateFlow(emptyList())

    val downloadManager: BrowserDownloadManager
        get() = _pool?.downloadManager ?: BrowserDownloadManager()

    val downloadsFlow: StateFlow<List<BrowserDownloadManager.DownloadState>>
        get() = _pool?.downloadsFlow ?: MutableStateFlow(emptyList())

    val recentActions: StateFlow<List<String>>
        get() = _pool?.recentActions ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())

    val screenshotEvent: SharedFlow<Unit>
        get() = _pool?.screenshotEvent ?: kotlinx.coroutines.flow.MutableSharedFlow()

    val tabs: StateFlow<List<BrowserSession>>
        get() = sessions

    val selectedTab: BrowserSession?
        get() = selectedSession

    val selectedTabIndex: StateFlow<Int>
        get() = selectedSessionIndex

    // ── 事件回调 ──

    @Volatile
    private var _onShowEmbeddedRequest: (() -> Unit)? = null

    var onShowEmbeddedRequest: (() -> Unit)?
        get() = _pool?.onShowEmbeddedRequest ?: _onShowEmbeddedRequest
        set(v) {
            _onShowEmbeddedRequest = v
            _pool?.onShowEmbeddedRequest = v
        }

    // ── 会话管理 ──

    fun createSession(context: Context): BrowserSession? {
        return pool(context).createSession()
    }

    fun selectSession(index: Int) {
        _pool?.selectSession(index)
    }

    fun closeSession(index: Int) {
        _pool?.closeSession(index)
    }

    fun releaseAllSessions() {
        _pool?.releaseAllSessions()
    }

    // ── 历史记录 ──

    fun addHistoryStatic(url: String, title: String) {
        _pool?.addHistoryStatic(url, title)
    }

    fun searchHistory(query: String): List<BrowserHistoryEntry> =
        requirePool().searchHistory(query)

    fun uniqueHistoryDomains(): List<String> =
        requirePool().uniqueHistoryDomains()

    fun clearHistory() {
        _pool?.clearHistory()
    }

    fun removeHistoryEntry(url: String) {
        _pool?.removeHistoryEntry(url)
    }

    // ── 下载管理 ──

    fun startDownload(url: String, filename: String, totalBytes: Long = -1L) {
        _pool?.startDownload(url, filename, totalBytes)
    }

    fun updateDownloadProgress(url: String, bytesDone: Long, totalBytes: Long) {
        _pool?.updateDownloadProgress(url, bytesDone, totalBytes)
    }

    fun finishDownload(url: String, formattedSize: String) {
        _pool?.finishDownload(url, formattedSize)
    }

    fun unreadDownloadCount(): Int = _pool?.unreadDownloadCount() ?: 0

    fun clearCompletedDownloads() {
        _pool?.clearCompletedDownloads()
    }

    fun handleBlobDownload(blobUrl: String, filename: String) {
        _pool?.handleBlobDownload(blobUrl, filename)
    }

    fun setupDownloadListener(webView: WebView) {
        _pool?.setupDownloadListener(webView)
    }

    // ── 状态查询 ──

    fun isBound(): Boolean = _pool?.isBound() ?: false
    fun isEmbeddedMode(): Boolean = _pool?.isEmbeddedMode() ?: false
    fun currentUrl(): String? = _pool?.currentUrl()
    fun currentTitle(): String? = _pool?.currentTitle()
    fun hasActivePage(): Boolean = _pool?.hasActivePage() ?: false

    fun isOpensNewPage(toolName: String): Boolean =
        _pool?.isOpensNewPage(toolName) ?: false

    fun isVisualChange(toolName: String): Boolean =
        _pool?.isVisualChange(toolName) ?: false

    // ── 动作代理 ──

    fun appendAction(label: String) {
        _pool?.appendAction(label)
    }

    fun touchActivity() {
        _pool?.touchActivity()
    }

    fun startTaskWindow() {
        _pool?.startTaskWindow()
    }

    fun stopCurrentTask() {
        _pool?.stopCurrentTask()
    }

    fun isWithinTaskWindow(): Boolean = _pool?.isWithinTaskWindow() ?: false

    var lastActivityDate: Long
        get() = _pool?.lastActivityDate ?: System.currentTimeMillis()
        set(value) { _pool?.let { it.lastActivityDate = value } }

    var pendingTaskJob: Job?
        get() = _pool?.pendingTaskJob
        set(value) { _pool?.let { it.pendingTaskJob = value } }

    fun taskWindowActiveFlow(): StateFlow<Boolean> =
        _pool?.taskWindowActiveFlow() ?: kotlinx.coroutines.flow.MutableStateFlow(false)

    fun recentActionsFlow(): StateFlow<List<String>> =
        _pool?.recentActionsFlow() ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())

    internal fun activeWebView(): WebView? = _pool?.activeWebView()

    // ── 兼容别名 ──

    fun createTab(context: Context): BrowserSession? = createSession(context)
    fun selectTab(index: Int) { selectSession(index) }
    fun closeTab(index: Int) { closeSession(index) }
    fun releaseAllTabs() { releaseAllSessions() }
    fun ensureTab(context: Context): BrowserSession {
        return selectedSession ?: createSession(context) ?: error("Failed to create session")
    }

    // ── 空闲回收 ──

    fun startIdleSweep() {
        _pool?.startIdleSweep()
    }

    // ── 头戴模式 ──

    fun bindHeadless(callerConvId: String, webView: WebView): Boolean {
        return pool(webView.context).bindHeadless(callerConvId, webView)
    }

    fun canBindHeadless(callerConvId: String): Boolean =
        _pool?.canBindHeadless(callerConvId) ?: true

    fun unbindHeadless(callerConvId: String) {
        _pool?.unbindHeadless(callerConvId)
    }

    fun clearSession(callerConvId: String) {
        _pool?.clearSession(callerConvId)
    }

    suspend fun awaitBind(timeoutMs: Long = 5_000L): Boolean =
        _pool?.awaitBind(timeoutMs) ?: false

    suspend fun streamScreenshotIfHeadless(actionLabel: String) {
        _pool?.streamScreenshotIfHeadless(actionLabel)
    }

    // ── 截图 ──

    suspend fun captureLiveSnapshot(): Bitmap? = _pool?.captureLiveSnapshot()

    // ── Viewport 管理 ──

    fun setGlobalViewport(width: Int, height: Int) {
        _pool?.setGlobalViewport(width, height)
    }

    // ── 串行锁 ──

    suspend fun <T> withSessionLock(sessionId: Int, action: suspend () -> T): T =
        requirePool().withSessionLock(sessionId, action)

    // ── 错误信封 ──

    fun notOpenEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_not_open")
        put("recovery", "Call browser_open with a URL to launch the browser before invoking this tool.")
    }

    fun taskTimeoutEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_task_timeout")
        put("recovery", "The browser task window has expired. Call browser_open to start a new task.")
    }

    fun sessionLostEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_session_lost")
        put("recovery", "The headless browser session ended (the calling foreground service was killed). Ask the user to retry.")
    }

    fun bindBusyEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_busy")
        put("recovery", "Another conversation is currently driving the browser. Wait for it to finish (the inUse flag will auto-clear 15s after the last tool call), then retry browser_open.")
    }
}

object BrowserControllerHandle {

    /**
     * 在串行锁保护下执行浏览器操作。
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
    val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
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
