package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.browser.BrowserController
import me.rerere.rikkahub.browser.BrowserControllerHandle
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.browser.BrowserUseJS
import me.rerere.rikkahub.browser.awaitReadyState
import me.rerere.rikkahub.browser.evaluateJavascriptAsync
import java.net.URI

// ---- 新增工具（对标 OpenMinis BrowserAction，补齐 10 个缺失工具） ----

/**
 * 获取页面骨架（DOM 结构摘要）。
 * 对标 OpenMinis BrowserUseManager.getBackbone
 */
fun getBackboneTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_BACKBONE,
    description = "Get the page's DOM backbone (structural summary of elements). Returns a tree of tag, id, class, and text. {backbone, nodeCount, depth}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("max_depth", buildJsonObject {
                put("type", "integer")
                put("description", "Maximum DOM tree depth (default 5)")
            })
        })
    },
    execute = { input ->
        val maxDepth = input.jsonObject["max_depth"]?.jsonPrimitive?.intOrNull ?: 5
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val js = BrowserUseJS.getBackbone(maxDepth)
                val raw = webView.evaluateJavascriptAsync(js)
                parseJsResult(raw)
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.GET_BACKBONE)
        textPart(out)
    },
)

/**
 * 通过页面上下文发起 HTTP 请求（可绕过 CORS）。
 * 对标 OpenMinis BrowserUseManager.fetch
 * 使用同步 XMLHttpRequest 避免 Promise 兼容问题。
 */
fun fetchTool(): Tool = Tool(
    name = BrowserToolDefaults.FETCH,
    description = "Fetch a URL from within the page context (bypasses CORS for same-origin requests). Returns the response body, status code, and content type. {success, status, body, size}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("url", buildJsonObject {
                put("type", "string")
                put("description", "URL to fetch")
            })
        }, required = listOf("url"))
    },
    execute = { input ->
        val url = input.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = if (url == null) {
            missingArgEnvelope("url", "url is required")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                val session = BrowserController.selectedSession
                if (session == null) {
                    return@withTimeoutOrNull buildJsonObject {
                        put("error", "browser_not_open")
                        put("recovery", "Call browser_open first")
                    }
                }
                val js = BrowserUseJS.fetch(url)
                val raw = session.evaluateAsyncJs(js, 60_000L)
                parseJsResult(raw)
            } ?: timeoutEnvelope(BrowserToolDefaults.FETCH)
        }
        textPart(out)
    },
)

/**
 * 获取当前页面的 Cookies。
 * 对标 OpenMinis BrowserUseManager.getCookies
 */
fun getCookiesTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_COOKIES,
    description = "Get cookies for the current page. Optionally filter by keyword. Returns a list of cookie name=value pairs. {cookies, count}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("keywords", buildJsonObject {
                put("type", "string")
                put("description", "Optional comma-separated keywords to filter cookies by name")
            })
        })
    },
    execute = { input ->
        val keywords = input.jsonObject["keywords"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val url = webView.url ?: ""
                if (url.isBlank() || url == "about:blank") {
                    return@withController buildJsonObject {
                        put("error", "no_page")
                        put("recovery", "navigate to a page first")
                    }
                }
                val raw = CookieManager.getInstance().getCookie(url) ?: ""
                if (raw.isEmpty()) {
                    return@withController buildJsonObject {
                        put("cookies", kotlinx.serialization.json.buildJsonArray { })
                        put("count", 0)
                    }
                }
                val pairs = raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
                    val eq = line.indexOf('=')
                    if (eq <= 0) null
                    else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
                }
                val filtered = if (keywords.isNullOrEmpty()) pairs else pairs.filter { (name, _) ->
                    keywords.any { kw -> name.contains(kw, ignoreCase = true) }
                }
                buildJsonObject {
                    put("cookies", kotlinx.serialization.json.buildJsonArray {
                        filtered.forEach { (name, value) ->
                            add(buildJsonObject {
                                put("name", name)
                                put("value", value.take(200))
                            })
                        }
                    })
                    put("count", filtered.size)
                    put("total", pairs.size)
                    put("url", url)
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.GET_COOKIES)
        textPart(out)
    },
)

