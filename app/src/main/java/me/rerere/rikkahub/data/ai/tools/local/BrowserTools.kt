package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.browser.BrowserController
import me.rerere.rikkahub.browser.BrowserControllerHandle
import me.rerere.rikkahub.browser.BrowserDiffHelper
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.browser.ReadabilityRunner.runReadability
import me.rerere.rikkahub.browser.awaitReadyState
import me.rerere.rikkahub.browser.evaluateJavascriptAsync
import java.io.File
import java.io.FileOutputStream

/**
 * Pass 2 of the in-app Browser feature: LLM-callable tool factories that drive the
 * BrowserActivity's WebView through [BrowserControllerHandle.withController].
 *
 * Every tool wraps its dispatch in [withTimeoutOrNull] (30 s per the spec's "every tool
 * MUST have a hard timeout" rule); every state-changing write tool also calls
 * [awaitReadyState] post-action so the next read tool sees the new page.
 *
 * Tool registration is gated by [me.rerere.rikkahub.browser.BrowserPreferences] in
 * [me.rerere.rikkahub.data.ai.tools.LocalTools.getTools] — toggling a tool off in
 * Settings → Browser unregisters it entirely, so YOLO can't accidentally run a tool the
 * user has explicitly disabled.
 */

private const val MAX_SCREENSHOT_HEIGHT_PX = 32768
private const val SCREENSHOT_CACHE_SUBDIR = "browser-shots"

/**
 * Hard cap on the string browser_eval_js puts in its result envelope. Matches the 64 KB
 * ceiling the read tools (runGetText / runReadHelper) clamp page text/HTML to, so an eval of
 * something like `document.body.outerHTML` can't bloat the turn with megabytes of payload.
 */
private const val EVAL_JS_MAX_RESULT_CHARS = 64 * 1024

/**
 * Per-tool timeout budget every browser tool wraps its dispatch in. User-configurable via
 * Settings → Browser (GitHub issue #4) — resolved fresh on each tool call from
 * [BrowserController.perToolTimeoutMs], which [me.rerere.rikkahub.browser.BrowserPreferences]
 * keeps in sync with the persisted value.
 */
internal val toolTimeoutMs: Long get() = BrowserController.perToolTimeoutMs



// ---- Common envelope helpers --------------------------------------------------------------

/**
 * 视觉变化动作（对标 OpenMinis BrowserAction.visualChangeActions）。
 * 这些动作执行后应保存一帧截图，供 ToolDetailSheet 显示。
 */
private val VISUAL_CHANGE_ACTIONS: Set<String> = BrowserToolDefaults.VISUAL_CHANGE_TOOLS

internal fun timeoutEnvelope(toolName: String): JsonObject = buildJsonObject {
    put("error", "tool_timeout")
    put("tool", toolName)
    put("recovery", "The browser tool exceeded its $toolTimeoutMs-ms budget. Retry, or simplify the selector.")
}

internal fun missingArgEnvelope(name: String, detail: String): JsonObject = buildJsonObject {
    put("error", "missing_$name")
    put("detail", detail)
}

internal fun textPart(obj: JsonObject): List<UIMessagePart> =
    listOf(UIMessagePart.Text(obj.toString()))

/**
 * 返回文本 + 可选截图（对标 OpenMinis：visualChangeActions 后附带截图）。
 */
private suspend fun textPartWithScreenshot(
    obj: JsonObject,
    toolName: String,
): List<UIMessagePart> {
    val parts = mutableListOf<UIMessagePart>()
    parts.add(UIMessagePart.Text(obj.toString()))
    addScreenshotIfVisualChange(toolName, parts)
    return parts
}

/**
 * JSON-encode a Kotlin string so it can be embedded inside an evaluateJavascript payload
 * as a JS string literal — handles backslashes, quotes, control characters, and Unicode
 * escapes uniformly. Doing this by hand is the canonical XSS-via-LLM-tool footgun; we
 * route through kotlinx.serialization's JsonPrimitive which formats per the JSON spec
 * (also valid JS string syntax).
 */
internal fun jsString(s: String): String = JsonPrimitive(s).toString()

// ---- Read tools ---------------------------------------------------------------------------

