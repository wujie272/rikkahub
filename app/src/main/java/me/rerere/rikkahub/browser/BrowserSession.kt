package me.rerere.rikkahub.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 浏览器会话
 *
 * 管理单个 WebView 及其所有状态（URL、标题、加载状态、导航能力、动作日志、截图事件）。
 * 非单例，每个标签页 / 头戴会话一个独立实例。
 * 由 [BrowserSessionPool] 创建和管理，通过 pool 参数回调池层事件。
 */
class BrowserSession(
    val id: Int,
    context: Context,
    private val pool: BrowserSessionPool,
) {
    companion object {
        private const val TAG = "BrowserSession"
        private const val MAX_RECENT_ACTIONS = 20
        /**
         * 隐式标签页操作后的保活窗口。每次 appendAction 重新刷新倒计时，
         * 超时后自动释放 inUse 状态，遮罩消失。
         */
        private const val IMPLICIT_TAB_GRACE_MS = 15_000L
    }

    // ── WebView ──
    val webView: WebView

    // ── 状态流 ──
    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    // ── 会话级状态（原 BrowserSession） ──
    private val _recentActions = MutableStateFlow<List<String>>(emptyList())
    val recentActions: StateFlow<List<String>> = _recentActions.asStateFlow()

    private val _screenshotEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val screenshotEvent: SharedFlow<Unit> = _screenshotEvent.asSharedFlow()

    private val _inUse = MutableStateFlow(false)
    val taskWindowActive: StateFlow<Boolean> = _inUse.asStateFlow()

    @Volatile
    var inUseGraceJob: Job? = null

    @Volatile
    var pendingTaskJob: Job? = null

    @Volatile
    var lastActivityDate: Long = System.currentTimeMillis()

    /** 导航完成的 deferred，用于 reloadAndWait / loadUrl 等待页面加载完成 */
    private var navigationDeferred: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** 上次应用的 viewport 尺寸，导航前重新应用以保持 viewport 一致性 */
    private var lastAppliedViewport: Pair<Int, Int>? = null

    val hasActivePage: Boolean
        get() {
            val url = _currentUrl.value
            return url.isNotEmpty() && url != "about:blank"
        }

    // ── 初始化 WebView ──
    init {
        webView = WebView(context.applicationContext).apply {
            configureWebViewForRikka(this)

            // ── JS bridge for async Promise resolution ──
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun resolve(result: String) {
                    @Suppress("UNCHECKED_CAST")
                    val d = asyncJsDeferred
                    d?.complete(result)
                }

                @android.webkit.JavascriptInterface
                fun reject(error: String) {
                    @Suppress("UNCHECKED_CAST")
                    val d = asyncJsDeferred
                    d?.complete("{\"error\":\"" + error.replace("\"", "\\\"") + "\"}")
                }
            }, "__rikkahub__")

            // ── 设置 DownloadListener（委托给池层） ──
            pool.setupDownloadListener(this)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    // Google OAuth 路由到 Custom Tab
                    if (GoogleAuthRouter.shouldRouteExternally(url)) {
                        GoogleAuthRouter.openInCustomTab(view?.context ?: context, url)
                        return true
                    }
                    // 对接 ExternalSchemeHandler 处理外部链接
                    if (BrowserExternalSchemeHandler.handleUrl(context, url)) {
                        return true
                    }
                    // 禁止非 file:// 页面跳转到 file://（安全防护）
                    val toFile = request?.url?.scheme.equals("file", ignoreCase = true)
                    if (toFile && view?.url?.startsWith("file:", ignoreCase = true) != true) {
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): android.webkit.WebResourceResponse? {
                    val url = request?.url ?: return null
                    if (url.scheme != "workspace") return null
                    return interceptWorkspaceURL(url)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    _currentUrl.value = url ?: ""
                    _canGoBack.value = view?.canGoBack() ?: false
                    _canGoForward.value = view?.canGoForward() ?: false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    _currentUrl.value = url ?: ""
                    _canGoBack.value = view?.canGoBack() ?: false
                    _canGoForward.value = view?.canGoForward() ?: false
                    _isLoading.value = false
                    // 完成导航 deferred，解除 reloadAndWait / loadUrl 的等待
                    navigationDeferred?.complete(Unit)
                    navigationDeferred = null
                    if (url != null) {
                        pool.addHistoryStatic(url, _pageTitle.value)
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    _pageTitle.value = title ?: ""
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?,
                ): Boolean {
                    // 处理 target="_blank" 链接：在新标签页中打开
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    val newSession = pool.createSession()
                    if (newSession == null) return false
                    transport.webView = newSession.webView
                    resultMsg?.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView?) {
                    // 处理 window.close()
                    val sessions = pool.sessions.value
                    val idx = sessions.indexOfFirst { it.webView === window }
                    if (idx >= 0) {
                        pool.closeSession(idx)
                    }
                }
            }
        }

        _currentUrl.value = webView.url?.takeIf { it != "about:blank" }.orEmpty()
        _pageTitle.value = webView.title ?: ""
        _canGoBack.value = webView.canGoBack()
        _canGoForward.value = webView.canGoForward()
    }

    // ── 导航 ──

    fun loadUrl(url: String) {
        _isLoading.value = true
        // 导航前重新应用 viewport，避免 intercepted 导航回退到 980px
        lastAppliedViewport?.let { (w, h) -> applyViewport(w, h) }
        webView.loadUrl(url)
    }

    /**
     * 加载带有 viewport meta 的空白页，确保新标签页 viewport 立刻生效。
     */
    fun loadBlankPage() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0">
            </head>
            <body></body>
            </html>
        """.trimIndent()
        _isLoading.value = true
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    /**
     * 重新加载并等待页面完成 reloadAndWait。
     */
    suspend fun reloadAndWait(timeoutMs: Long = 30_000L): Boolean {
        val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
        navigationDeferred = deferred
        _isLoading.value = true
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (navigationDeferred === deferred) {
                _isLoading.value = false
                deferred.complete(Unit)
                navigationDeferred = null
            }
        }
        handler.postDelayed(timeoutRunnable, timeoutMs)
        webView.reload()
        return try {
            deferred.await()
            true
        } finally {
            handler.removeCallbacks(timeoutRunnable)
        }
    }

    fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun goForward() {
        if (webView.canGoForward()) webView.goForward()
    }

    fun reload() {
        webView.reload()
    }

    fun stopLoading() {
        webView.stopLoading()
        _isLoading.value = false
    }

    // ── UA / Viewport ──

    fun setUserAgent(ua: String) {
        webView.settings.userAgentString = ua
    }

    fun applyViewport(cssWidth: Int, cssHeight: Int) {
        lastAppliedViewport = cssWidth to cssHeight
        val density = webView.resources.displayMetrics.density
        val w = ((cssWidth * density).toInt()).coerceAtLeast(1)
        val h = ((cssHeight * density).toInt()).coerceAtLeast(1)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, w, h)
    }

    // ── 销毁 ──
    fun destroy() {
        // 取消所有协程，防止 evictionScope 持有 session 引用导致泄漏
        inUseGraceJob?.cancel()
        inUseGraceJob = null
        pendingTaskJob?.cancel()
        pendingTaskJob = null
        evictionScope.cancel()
        _inUse.value = false
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "destroy failed: ${e.message}")
        }
    }

    // ── 动作日志 ──
    // ── workspace:// URL 拦截 ──

    /**
     * 拦截 workspace:// URL 并从 workspace 目录返回文件内容。
     * workspace://hello.html → filesDir/workspace/hello.html
     */
    private fun interceptWorkspaceURL(uri: android.net.Uri): android.webkit.WebResourceResponse? {
        try {
            val path = uri.path ?: return null
            val workspaceDir = java.io.File(webView.context.filesDir, "workspace")
            val localFile = java.io.File(workspaceDir, path.trimStart('/'))
            if (!localFile.exists() || !localFile.isFile) {
                return android.webkit.WebResourceResponse(
                    "text/plain", "UTF-8", 404, "Not Found",
                    emptyMap(),
                    "File not found: $path".byteInputStream()
                )
            }
            val mimeType = guessMimeType(localFile.name)
            return android.webkit.WebResourceResponse(mimeType, "UTF-8", 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*"),
                localFile.inputStream()
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "workspace:// intercept error: ${e.message}")
            return null
        }
    }

    private fun guessMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "txt", "md" -> "text/plain"
            "xml" -> "text/xml"
            else -> "application/octet-stream"
        }
    }
    fun appendAction(label: String) {
        lastActivityDate = System.currentTimeMillis()
        // 工具调用时刷新保活倒计时，防止遮罩提前消失
        if (_inUse.value) armInUseGraceRelease()
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        val current = _recentActions.value
        val next = (listOf(trimmed) + current).take(MAX_RECENT_ACTIONS)
        _recentActions.value = next
        _screenshotEvent.tryEmit(Unit)
    }

    // ── 任务窗口（自动过期）──

    private val evictionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun startTaskWindow() {
        _inUse.value = true
        armInUseGraceRelease()
    }

    /**
     * 自动过期倒计时，每次工具调用（appendAction）重新刷新。
     * 超时后 inUse = false → isAgentBusy = false → 遮罩消失。
     */
    private fun armInUseGraceRelease() {
        inUseGraceJob?.cancel()
        inUseGraceJob = evictionScope.launch {
            delay(IMPLICIT_TAB_GRACE_MS)
            _inUse.value = false
            inUseGraceJob = null
        }
    }

    fun stopCurrentTask() {
        pendingTaskJob?.cancel()
        pendingTaskJob = null
        inUseGraceJob?.cancel()
        inUseGraceJob = null
        _inUse.value = false
        appendAction("AI task stopped by user")
    }

    // ── 截图 ──
    // ── JS Bridge 异步 Promise 支持 ──
    private var asyncJsDeferred: CompletableDeferred<String>? = null

    /**
     * 通过 JS bridge 执行异步 JavaScript（Promise 结果通过 bridge 回传）。
     */
    suspend fun evaluateAsyncJs(js: String, timeoutMs: Long = 30_000L): String? {
        val deferred = CompletableDeferred<String>()
        asyncJsDeferred = deferred
        val wrapped = """
            (async function(){
                try {
                    var __v__ = await ($js);
                    if (__v__ === undefined || __v__ === null) {
                        __rikkahub__.resolve('null');
                    } else if (typeof __v__ === 'object') {
                        __rikkahub__.resolve(JSON.stringify(__v__));
                    } else {
                        __rikkahub__.resolve(String(__v__));
                    }
                } catch(e) {
                    __rikkahub__.reject(e && e.message ? e.message : String(e));
                }
            })();
        """.trimIndent()
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(wrapped, null)
        }
        val raw = withTimeoutOrNull(timeoutMs) { deferred.await() }
        asyncJsDeferred = null
        return raw
    }
    /**
     * 截图当前 WebView 内容 captureWebViewBitmap。
     * 关键：WebView 可能未挂载到窗口（池化管理），width/height 为 0，
     * 此时需要手动测量布局后再截图，否则 bitmap 空或 1×1。
     */
    suspend fun captureLiveSnapshot(): Bitmap? = withContext(Dispatchers.Main) {
        try {
            var w = webView.width
            var h = webView.height
            if (w <= 0 || h <= 0) {
                // 池中 WebView 未挂载到窗口，手动测量布局
                val density = webView.resources.displayMetrics.density
                val targetW = (412 * density).toInt().coerceAtLeast(1)
                val targetH = (915 * density).toInt().coerceAtLeast(1)
                webView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(targetW, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(targetH, android.view.View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, targetW, targetH)
                w = targetW
                h = targetH
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            bitmap
        } catch (e: Exception) {
            android.util.Log.w(TAG, "captureLiveSnapshot failed: ${e.message}")
            null
        }
    }
}