/**
 * 设置 Cookies（写入当前页面的 Cookie 存储）。
 * 对标 OpenMinis BrowserUseManager.setCookies
 */
fun setCookiesTool(): Tool = Tool(
    name = BrowserToolDefaults.SET_COOKIES,
    description = "Set cookies for the current page. Accepts a list of {name, value, domain?, path?, secure?, httpOnly?} objects. {success, count}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("cookies", buildJsonObject {
                put("type", "string")
                put("description", "JSON array of cookie objects: [{\"name\":\"foo\",\"value\":\"bar\"}]")
            })
        }, required = listOf("cookies"))
    },
    execute = { input ->
        val cookiesRaw = input.jsonObject["cookies"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = if (cookiesRaw == null) {
            missingArgEnvelope("cookies", "cookies is required (JSON array of {name, value} objects)")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    val url = webView.url ?: ""
                    if (url.isBlank() || url == "about:blank") {
                        return@withController buildJsonObject {
                            put("error", "no_page")
                            put("recovery", "navigate to a page first")
                        }
                    }
                    val defaultDomain = runCatching { URI(url).host }.getOrNull().orEmpty()
                    val cookieMgr = CookieManager.getInstance()
                    var setCount = 0
                    var errorCount = 0
                    try {
                        val json = kotlinx.serialization.json.Json.parseToJsonElement(cookiesRaw).jsonArray
                        for (entry in json) {
                            val obj = entry.jsonObject
                            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                            val value = obj["value"]?.jsonPrimitive?.contentOrNull
                            if (name == null || value == null) { errorCount++; continue }
                            val domain = obj["domain"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: defaultDomain
                            val path = obj["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "/"
                            val sb = StringBuilder()
                            sb.append(name).append('=').append(value)
                            if (domain.isNotEmpty()) sb.append("; Domain=").append(domain)
                            sb.append("; Path=").append(path)
                            cookieMgr.setCookie(url, sb.toString())
                            setCount++
                        }
                        cookieMgr.flush()
                    } catch (e: Exception) {
                        return@withController buildJsonObject {
                            put("error", "parse_failed")
                            put("detail", e.message ?: "Invalid JSON format")
                        }
                    }
                    buildJsonObject {
                        put("success", true)
                        put("count", setCount)
                        if (errorCount > 0) put("errors", errorCount)
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SET_COOKIES)
        }
        textPart(out)
    },
)

/**
 * 列出所有标签页。
 * 对标 OpenMinis BrowserTabPool.listTabs
 */
fun listTabsTool(): Tool = Tool(
    name = BrowserToolDefaults.LIST_TABS,
    description = "List all open browser tabs. Returns {tabs: [{id, title, url, isActive}], count}.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val out = buildJsonObject {
            val sessions = BrowserController.sessions.value
            val selectedIdx = BrowserController.selectedSessionIndex.value
            put("count", sessions.size)
            put("max", 3)
            put("tabs", kotlinx.serialization.json.buildJsonArray {
                sessions.forEachIndexed { idx, session ->
                    add(buildJsonObject {
                        put("id", session.id)
                        put("title", session.pageTitle.value)
                        put("url", session.currentUrl.value)
                        put("isActive", idx == selectedIdx)
                    })
                }
            })
        }
        textPart(out)
    },
)

/**
 * 创建新标签页。
 * 对标 OpenMinis BrowserTabPool.createTab
 */