fun browserOpenTool(context: Context): Tool = Tool(
    name = BrowserToolDefaults.NAVIGATE,
    description = "Navigate the in-app browser to a URL. If the input is not a URL (no scheme like http://), it's treated as a search query and routed through the configured search engine (DuckDuckGo by default). Returns {success, current_url, title, search_query?}. Resets the per-task 5-minute timer.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The full URL to navigate to (https://...)")
                })
            },
            required = listOf("url"),
        )
    },
    execute = { input ->
        val raw = input.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        // 自动检测：非 URL 的输入作为搜索查询处理
        // 对标 OpenMinis：browser_open 支持搜索查询
        val isSearchQuery = raw != null && android.net.Uri.parse(raw.trim()).scheme == null
        val resolvedUrl = if (isSearchQuery) {
            BrowserToolDefaults.buildSearchUrl(raw, BrowserController.currentSearchEngineUrlTemplate())
        } else {
            raw
        }
        val url = resolvedUrl
        // Heuristic exfil-shape check: if the URL's QUERY (not path — CDN asset hashes
        // would false-positive) carries something that looks like an opaque blob, JWT,
        // API key, or credit-card-shaped digit run, attach a warning so the LLM treats
        // the destination with care. Best-effort, not a security boundary.
        val exfilHits = SensitiveContentDetector.scanUrlQuery(url)
        // Scheme allow-list IS a security boundary: the browser WebViews run with
        // allowFileAccess on (skill webview cards open file:// pages there), so a
        // model-driven file:// navigation followed by browser_get_text would read
        // app-private files (datastore, DB, known_hosts) into the conversation —
        // including via prompt injection from a browsed page. file://, content://,
        // javascript:, intent: etc. are therefore rejected at the tool boundary.
        // Scheme-less input is passed through unchanged (pre-existing behaviour).
        val scheme = url?.let { android.net.Uri.parse(it.trim()).scheme?.lowercase() }
        val rawOut = if (url == null) {
            missingArgEnvelope("url", "url is required and must be a non-empty string")
        } else if (scheme != null && scheme !in setOf("http", "https", "about")) {
            buildJsonObject {
                put("error", "scheme_not_allowed")
                put("detail", "browser_open only accepts http(s) and about: URLs; got scheme '$scheme'")
            }
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                // 嵌入模式：通知 ChatPage 弹出 Sheet，等待 WebView 绑定
                // 对标 OpenMinis：先创建 WebView，再通知 UI 显示 Sheet
                // 对标 OpenMinis BrowserTabPool：创建新标签页
                withContext(Dispatchers.Main) {
                    BrowserController.createTab(context)
                }
                BrowserController.startTaskWindow()
                BrowserController.onShowEmbeddedRequest?.invoke()
                BrowserController.appendAction("Open: $url")
                val result = BrowserControllerHandle.withController {
                    withContext(Dispatchers.Main) { webView.loadUrl(url) }
                    webView.awaitReadyState(8_000L)
                    buildJsonObject {
                        put("success", true)
                        put("current_url", webView.url ?: url)
                        put("title", webView.title.orEmpty())
                        if (isSearchQuery) {
                            put("search_query", raw)
                            put("search_engine", BrowserToolDefaults.SEARCH_ENGINES.getOrNull(BrowserController.searchEngineIndex)?.name ?: "Unknown")
                        }
                    }
                }
                result
            } ?: timeoutEnvelope(BrowserToolDefaults.NAVIGATE)
        }
        val out = if (exfilHits.isEmpty() ||
            rawOut["success"]?.jsonPrimitive?.booleanOrNull != true) rawOut
        else buildJsonObject {
            rawOut.forEach { (k, v) -> put(k, v) }
            put(
                "warning",
                "URL query string carries content shaped like sensitive data " +
                    "(${exfilHits.joinToString { it.name.lowercase() }}). " +
                    "Verify with the user that they intended to send this " +
                    "to ${rawOut["current_url"]?.jsonPrimitive?.contentOrNull ?: url} " +
                    "before relying on the result, and do NOT echo the value back."
            )
        }
        textPartWithScreenshot(out, BrowserToolDefaults.NAVIGATE)
    },
)

fun browserCurrentUrlTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_PAGE_INFO,
    description = "Return the browser's current URL and page title. {url, title}. browser_not_open if the browser isn't open.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                buildJsonObject {
                    put("url", webView.url.orEmpty())
                    put("title", webView.title.orEmpty())
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.GET_PAGE_INFO)
        textPart(out)
    },
)

fun browserScreenshotTool(context: Context): Tool = Tool(
    name = BrowserToolDefaults.SCREENSHOT,
    description = "Capture the visible viewport of the browser as a vision attachment. Use browser_get_text first if you only need the page's text — screenshots cost vision tokens. full_page=true stretches the viewport to the document height before capture (capped at 32768px). Returns metadata including image dimensions, viewport stats, and scroll position.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("full_page", buildJsonObject {
                    put("type", "boolean")
                    put("description", "When true, capture the entire scrollable page by temporarily stretching the viewport to document.scrollHeight (height-capped at 32768 px). Default false captures only the current viewport.")
                })
            },
        )
    },
    execute = { input ->
        val fullPage = input.jsonObject["full_page"]?.jsonPrimitive?.booleanOrNull == true
        val parts = mutableListOf<UIMessagePart>()
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                withContext(Dispatchers.Main) {
                    var truncated = false
                    var originalHeightPx = 0
                    var didStretch = false
                    var savedW = 0
                    var savedH = 0

                    if (fullPage) {
                        // Measure full document height in CSS pixels
                        val cssScrollHeight = webView.evaluateJavascriptAsync(
                            "document.documentElement.scrollHeight", 4_000L
                        )?.trim()?.toIntOrNull() ?: 0
                        val density = webView.resources.displayMetrics.density
                        val scrollHeightPx = if (cssScrollHeight > 0) {
                            (cssScrollHeight * density).toInt()
                        } else {
                            webView.height
                        }
                        originalHeightPx = scrollHeightPx
                        val cappedPx = scrollHeightPx.coerceAtMost(MAX_SCREENSHOT_HEIGHT_PX)
                        truncated = scrollHeightPx > MAX_SCREENSHOT_HEIGHT_PX

                        // Eagerize lazy images
                        webView.evaluateJavascriptAsync(
                            """(async () => {
                                document.querySelectorAll('img[loading="lazy"]').forEach(i => i.loading = 'eager');
                                await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
                                return 'ok';
                            })()""", 2_000L
                        )
                        delay(100)

                        // Stretch viewport to full height
                        val (vpW, vpH) = BrowserController.resolvedViewportSize()
                        savedW = vpW; savedH = vpH
                        val cssCappedHeight = (cappedPx / density).toInt().coerceAtLeast(vpH)
                        val session = BrowserController.selectedSession
                        session?.applyViewport(savedW, cssCappedHeight)
                        didStretch = true
                        delay(100)
                    }

                    val width = webView.width.coerceAtLeast(1)
                    val height = webView.height.coerceAtLeast(1).coerceAtMost(MAX_SCREENSHOT_HEIGHT_PX)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    webView.draw(canvas)

                    // Restore viewport if stretched
                    if (didStretch) {
                        val session = BrowserController.selectedSession
                        session?.applyViewport(savedW, savedH)
                    }

                    val cacheDir = File(context.cacheDir, SCREENSHOT_CACHE_SUBDIR).apply { mkdirs() }
                    val outFile = File(cacheDir, "screenshot-${System.currentTimeMillis()}.webp")
                    try {
                        FileOutputStream(outFile).use { os ->
                            bitmap.compress(Bitmap.CompressFormat.WEBP, 85, os)
                        }
                    } finally {
                        bitmap.recycle()
                    }

                    BrowserController.appendAction("Screenshot" + if (fullPage) " (full_page)" else "")

                    // Collect viewport metadata via JS
                    val scrollInfo = webView.evaluateJavascriptAsync(
                        "JSON.stringify({sx:window.scrollX||0,sy:window.scrollY||0,pw:document.documentElement.scrollWidth||0,ph:document.documentElement.scrollHeight||0,vw:window.innerWidth||0,vh:window.innerHeight||0})",
                        2_000L
                    )
                    var scrollX = 0; var scrollY = 0; var pageW = 0; var pageH = 0; var vpW = 0; var vpH = 0
                    try {
                        val info = kotlinx.serialization.json.Json.parseToJsonElement(scrollInfo ?: "{}").jsonObject
                        scrollX = info["sx"]?.jsonPrimitive?.intOrNull ?: 0
                        scrollY = info["sy"]?.jsonPrimitive?.intOrNull ?: 0
                        pageW = info["pw"]?.jsonPrimitive?.intOrNull ?: 0
                        pageH = info["ph"]?.jsonPrimitive?.intOrNull ?: 0
                        vpW = info["vw"]?.jsonPrimitive?.intOrNull ?: 0
                        vpH = info["vh"]?.jsonPrimitive?.intOrNull ?: 0
                    } catch (_: Exception) {}

                    buildJsonObject {
                        put("success", true)
                        put("file_path", outFile.absolutePath)
                        put("width", width)
                        put("height", height)
                        put("viewportWidth", vpW)
                        put("viewportHeight", vpH)
                        put("pageWidth", pageW)
                        put("pageHeight", pageH)
                        put("scrollX", scrollX)
                        put("scrollY", scrollY)
                        if (fullPage) {
                            put("full_page", true)
                            if (originalHeightPx > 0) put("originalHeight", originalHeightPx)
                            if (truncated) put("truncated", true)
                        }
                    }
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.SCREENSHOT)
        out.jsonObject["file_path"]?.jsonPrimitive?.contentOrNull?.let { fp ->
            parts.add(UIMessagePart.Image(url = "file://$fp"))
        }
        parts.add(UIMessagePart.Text(out.toString()))
        parts
    },
)

