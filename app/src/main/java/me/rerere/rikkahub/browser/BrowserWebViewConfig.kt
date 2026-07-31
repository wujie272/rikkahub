package me.rerere.rikkahub.browser

import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Single source of truth for WebView settings shared by the foreground browser
 * ([BrowserView]).
 *
 * Why this exists. The foreground BrowserView accumulated four white-page render
 * fixes between commits `1ac54c4b`, `3ac3b4b4`, and `a1db859c`:
 *  - `mixedContentMode = MIXED_CONTENT_COMPATIBILITY_MODE` (HTTPS pages with HTTP
 *    analytics / fonts render blank under the default NEVER_ALLOW)
 *  - `setLayerType(LAYER_TYPE_HARDWARE, null)` (Compose `AndroidView` interop loses
 *    the hardware layer inside a `Box` and the page renders all-white)
 *  - `mediaPlaybackRequiresUserGesture = false` (sites whose player JS errors out
 *    before layout settle render blank)
 *  - `userAgentString = UserAgentProfile.MOBILE.userAgentString` (Google 永久禁止
 *    WebView 登录，使用真实 Chrome UA 绕过 403 disallowed_useragent)
 *
 * Pulling the configuration into one shared function means future fixes apply
 * consistently across all browser sessions.
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
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportMultipleWindows(true)
        javaScriptCanOpenWindowsAutomatically = false
        mediaPlaybackRequiresUserGesture = false
        builtInZoomControls = false
        displayZoomControls = false
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        // 直接用 Chrome Mobile UA（无 Version/4.0 标记），
        // 避免 Google 等网站 403 disallowed_useragent
        userAgentString = UserAgentProfile.MOBILE.userAgentString!!
    }
    // Hardware layer hint. For the foreground Activity's WebView this fixes a Compose
    // AndroidView interop quirk that produces all-white pages. For headless capture via
    // `webView.draw(canvas)` onto a software bitmap the framework falls back to the
    // software path automatically — calling this is harmless either way and keeps the
    // two code paths identical.
    webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

    // 启用第三方 Cookie，否则 hCaptcha/Turnstile/reCAPTCHA 会卡住
    // 验证码 widget 在跨域 iframe 中通过 Set-Cookie 回传 token
    android.webkit.CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
}
