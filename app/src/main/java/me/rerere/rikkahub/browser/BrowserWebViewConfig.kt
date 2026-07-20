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
 *
 * **Anti-detection layer.** This function also configures UA spoofing,
 * X-Requested-With suppression, and third-party cookie support — all needed
 * for sites like X/Twitter that actively block embedded WebViews.
 */
internal fun configureWebViewForRikka(webView: WebView) {
    // ═══════════════════════════════════════════════════════════════════
    // Anti-detection layer
    // ═══════════════════════════════════════════════════════════════════

    // 1) Suppress X-Requested-With header (WebView 113+ API).
    //    X/Twitter checks this header to detect embedded WebViews.
    @Suppress("DEPRECATION")
    runCatching {
        androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(
            webView.settings, emptySet()
        )
    }.onFailure { /* WebView too old — UA spoof + JS shim still help */ }

    // 2) Third-party cookies — X.com OAuth redirects across subdomains.
    runCatching {
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    // 3) Private State Token — Cloudflare PAT bypasses JS challenge.
    //    Enables cryptographic attestation that the device is legitimate,
    //    allowing Cloudflare-protected sites (e.g. civitai.com) to skip
    //    the JS challenge entirely. Added in API 33 (Android 13).
    //    Your device runs Android 16, so this is fully supported.
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        runCatching {
            webView.settings.setPrivateStateTokenEnabled(true)
        }
    }

    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        @Suppress("DEPRECATION")
        databaseEnabled = true
        allowFileAccess = true
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = true
        allowContentAccess = false
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        mediaPlaybackRequiresUserGesture = false
        builtInZoomControls = true
        displayZoomControls = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // 4) Full Chrome mobile UA — no "wv" token, resembles real Chrome.
        val chromeMobileUA = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"
        userAgentString = chromeMobileUA

        // Stash for desktop-mode toggle in BrowserActivity
        BrowserController.mobileUA = userAgentString
    }
    webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    // 5) Set background color to prevent white flash before page renders.
    //    Without this, the WebView shows a white/black background while the
    //    page loads, which users perceive as a blank screen.
    webView.setBackgroundColor(android.graphics.Color.WHITE)
}

/**
 * JS injected on every page start to hide WebView/bot fingerprinting signals.
 * This shim is injected via [WebViewClient.onPageStarted] in both [BrowserView]
 * and [HeadlessBrowserSession].
 */
internal const val ANTI_BOT_SHIM_JS = """
(function(){
    try {
        Object.defineProperty(navigator, 'webdriver', {get: function(){return undefined;}, configurable: true});
        if (window.chrome === undefined) {
            Object.defineProperty(window, 'chrome', {value: {runtime: {}}, configurable: true});
        }
        Object.defineProperty(navigator, 'plugins', {get: function(){return [1,2,3,4,5];}, configurable: true});
        Object.defineProperty(navigator, 'languages', {get: function(){return ['en-US','en','zh-CN','zh'];}, configurable: true});
    } catch(e) {}
})();
"""

/**
 * Domain list for sites known to block Android WebView. When the foreground
 * browser loads one of these, [BrowserView] intercepts and opens them via
 * Chrome Custom Tabs instead — these sites use multi-layer fingerprinting
 * (UA + XRW + JS API checks) that cannot be fully spoofed from an embedded
 * WebView.
 *
 * Maintained list as of 2026-07:
 *   - x.com / twitter.com: actively blocks WebView
 *   - Add new domains here as they're discovered.
 */
internal val WEBVIEW_BLOCKED_DOMAINS = setOf(
    "x.com",
    "twitter.com",
)

/**
 * Cursor position shim — injected on [WebViewClient.onPageFinished] to guard
 * against the `type="tel"` cursor-jump-to-start bug that affects sites like
 * Boss直聘 login page.
 *
 * Root cause: Android WebView's internal cursor-position management interacts
 * badly with some IMEs when `EditorInfo.IME_FLAG_NAVIGATE_NEXT` / `_PREVIOUS`
 * are set on a phone/tel-type input field. The Kotlin-side fix in
 * [BrowserView.onCreateInputConnection] clears those flags. This JS script
 * is belt-and-suspenders: it re-positions the caret to the end after every
 * `input` event on any `type="tel"` / `inputmode="numeric"` / `type="number"`
 * field, covering IMEs that manage their own cursor internally.
 */
internal const val CURSOR_POSITION_SHIM_JS = """
(function(){
    try {
        document.querySelectorAll('input[type="tel"], input[type="number"], input[inputmode="numeric"]').forEach(function(el) {
            // Remove any existing listener from a previous injection to avoid stacking
            el.removeEventListener('input', cursorFixer);
            el.addEventListener('input', cursorFixer);
        });
    } catch(e) {}
    function cursorFixer() {
        // Preserve selection direction: if user is selecting backward (selectionStart > selectionEnd),
        // don't override. Only fix the common case where IME reset both to 0.
        var len = this.value.length;
        if (this.selectionStart === 0 && this.selectionEnd === 0 && len > 0) {
            this.setSelectionRange(len, len);
        }
    }
})();
"""