fun browserGetTextTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_TEXT,
    description = "Returns the main article content via Readability.js by default, falling back to selector-based extraction if Readability fails. Pass extract_mode:'raw' for the unfiltered text. Pass selector (e.g. 'article', 'main', '.content') for explicit scoping — selectors override Readability. max_chars (default 8000) caps the result. Use this BEFORE screenshot if you only need text content. {text, truncated, extract_mode}.",
    parameters = { getTextSchema(defaultMax = 8000) },
    execute = { input -> textPart(runGetText(input)) },
)



/** Valid values for browser_wait_for's `state` arg. `attached` preserves the original behavior. */
private val WAIT_FOR_STATES = setOf("attached", "detached", "visible", "hidden")

/**
 * Build the JS predicate browser_wait_for polls. Returns a self-invoking expression
 * that evaluates to the JS boolean `true` once the wait condition is satisfied.
 *
 *  - `state` decides what "satisfied" means for the element matching [selector]:
 *      attached  — at least one element matches (default; original behavior)
 *      detached  — no element matches
 *      visible   — a matching element is in the DOM AND rendered (offsetParent or a
 *                  non-zero client rect — covers position:fixed which has null offsetParent)
 *      hidden    — no matching element is visible (none in DOM, or all rendered hidden)
 *  - When [containsText] is non-null, the matched element ALSO has to contain that text
 *    (case-sensitive substring of innerText/textContent). For detached/hidden states the
 *    text constraint is ignored — "not there" can't also "contain text".
 *
 * Pure string builder so it stays unit-testable without a WebView.
 */
internal fun buildWaitForPredicate(selector: String, state: String, containsText: String?): String {
    val sel = jsString(selector)
    val txt = containsText?.let { jsString(it) }
    val textCheck = if (txt != null) {
        "function(el){var t=(el.innerText||el.textContent||'');return t.indexOf($txt)!==-1;}"
    } else {
        "function(){return true;}"
    }
    val visibleCheck = "function(el){" +
        "if(el.offsetParent!==null)return true;" +
        "var r=el.getClientRects();return r&&r.length>0;" +
        "}"
    return when (state) {
        "detached" -> "(function(){try{return document.querySelector($sel)===null;}catch(e){return false;}})()"
        "hidden" -> "(function(){try{" +
            "var els=document.querySelectorAll($sel);" +
            "var vis=$visibleCheck;" +
            "for(var i=0;i<els.length;i++){if(vis(els[i]))return false;}" +
            "return true;" +
            "}catch(e){return false;}})()"
        "visible" -> "(function(){try{" +
            "var els=document.querySelectorAll($sel);" +
            "var vis=$visibleCheck;var hasText=$textCheck;" +
            "for(var i=0;i<els.length;i++){if(vis(els[i])&&hasText(els[i]))return true;}" +
            "return false;" +
            "}catch(e){return false;}})()"
        else -> "(function(){try{" + // "attached" (default)
            "var els=document.querySelectorAll($sel);" +
            "var hasText=$textCheck;" +
            "for(var i=0;i<els.length;i++){if(hasText(els[i]))return true;}" +
            "return false;" +
            "}catch(e){return false;}})()"
    }
}

