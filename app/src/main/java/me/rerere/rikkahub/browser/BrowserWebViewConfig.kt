package me.rerere.rikkahub.browser

import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Single source of truth for WebView settings shared by the foreground browser
 * ([BrowserView]) and the headless browser ([HeadlessBrowserSession]).
 *
 * Why this exists. The foreground BrowserView accumulated four white-page render
 * fixes between commits `1ac54c4b`, `3ac3b4b4`, and `a1db859c`:
 *  - `mixedContentMode = MIXED_CONTENT_COMPATIBILITY_MODE` (HTTPS pages with HTTP
 *    analytics / fonts render blank under the default NEVER_ALLOW)
 *  - `setLayerType(LAYER_TYPE_HARDWARE, null)` (Compose `AndroidView` interop loses
 *    the hardware layer inside a `Box` and the page renders all-white)
 *  - `mediaPlaybackRequiresUserGesture = false` (sites whose player JS errors out
 *    before layout settle render blank)
 *  - `userAgentString.replace("; wv)", ")")` (Hugo / Cloudflare / bot-sniff CMSes
 *    serve stripped-down content to a `wv`-marked embedded WebView)
 *
 * Those fixes lived in `BrowserView.WebViewHost` only. The headless WebView created
 * by `HeadlessBrowserSession.start` had NONE of them, so a headless-driven
 * browse on the same site that the user just verified loads in foreground would
 * silently render an all-white PNG and stream it back to the user's chat.
 *
 * Pulling the configuration into one shared function means future fixes for either
 * mode automatically benefit the other.
 */
internal fun configureWebViewForRikka(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        // Removed in API 35 but still compile-time present and load-bearing for some
        // sites that store IndexedDB shadow data via the old WebSQL fallback.
        @Suppress("DEPRECATION")
        databaseEnabled = true
        // Phase 20D needs this — skill webview cards produce file:// URLs into the
        // app's private data dir. Cross-origin protection still applies via the
        // file:// unique-origin rule (http(s) pages can't fetch file:// content).
        allowFileAccess = true
        // Required for skill webview assets: when a skill's viewer page (e.g.
        // virtual-piano's ui.html) is opened from a file:// URL it needs to load
        // sibling asset files (audio, images, sub-pages) also via file://. Without
        // this flag the WebView blocks those requests silently (no error, just empty
        // <audio> elements). This only enables file:// → file:// sub-resource loads;
        // http(s) pages still cannot reach app-private file:// paths.
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = true
        allowContentAccess = false
        // useWideViewPort + loadWithOverviewMode: 保持 WebView 默认行为。
        // useWideViewPort=true 让 WebView 自适应 viewport meta 标签的宽度，
        // loadWithOverviewMode=false 让它不要缩放到屏幕宽度（这会导致
        // 响应式页面内容被压到顶部）。这样配合后，移动端页面正常渲染，
        // 桌面端页面也不会被奇怪地缩放。
        useWideViewPort = true
        loadWithOverviewMode = false
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        mediaPlaybackRequiresUserGesture = false
        builtInZoomControls = true
        displayZoomControls = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Full Chrome mobile UA instead of just stripping "wv" — X/Twitter and other
        // sites detect WebView via navigator.webdriver, User-Agent inconsistencies,
        // and other fingerprinting signals. A real Chrome UA reduces WebView blocking.
        val chromeMobileUA = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"
        userAgentString = chromeMobileUA

        // Capture the mobile UA for desktop mode toggle restoration
        BrowserController.mobileUA = userAgentString
    }
    // Hardware layer hint. For the foreground Activity's WebView this fixes a Compose
    // AndroidView interop quirk that produces all-white pages. For headless capture via
    // `webView.draw(canvas)` onto a software bitmap the framework falls back to the
    // software path automatically — calling this is harmless either way and keeps the
    // two code paths identical.
    webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

    // WebViewClient delegation for anti-bot shim injection
}

/** JS injected on every page start to hide WebView/bot fingerprinting signals. */
internal const val ANTI_BOT_SHIM_JS = """
(function(){
    try {
        // Hide navigator.webdriver — sites like X/Twitter check this flag
        Object.defineProperty(navigator, 'webdriver', {get: function(){return undefined;}, configurable: true});
        // Spoof chrome object presence (real Chrome has it, WebView lacks it)
        if (window.chrome === undefined) {
            Object.defineProperty(window, 'chrome', {value: {runtime: {}}, configurable: true});
        }
        // Override plugins to look like Chrome mobile has them
        Object.defineProperty(navigator, 'plugins', {get: function(){return [1,2,3,4,5];}, configurable: true});
        // Override languages to match Chrome defaults
        Object.defineProperty(navigator, 'languages', {get: function(){return ['en-US','en','zh-CN','zh'];}, configurable: true});
    } catch(e) { /* best-effort */ }
})();
"""