fun newTabTool(context: Context): Tool = Tool(
    name = BrowserToolDefaults.NEW_TAB,
    description = "Create a new browser tab. Returns {id, success}. Max 3 tabs.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("url", buildJsonObject {
                put("type", "string")
                put("description", "Optional URL to navigate to in the new tab")
            })
        })
    },
    execute = { input ->
        val url = input.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = withTimeoutOrNull(toolTimeoutMs) {
            withContext(Dispatchers.Main) {
                val session = BrowserController.createTab(context)
                if (session == null) {
                    buildJsonObject {
                        put("error", "max_tabs")
                        put("detail", "Maximum 3 tabs reached")
                    }
                } else {
                    if (url != null) {
                        session.loadUrl(url)
                        session.webView.awaitReadyState(8_000L)
                    }
                    buildJsonObject {
                        put("success", true)
                        put("id", session.id)
                        put("tabCount", BrowserController.sessions.value.size)
                    }
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.NEW_TAB)
        textPart(out)
    },
)

/**
 * 关闭标签页。
 * 对标 OpenMinis BrowserTabPool.closeTab
 */
fun closeTabTool(): Tool = Tool(
    name = BrowserToolDefaults.CLOSE_TAB,
    description = "Close a browser tab by ID. If no ID is given, closes the current tab. {success, remaining}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("tab_id", buildJsonObject {
                put("type", "integer")
                put("description", "Tab ID to close (default: current tab)")
            })
        })
    },
    execute = { input ->
        val tabId = input.jsonObject["tab_id"]?.jsonPrimitive?.intOrNull
        val out = withTimeoutOrNull(toolTimeoutMs) {
            withContext(Dispatchers.Main) {
                val sessions = BrowserController.sessions.value
                val idx = if (tabId != null) sessions.indexOfFirst { it.id == tabId } else BrowserController.selectedSessionIndex.value
                if (idx < 0 || idx >= sessions.size) {
                    buildJsonObject {
                        put("error", "tab_not_found")
                        put("detail", if (tabId != null) "Tab $tabId not found" else "No active tab")
                    }
                } else {
                    BrowserController.closeTab(idx)
                    buildJsonObject {
                        put("success", true)
                        put("tabId", tabId ?: sessions.getOrNull(idx)?.id ?: -1)
                        put("remaining", BrowserController.sessions.value.size)
                    }
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.CLOSE_TAB)
        textPart(out)
    },
)

/**
 * 设置 User Agent。
 * 对标 OpenMinis BrowserUseManager.setUserAgent
 */
fun setUserAgentTool(): Tool = Tool(
    name = BrowserToolDefaults.SET_USER_AGENT,
    description = "Switch the browser's user agent profile. Returns the new UA string. {success, user_agent, viewport}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("user_agent", buildJsonObject {
                put("type", "string")
                put("enum", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("mobile_chrome"))
                    add(kotlinx.serialization.json.JsonPrimitive("desktop_chrome"))
                })
                put("description", "User agent profile to switch to")
            })
        }, required = listOf("user_agent"))
    },
    execute = { input ->
        val profile = input.jsonObject["user_agent"]?.jsonPrimitive?.contentOrNull
        val out = if (profile == null || profile !in setOf("mobile_chrome", "desktop_chrome")) {
            missingArgEnvelope("user_agent", "user_agent must be 'mobile_chrome' or 'desktop_chrome'")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    withContext(Dispatchers.Main) {
                        val ua = if (profile == "desktop_chrome") {
                            BrowserController.DESKTOP_UA
                        } else {
                            BrowserController.mobileUA ?: webView.settings.userAgentString
                                .replace("Desktop", "Mobile")
                                .replace("Windows", "Linux")
                        }
                        webView.settings.userAgentString = ua
                        BrowserController.desktopMode = profile == "desktop_chrome"
                        // 重新加载页面以应用新 UA
                        if (webView.url?.isNotEmpty() == true && webView.url != "about:blank") {
                            webView.reload()
                            webView.awaitReadyState(8_000L)
                        }
                    }
                    val (vpW, vpH) = BrowserController.resolvedViewportSize()
                    buildJsonObject {
                        put("success", true)
                        put("user_agent", profile)
                        put("viewport", "${vpW}x$vpH")
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SET_USER_AGENT)
        }
        textPart(out)
    },
)

/**
 * 设置 Viewport 尺寸。
 * 对标 OpenMinis BrowserUseManager.applyViewport / BrowserTabPool.setGlobalViewport
 */