fun browserWaitForTool(): Tool = Tool(
    name = BrowserToolDefaults.WAIT_FOR_DOM_STABLE,
    description = "Pause until a CSS selector reaches a target state. Polls every 200 ms up to timeout_ms (default 10_000). state is one of attached (default — element present in DOM), detached (element gone), visible (present AND rendered), hidden (none rendered). Optional contains_text waits until a matching element contains that text. {found, elapsed_ms}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject {
                put("type", "string")
                put("description", "CSS selector to wait for")
            })
            put("timeout_ms", buildJsonObject {
                put("type", "integer")
                put("description", "Max wait in ms (default 10000, capped at the configured per-tool timeout)")
            })
            put("contains_text", buildJsonObject {
                put("type", "string")
                put("description", "Optional — wait until an element matching the selector contains this text (case-sensitive substring). Ignored for state=detached/hidden.")
            })
            put("state", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("attached"); add("detached"); add("visible"); add("hidden") })
                put("description", "Target state to wait for (default 'attached', the original presence-in-DOM behavior)")
            })
        }, required = listOf("selector"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val rawState = input.jsonObject["state"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val out = when {
            selector == null ->
                missingArgEnvelope("selector", "selector is required and must be a non-empty CSS selector")
            rawState != null && rawState !in WAIT_FOR_STATES ->
                missingArgEnvelope("state", "state must be one of [attached, detached, visible, hidden]")
            else -> {
                val state = rawState ?: "attached"
                val containsText = input.jsonObject["contains_text"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                // Cap the user-supplied timeout at our per-tool budget so the LLM can't ask for a
                // longer wait and starve every other tool call. The withTimeoutOrNull below would
                // catch this anyway, but capping here gives a clean envelope. The cap tracks the
                // user-configured per-tool timeout (Settings → Browser).
                val timeoutMs = (input.jsonObject["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 10_000)
                    .toLong()
                    .coerceIn(200L, toolTimeoutMs)
                withTimeoutOrNull(toolTimeoutMs) {
                    BrowserControllerHandle.withController {
                        val started = System.currentTimeMillis()
                        val deadline = started + timeoutMs
                        val js = buildWaitForPredicate(selector, state, containsText)
                        var found = false

                        // 快速路径：页面已加载完成时，用两次采样确认稳定性
                        // 对标 OpenMinis：readyState=complete 快速检测
                        if (state == "attached" || state == "visible") {
                            val readyState = webView.evaluateJavascriptAsync(
                                "(function(){try{return document.readyState;}catch(e){return '';}})()", 1_500L
                            )
                            if (readyState?.contains("complete") == true) {
                                val first = webView.evaluateJavascriptAsync(js, 1_500L)
                                delay(50)
                                val second = webView.evaluateJavascriptAsync(js, 1_500L)
                                if (first == "true" && second == "true") {
                                    return@withController buildJsonObject {
                                        put("found", true)
                                        put("elapsed_ms", System.currentTimeMillis() - started)
                                        put("state", state)
                                        put("fast_path", true)
                                        if (containsText != null) put("contains_text", containsText)
                                    }
                                }
                            }
                        }

                        while (System.currentTimeMillis() < deadline) {
                            val raw = webView.evaluateJavascriptAsync(js, 1_500L)
                            if (raw == "true") { found = true; break }
                            delay(200)
                        }
                        buildJsonObject {
                            put("found", found)
                            put("elapsed_ms", System.currentTimeMillis() - started)
                            put("state", state)
                            if (containsText != null) put("contains_text", containsText)
                        }
                    }
                } ?: timeoutEnvelope(BrowserToolDefaults.WAIT_FOR_DOM_STABLE)
            }
        }
        textPart(out)
    },
)

// ---- Write tools --------------------------------------------------------------------------

fun browserClickTool(): Tool = Tool(
    name = BrowserToolDefaults.CLICK,
    description = "Click an element matching a CSS selector. Returns the diff between the page before and after the action by default ({added, removed, added_chars, removed_chars, truncated} truncated to 4000 chars total). Pass full:true to skip the diff and get post_click_url only — use when the click navigates to an entirely new page. Waits up to 8 s for readyState=complete.",
    parameters = { selectorWithFullSchema("CSS selector to click") },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val full = parseFullArg(input)
        val out = if (selector == null) {
            missingArgEnvelope("selector", "selector is required and must be a non-empty CSS selector")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    withDiff(full) {
                        val js = """(function(){
                            try {
                                var el = document.querySelector(${jsString(selector)});
                                if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                                el.scrollIntoView({block:'center', inline:'center'});
                                el.click();
                                return JSON.stringify({clicked:true});
                            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                        })()"""
                        val raw = webView.evaluateJavascriptAsync(js)
                        val res = parseJsResult(raw)
                        if (res.containsKey("error")) return@withDiff res
                        webView.awaitReadyState(8_000L)
                        BrowserController.appendAction("Click: $selector")
                        buildJsonObject {
                            put("success", true)
                            put("post_click_url", webView.url.orEmpty())
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.CLICK)
        }
        textPartWithScreenshot(out, BrowserToolDefaults.CLICK)
    },
)

fun browserTypeTool(): Tool = Tool(
    name = BrowserToolDefaults.TYPE,
    description = "Type text into an input/textarea/contenteditable matching a CSS selector. Focuses, optionally clears, sets the value + dispatches an 'input' event so SPA frameworks observe the change. Returns the diff between the page before and after by default; pass full:true to skip the diff. {success, [diff]}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject { put("type","string"); put("description","CSS selector of the input") })
            put("text", buildJsonObject { put("type","string"); put("description","Text to type") })
            put("clear", buildJsonObject { put("type","boolean"); put("description","Clear the field first (default true)") })
            put("full", buildJsonObject { put("type","boolean"); put("description","If true, return the action envelope without the page-text diff (default false)") })
        }, required = listOf("selector","text"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val text = input.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        val clear = input.jsonObject["clear"]?.jsonPrimitive?.booleanOrNull ?: true
        val full = parseFullArg(input)
        val out = when {
            selector == null -> missingArgEnvelope("selector", "selector is required")
            text == null -> missingArgEnvelope("text", "text is required (use empty string to clear)")
            else -> withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    withDiff(full) {
                        // Use both 'input' and 'change' events to satisfy frameworks that listen
                        // to either; React's synthetic event layer needs the native value setter
                        // path which we don't replicate here — covers ~90% of real inputs.
                        val js = """(function(){
                            try {
                                var el = document.querySelector(${jsString(selector)});
                                if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                                el.focus();
                                if (${if (clear) "true" else "false"}) {
                                    if ('value' in el) el.value = '';
                                    else if (el.isContentEditable) el.textContent = '';
                                }
                                if ('value' in el) el.value = (el.value || '') + ${jsString(text)};
                                else if (el.isContentEditable) el.textContent = (el.textContent || '') + ${jsString(text)};
                                el.dispatchEvent(new Event('input', {bubbles:true}));
                                el.dispatchEvent(new Event('change', {bubbles:true}));
                                return JSON.stringify({typed:true});
                            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                        })()"""
                        val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                        if (res.containsKey("error")) return@withDiff res
                        BrowserController.appendAction("Typed into $selector")
                        buildJsonObject { put("success", true) }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.TYPE)
        }
        textPartWithScreenshot(out, BrowserToolDefaults.TYPE)
    },
)

fun browserScrollTool(): Tool = Tool(
    name = BrowserToolDefaults.SCROLL,
    description = "Scroll the page in a direction (up/down/top/bottom). amount is in pixels (default 600, ignored for top/bottom). {success, scroll_y}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("direction", buildJsonObject {
                put("type","string")
                put("enum", buildJsonArray { add("up"); add("down"); add("top"); add("bottom") })
            })
            put("amount", buildJsonObject { put("type","integer"); put("description","Scroll distance in px (default 600)") })
        }, required = listOf("direction"))
    },
    execute = { input ->
        val direction = input.jsonObject["direction"]?.jsonPrimitive?.contentOrNull
        val amount = input.jsonObject["amount"]?.jsonPrimitive?.intOrNull ?: 600
        val out = if (direction == null || direction !in setOf("up", "down", "top", "bottom")) {
            missingArgEnvelope("direction", "direction must be one of [up, down, top, bottom]")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    val js = """(function(){
                        try {
                            switch (${jsString(direction)}) {
                                case 'up': window.scrollBy(0, -$amount); break;
                                case 'down': window.scrollBy(0, $amount); break;
                                case 'top': window.scrollTo(0, 0); break;
                                case 'bottom': window.scrollTo(0, document.body.scrollHeight); break;
                            }
                            return JSON.stringify({scroll_y: Math.round(window.scrollY)});
                        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                    })()"""
                    val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                    if (res.containsKey("error")) return@withController res
                    BrowserController.appendAction("Scroll $direction")
                    buildJsonObject {
                        put("success", true)
                        put("scroll_y", res["scroll_y"]?.jsonPrimitive?.intOrNull ?: 0)
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SCROLL)
        }
        // browser_scroll is in WRITE_TOOLS
        // "screenshots stream after each state-changing action." Stream the post-scroll view so
        // the Telegram user can see the new viewport position (scroll is purely viewport movement
        // — the diff helper won't capture it since body.innerText doesn't change).
        textPartWithScreenshot(out, BrowserToolDefaults.SCROLL)
    },
)


