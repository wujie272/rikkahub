package me.rerere.rikkahub.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import java.net.URI

/**
 * Google OAuth 路由 — 对标 OpenMinis GoogleAuthRouter
 *
 * Google 永久禁止 WebView 登录（403 disallowed_useragent），
 * 将 accounts.google.com 等域名路由到 Chrome Custom Tab。
 * Custom Tab 运行在用户真实的 Chrome 进程里，共享 Cookie。
 */
object GoogleAuthRouter {

    private val GOOGLE_AUTH_HOSTS = listOf(
        "accounts.google.com",
        "signin.google.com",
        "myaccount.google.com",
        "oauth2.googleapis.com",
        "accounts.youtube.com",
    )

    /**
     * 判断 URL 是否需要路由到外部（Custom Tab）。
     */
    fun shouldRouteExternally(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return GOOGLE_AUTH_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * 在 Chrome Custom Tab 中打开 URL。
     * 回退到普通 ACTION_VIEW。
     */
    fun openInCustomTab(context: Context, url: String) {
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
                .launchUrl(context, Uri.parse(url))
        } catch (t: Throwable) {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
}