fun setViewportTool(): Tool = Tool(
    name = BrowserToolDefaults.SET_VIEWPORT,
    description = "Set the browser viewport size in CSS pixels. Width and height 0 restores the UA default. {success, width, height}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("width", buildJsonObject {
                put("type", "integer")
                put("description", "Viewport width in CSS pixels (0 = UA default)")
            })
            put("height", buildJsonObject {
                put("type", "integer")
                put("description", "Viewport height in CSS pixels (0 = UA default)")
            })
        }, required = listOf("width", "height"))
    },
    execute = { input ->
        val width = input.jsonObject["width"]?.jsonPrimitive?.intOrNull ?: 0
        val height = input.jsonObject["height"]?.jsonPrimitive?.intOrNull ?: 0
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserController.setGlobalViewport(width, height)
            // 通知当前会话重新布局
            BrowserController.selectedSession?.applyViewport(width, height)
            val (resW, resH) = BrowserController.resolvedViewportSize()
            buildJsonObject {
                put("success", true)
                put("width", resW)
                put("height", resH)
                put("custom", width > 0 && height > 0)
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.SET_VIEWPORT)
        textPart(out)
    },
)

/**
 * 滚动页面并收集元素文本。
 * 对标 OpenMinis BrowserUseManager.scrollAndCollect
 */
fun scrollAndCollectTool(): Tool = Tool(
    name = BrowserToolDefaults.SCROLL_AND_COLLECT,
    description = "Scroll the page multiple times, collecting text of elements matching a CSS selector. Useful for scanning listings, feeds, or search results. {matched, total, items}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("item_selector", buildJsonObject {
                put("type", "string")
                put("description", "CSS selector for items to collect")
            })
            put("scroll_count", buildJsonObject {
                put("type", "integer")
                put("description", "Number of times to scroll (default 5, max 50)")
            })
            put("keywords", buildJsonObject {
                put("type", "string")
                put("description", "Optional comma-separated keywords to filter items")
            })
        }, required = listOf("item_selector"))
    },
    execute = { input ->
        val selector = input.jsonObject["item_selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val scrollCount = (input.jsonObject["scroll_count"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 50)
        val keywords = input.jsonObject["keywords"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val out = if (selector == null) {
            missingArgEnvelope("item_selector", "item_selector is required")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    val collected = mutableSetOf<String>()
                    for (i in 0 until scrollCount) {
                        val js = """(function(){
                            try {
                                var nodes = document.querySelectorAll(${jsString(selector)});
                                var out = [];
                                for (var i=0;i<nodes.length;i++) {
                                    var t = (nodes[i].innerText || nodes[i].textContent || '').trim();
                                    if (t) out.push(t);
                                }
                                return JSON.stringify(out);
                            } catch(e) { return '[]'; }
                        })()"""
                        val raw = webView.evaluateJavascriptAsync(js)
                        try {
                            val arr = kotlinx.serialization.json.Json.parseToJsonElement(raw ?: "[]").jsonArray
                            for (el in arr) {
                                val s = el.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                                collected.add(s)
                            }
                        } catch (_: Exception) { }
                        // Scroll one viewport down
                        webView.evaluateJavascriptAsync("window.scrollBy(0, window.innerHeight);", 2_000L)
                        delay(400)
                    }
                    val filtered = if (keywords.isNullOrEmpty()) collected.toList()
                        else collected.filter { text -> keywords.any { kw -> text.contains(kw, ignoreCase = true) } }
                    BrowserController.appendAction("Scroll+collect: $selector (${filtered.size} items)")
                    buildJsonObject {
                        put("success", true)
                        put("matched", filtered.size)
                        put("total", collected.size)
                        put("scrolls", scrollCount)
                        put("items", kotlinx.serialization.json.buildJsonArray {
                            filtered.take(50).forEach { item ->
                                add(buildJsonObject {
                                    put("text", item.take(200))
                                })
                            }
                        })
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SCROLL_AND_COLLECT)
        }
        textPart(out)
    },
)