fun navigateTool(context: Context): Tool = browserOpenTool(context)

fun getPageInfoTool(): Tool = browserCurrentUrlTool()

fun screenshotTool(context: Context): Tool = browserScreenshotTool(context)

fun getTextTool(): Tool = browserGetTextTool()

fun getReadableTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_READABLE,
    description = "Extract the main readable content from the current page using Readability.js. Returns the article text, title, and excerpt. Falls back to raw body text if Readability fails. {text, title, excerpt}.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val text = webView.runReadability()
                if (text.isNullOrEmpty()) {
                    buildJsonObject { put("error", "readability_failed") }
                } else {
                    buildJsonObject {
                        put("text", text)
                        put("title", withContext(Dispatchers.Main) { webView.title.orEmpty() })
                    }
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.GET_READABLE)
        textPart(out)
    },
)

fun clickTool(): Tool = browserClickTool()

fun typeTool(): Tool = browserTypeTool()

fun scrollTool(): Tool = browserScrollTool()

fun executeJsTool(): Tool = Tool(
    name = BrowserToolDefaults.EXECUTE_JS,
    description = "Run arbitrary JavaScript in the page and return its last expression. HARDLINE-checked: shell-shaped strings, document.cookie writes, eval/Function constructors, and string-form setTimeout are all blocked at the tool dispatcher BEFORE the JS executes. Always asks for approval; never eligible for 'Always Allow'.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("code", buildJsonObject {
                put("type","string")
                put("description","JavaScript to evaluate. The string returned by the WebView is the value of the last expression, JSON-encoded.")
            })
        }, required = listOf("code"))
    },
    execute = { input ->
        val code = input.jsonObject["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = if (code == null) {
            missingArgEnvelope("code", "code is required")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    val raw = webView.evaluateJavascriptAsync(code, toolTimeoutMs - 1_000L)
                    BrowserController.appendAction("Run JS")
                    val (clipped, truncated) = clipText(raw ?: "null", EVAL_JS_MAX_RESULT_CHARS)
                    buildJsonObject {
                        put("result", clipped)
                        if (truncated) put("truncated", true)
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.EXECUTE_JS)
        }
        textPart(out)
    },
)

