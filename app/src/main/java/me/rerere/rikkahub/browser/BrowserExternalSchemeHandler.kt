package me.rerere.rikkahub.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 外部 URL Scheme 路由 — 对标 OpenMinis BrowserExternalSchemeHandler
 *
 * 处理 WebView 中点击的非 http(s) 链接（intent://, tel:, mailto: 等），
 * 将外部链接路由到系统对应的应用，避免 WebView 显示 ERR_UNKNOWN_URL_SCHEME。
 */
object BrowserExternalSchemeHandler {
    private val INTERNAL_SCHEMES = setOf("http", "https", "about", "file", "data")

    private val EXTERNAL_VIEW_SCHEMES = setOf(
        "tel", "mailto", "sms", "smsto", "mms", "mmsto",
        "geo", "market", "whatsapp", "tg", "weixin",
    )

    /**
     * 处理外部 URL。如果 URL 应被路由到外部应用，返回 true 并启动对应 Intent。
     */
    fun handleUrl(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme ?: return false

        // 内部 scheme 让 WebView 正常加载
        if (scheme in INTERNAL_SCHEMES) return false

        return when (scheme) {
            "intent" -> handleIntentScheme(context, url)
            "android-app" -> handleAndroidAppScheme(context, uri)
            in EXTERNAL_VIEW_SCHEMES -> openExternalView(context, uri)
            else -> {
                // 其他未知 scheme 也尝试 ACTION_VIEW
                try {
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    false
                }
            }
        }
    }

    private fun handleIntentScheme(context: Context, url: String): Boolean {
        return try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                // 移除 NEW_DOCUMENT 标志，避免在非 Activity 上下文中 crash
                flags = flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT.inv()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // 移除 package 限制，让系统选择可用应用
            val intentToTry = Intent(intent).apply { `package` = null }
            try {
                context.startActivity(intentToTry)
                true
            } catch (e: ActivityNotFoundException) {
                // 尝试 fallback URL
                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                if (fallbackUrl != null) {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(fallbackIntent)
                        true
                    } catch (_: ActivityNotFoundException) {
                        showToast(context, "无法打开链接")
                        true
                    }
                } else {
                    showToast(context, "无法打开链接")
                    true
                }
            }
        } catch (e: Exception) {
            // Intent.parseUri 可能抛出 URISyntaxException
            false
        }
    }

    private fun handleAndroidAppScheme(context: Context, uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            showToast(context, "无法打开应用")
            true
        }
    }

    private fun openExternalView(context: Context, uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            showToast(context, "无法打开链接")
            true
        }
    }

    private fun showToast(context: Context, message: String) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