fun findElementsTool(): Tool = Tool(
    name = BrowserToolDefaults.FIND_ELEMENTS,
    description = "Find elements matching a CSS selector. Returns a list of elements with their tag, text, attributes, and position. {count, elements: [{tag, text, id, class, href, rect}]}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject {
                put("type", "string")
                put("description", "CSS selector to find elements")
            })
        }, required = listOf("selector"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = if (selector == null) {
            missingArgEnvelope("selector", "selector is required")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    val js = """(function(){
                        try {
                            var els = document.querySelectorAll(${jsString(selector)});
                            var out = [];
                            for (var i=0; i<els.length && out.length<50; i++) {
                                var e = els[i];
                                var r = e.getBoundingClientRect();
                                out.push({
                                    index: i,
                                    tag: (e.tagName||'').toLowerCase(),
                                    text: (e.innerText||e.textContent||'').trim().substring(0,200),
                                    id: e.id||'',
                                    class: (e.className||'').toString().substring(0,100),
                                    href: (e.href||e.getAttribute('href')||'').substring(0,500),
                                    rect: {x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height)}
                                });
                            }
                            return JSON.stringify({count: els.length, shown: out.length, elements: out});
                        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                    })()"""
                    val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                    if (res.containsKey("error")) return@withController res
                    BrowserController.appendAction("Find: $selector")
                    res
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.FIND_ELEMENTS)
        }
        textPart(out)
    },
)

fun hoverTool(): Tool = Tool(
    name = BrowserToolDefaults.HOVER,
    description = "Hover over an element matching a CSS selector. Dispatches mouseenter and mouseover events. {success}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject {
                put("type", "string")
                put("description", "CSS selector of the element to hover over")
            })
        }, required = listOf("selector"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = if (selector == null) {
            missingArgEnvelope("selector", "selector is required")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    val js = """(function(){
                        try {
                            var el = document.querySelector(${jsString(selector)});
                            if (!el) return JSON.stringify({error:'selector_not_found'});
                            el.dispatchEvent(new MouseEvent('mouseover', {bubbles:true}));
                            el.dispatchEvent(new MouseEvent('mouseenter', {bubbles:true}));
                            return JSON.stringify({hovered:true, tag: (el.tagName||'').toLowerCase()});
                        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                    })()"""
                    val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                    if (res.containsKey("error")) return@withController res
                    BrowserController.appendAction("Hover: $selector")
                    buildJsonObject { put("success", true) }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.HOVER)
        }
        textPartWithScreenshot(out, BrowserToolDefaults.HOVER)
    },
)

private fun selectorWithFullSchema(description: String): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", description)
        })
        put("full", buildJsonObject {
            put("type", "boolean")
            put("description", "If true, return the action envelope without the page-text diff (default false)")
        })
    },
    required = listOf("selector"),
)

private fun selectorAndMaxCharsSchema(defaultMax: Int, required: Boolean): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", "CSS selector (default 'body')")
        })
        put("max_chars", buildJsonObject {
            put("type", "integer")
            put("description", "Truncation cap (default $defaultMax)")
        })
    },
    required = if (required) listOf("selector") else null,
)

/**
 * Schema for browser_get_text. Adds the [extract_mode] enum (auto / readability /
 * raw) to the standard selector + max_chars surface. Defaults are documented inline
 * so the LLM doesn't need to hunt through the spec to know the fallback behaviour.
 */
private fun getTextSchema(defaultMax: Int): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", "Optional CSS selector — when set, overrides Readability and reads the selector's innerText directly")
        })
        put("max_chars", buildJsonObject {
            put("type", "integer")
            put("description", "Truncation cap (default $defaultMax)")
        })
        put("extract_mode", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("auto"); add("readability"); add("raw") })
            put("description", "auto (default) tries Readability then falls back; readability forces it; raw uses selector-based innerText")
        })
    },
)


/**
 * Snapshot the page's `document.body.innerText` for the diff-after-action path.
 * Whitespace is collapsed so that incidental layout reflows (e.g. an extra newline
 * inserted by a CSS animation that just landed) don't show up as "added" lines in
 * the diff. Returns the empty string on any JS failure — the diff helper will then
 * treat an empty before-snapshot as "everything is new", which is the conservative
 * call when we can't tell what was there.
 */
private suspend fun BrowserControllerHandle.WithControllerScope.captureBodyText(): String {
    val raw = webView.evaluateJavascriptAsync(
        "(function(){try{return JSON.stringify(document.body.innerText||'');}catch(e){return JSON.stringify('');}})()",
        4_000L,
    ) ?: return ""
    return runCatching {
        val outer = Json.parseToJsonElement(raw)
        val inner = if (outer is JsonPrimitive && outer.isString) outer.contentOrNull.orEmpty() else outer.toString()
        Json.parseToJsonElement(inner).jsonPrimitive.contentOrNull.orEmpty()
    }.getOrElse { "" }
}

/**
 * Token-cost optimisation pass — wrap a state-changing tool's action with a
 * before/after text snapshot so the LLM gets a diff envelope instead of a full
 * page re-read. Returns:
 *  - The action's own envelope unchanged when [full] is true (legacy path).
 *  - The action's envelope merged with `{ "diff": {...} }` when [full] is false.
 *  - An error envelope if the action returned one — diff is skipped on error so we
 *    don't push a stale snapshot when nothing changed because the action failed.
 *
 * The action is responsible for awaiting readyState if it triggers navigation; we
 * snapshot AFTER the action returns to ensure we read the post-action page.
 */
private suspend fun BrowserControllerHandle.WithControllerScope.withDiff(
    full: Boolean,
    action: suspend BrowserControllerHandle.WithControllerScope.() -> JsonObject,
): JsonObject {
    if (full) return action()
    val before = captureBodyText()
    val result = action()
    if (result.containsKey("error")) return result
    val after = captureBodyText()
    return buildJsonObject {
        result.forEach { (k, v) -> put(k, v) }
        put("diff", BrowserDiffHelper.computeDiff(before, after))
    }
}

/**
 * Read the optional `full` arg uniformly across the state-changing tools. Default
 * false → diff path; true → preserve legacy envelope (post_*_url only). The arg
 * name matches the spec verbatim so the LLM has one concept to learn.
 */
private fun parseFullArg(input: kotlinx.serialization.json.JsonElement): Boolean =
    input.jsonObject["full"]?.jsonPrimitive?.booleanOrNull == true

/**
 * Parse the raw string evaluateJavascript returned. Our JS helpers always return a
 * JSON.stringify(...) result, so the raw value is itself a JSON-encoded string (i.e.
 * a literal "..." with internal escapes). Unwrap once to get the real JSON object.
 *
 * Falls back to {error:'js_no_result'} on null and {error:'js_parse_failed'} on a
 * value that can't be parsed — both surface to the LLM cleanly without throwing.
 */
internal fun parseJsResult(raw: String?): JsonObject {
    if (raw == null) return buildJsonObject { put("error", "js_no_result") }
    return runCatching {
        // evaluateJavascript wraps a JS string return value in JSON-quoted form, so
        // raw is "\"{...}\"" — parse the outer quoted string into a Kotlin string,
        // then parse that string as JSON.
        val outer = Json.parseToJsonElement(raw)
        val inner = if (outer is JsonPrimitive && outer.isString) outer.contentOrNull.orEmpty() else outer.toString()
        Json.parseToJsonElement(inner).jsonObject
    }.getOrElse { buildJsonObject { put("error", "js_parse_failed"); put("raw", raw) } }
}

/**
 * Shared body for browser_get_text / browser_get_dom: same selector+max_chars schema,
 * different JS body. The [jsBuilder] returns the JS payload; the helper handles the
 * round-trip + envelope + timeout.
 */
private suspend fun runReadHelper(
    input: kotlinx.serialization.json.JsonElement,
    toolName: String,
    defaultMax: Int,
    jsBuilder: (selector: String, maxChars: Int) -> String,
): JsonObject {
    val selector = (input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }) ?: "body"
    // Clamp max_chars at 100 (anything smaller is unhelpful) and 64 KB (so a runaway
    // model can't tell us to grab a megabyte of HTML).
    val maxChars = (input.jsonObject["max_chars"]?.jsonPrimitive?.intOrNull ?: defaultMax)
        .coerceIn(100, 64 * 1024)
    return withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            parseJsResult(webView.evaluateJavascriptAsync(jsBuilder(selector, maxChars)))
        }
    } ?: timeoutEnvelope(toolName)
}

/**
 * Token-cost optimisation pass — browser_get_text body. Resolves [extract_mode] +
 * [selector] precedence:
 *  - Explicit `selector` arg → skip Readability and use selector-based innerText
 *    (the user knows what they want; we trust the model).
 *  - `extract_mode = "raw"` → selector-based innerText against `body` (current
 *    pre-pass behaviour).
 *  - `extract_mode = "readability"` → force Readability; surface
 *    `{error:"readability_failed"}` if it returns null.
 *  - `extract_mode = "auto"` (default) → try Readability; fall back to
 *    selector-based innerText if it returns null OR less than 200 chars (a
 *    too-short article is often a junk extraction — better to fall back).
 */
private const val READABILITY_MIN_CHARS = 200

private suspend fun runGetText(input: kotlinx.serialization.json.JsonElement): JsonObject {
    val explicitSelector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
    val maxChars = (input.jsonObject["max_chars"]?.jsonPrimitive?.intOrNull ?: 8000)
        .coerceIn(100, 64 * 1024)
    val mode = input.jsonObject["extract_mode"]?.jsonPrimitive?.contentOrNull?.lowercase()
        ?.takeIf { it in setOf("auto", "readability", "raw") } ?: "auto"

    return withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            // Selector arg trumps everything — the model is being explicit, honour it.
            if (explicitSelector != null) {
                return@withController runRawText(explicitSelector, maxChars, mode = "raw_selector")
            }
            when (mode) {
                "raw" -> runRawText("body", maxChars, mode = "raw")
                "readability" -> {
                    val text = webView.runReadability()
                    if (text.isNullOrEmpty()) {
                        buildJsonObject {
                            put("error", "readability_failed")
                            put("recovery", "Try extract_mode:'auto' or pass a specific selector")
                        }
                    } else {
                        buildJsonObject {
                            val (clipped, truncated) = clipText(text, maxChars)
                            put("text", clipped)
                            put("truncated", truncated)
                            put("extract_mode", "readability")
                        }
                    }
                }
                else -> {
                    // auto: Readability first, then selector fallback
                    val text = webView.runReadability()
                    if (!text.isNullOrEmpty() && text.length >= READABILITY_MIN_CHARS) {
                        val (clipped, truncated) = clipText(text, maxChars)
                        buildJsonObject {
                            put("text", clipped)
                            put("truncated", truncated)
                            put("extract_mode", "readability")
                        }
                    } else {
                        runRawText("body", maxChars, mode = "raw_fallback")
                    }
                }
            }
        }
    } ?: timeoutEnvelope(BrowserToolDefaults.GET_TEXT)
}

private suspend fun BrowserControllerHandle.WithControllerScope.runRawText(
    selector: String,
    maxChars: Int,
    mode: String,
): JsonObject {
    val js = """(function(){
        try {
            var el = document.querySelector(${jsString(selector)});
            if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
            var t = (el.innerText || el.textContent || '').replace(/\s+/g,' ').trim();
            var truncated = false;
            if (t.length > $maxChars) { t = t.substring(0, $maxChars); truncated = true; }
            return JSON.stringify({text:t, truncated:truncated});
        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
    })()"""
    val res = parseJsResult(webView.evaluateJavascriptAsync(js))
    return if (res.containsKey("error")) res else buildJsonObject {
        res.forEach { (k, v) -> put(k, v) }
        put("extract_mode", mode)
    }
}

private fun clipText(text: String, maxChars: Int): Pair<String, Boolean> =
    if (text.length <= maxChars) text to false
    else text.substring(0, maxChars) to true

/**
 * 保存截图到缓存文件，返回可用于 UIMessagePart.Image 的 file:// URL。
 * 对标 OpenMinis BrowserUseManager 中 visualChangeActions 后的截图保存逻辑。
 */
private suspend fun saveScreenshotToFile(context: Context? = null): String? {
    // 对标 OpenMinis：先等页面稳定再截图
    delay(300)
    // 等待 WebView attach 到 window（首次 navigate 时 sheet 可能还没渲染完）
    // 对齐 OpenMinis：attachSnapshot 在同一个类实例内直接访问 webView，
    // 但 RikkaHub 的 saveScreenshotToFile 在 withController 作用域外执行，
    // 需要确保 WebView 已挂载到窗口，否则 draw(canvas) 可能空白
    var waited = 0L
    while (waited < 3000) {
        val session = BrowserController.selectedSession
        if (session != null) {
            val attached = withContext(Dispatchers.Main) { session.webView.isAttachedToWindow }
            if (attached) break
        }
        delay(200)
        waited += 200
    }
    return withContext(Dispatchers.Main) {
        val session = BrowserController.selectedSession ?: return@withContext null
        val webView = session.webView
        val ctx = context ?: webView.context.applicationContext ?: return@withContext null
        try {
            // 对标 OpenMinis captureWebViewBitmap：处理宽高为 0 的情况
            var w = webView.width
            var h = webView.height
            if (w <= 0 || h <= 0) {
                // 对齐 OpenMinis：使用 profile viewport 尺寸而非硬编码 1080x1920
                // OpenMinis 用 currentProfile.viewportSize（412x915 或 1280x800）
                val (vpW, vpH) = BrowserController.resolvedViewportSize()
                val density = webView.resources.displayMetrics.density
                val targetW = (vpW * density).toInt().coerceAtLeast(1)
                val targetH = (vpH * density).toInt().coerceAtLeast(1)
                webView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(targetW, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(targetH, android.view.View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, targetW, targetH)
                w = targetW; h = targetH
            }
            h = h.coerceAtMost(MAX_SCREENSHOT_HEIGHT_PX)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            val cacheDir = File(ctx.cacheDir, SCREENSHOT_CACHE_SUBDIR).apply { mkdirs() }
            val out = File(cacheDir, "visual-${System.currentTimeMillis()}.webp")
            try {
                FileOutputStream(out).use { os ->
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 85, os)
                }
                "file://${out.absolutePath}"
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            android.util.Log.w("BrowserTools", "saveScreenshotToFile failed: ${e.message}")
            null
        }
    }
}

/**
 * 如果当前工具是视觉变化动作，保存截图并添加到输出。
 * 对标 OpenMinis: visualChangeActions 后 result.imageFilePath = saveScreenshot()
 */
private suspend fun addScreenshotIfVisualChange(
    toolName: String,
    parts: MutableList<UIMessagePart>,
) {
    if (toolName !in VISUAL_CHANGE_ACTIONS) return
    val url = saveScreenshotToFile()
    if (url != null) {
        parts.add(UIMessagePart.Image(url = url))
    }
}

/**
 * Factory dispatch for a given browser tool name.
 * All browser tools run in embedded mode (in-chat BrowserPreviewSheet).
 */
fun createBrowserTool(
    toolName: String,
    context: Context,
): Tool? = when (toolName) {
    // 对标 OpenMinis BrowserAction
    BrowserToolDefaults.NAVIGATE -> navigateTool(context)
    BrowserToolDefaults.GET_PAGE_INFO -> getPageInfoTool()
    BrowserToolDefaults.SCREENSHOT -> screenshotTool(context)
    BrowserToolDefaults.GET_TEXT -> getTextTool()
    BrowserToolDefaults.GET_READABLE -> getReadableTool()
    BrowserToolDefaults.CLICK -> clickTool()
    BrowserToolDefaults.TYPE -> typeTool()
    BrowserToolDefaults.SCROLL -> scrollTool()
    BrowserToolDefaults.EXECUTE_JS -> executeJsTool()
    BrowserToolDefaults.FIND_ELEMENTS -> findElementsTool()
    BrowserToolDefaults.HOVER -> hoverTool()
    BrowserToolDefaults.WAIT_FOR_DOM_STABLE -> browserWaitForTool()
    // 补齐的 10 个工具（对标 OpenMinis BrowserAction）
    BrowserToolDefaults.GET_BACKBONE -> getBackboneTool()
    BrowserToolDefaults.FETCH -> fetchTool()
    BrowserToolDefaults.GET_COOKIES -> getCookiesTool()
    BrowserToolDefaults.SET_COOKIES -> setCookiesTool()
    BrowserToolDefaults.LIST_TABS -> listTabsTool()
    BrowserToolDefaults.NEW_TAB -> newTabTool(context)
    BrowserToolDefaults.CLOSE_TAB -> closeTabTool()
    BrowserToolDefaults.SET_USER_AGENT -> setUserAgentTool()
    BrowserToolDefaults.SET_VIEWPORT -> setViewportTool()
    BrowserToolDefaults.SCROLL_AND_COLLECT -> scrollAndCollectTool()
    else -> null
}
