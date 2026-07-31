package me.rerere.rikkahub.browser

import android.content.Context
import android.webkit.CookieManager
import java.net.URI

/**
 * 记录浏览器访问过的域名，用于在设置页展示"已保存 Cookie 的网站"列表。
 *
 * 为什么需要这个？Android 的 [CookieManager] 只提供了 [CookieManager.getCookie] 按域名查询，
 * 没有"列出所有存了 Cookie 的域名"的 API。所以我们自己记录。
 *
 * 记录时机：在 [WebViewClient.onPageStarted] 中调用 [recordUrl]。
 * 清理时机：浏览器"清除浏览数据"时调用 [clear]。
 *
 * 存储使用 SharedPreferences（轻量级 Set<String>），和 CookieManager 的存储独立。
 */
object CookieStore {

    private const val PREFS_NAME = "rikka_cookie_domains"
    private const val KEY_DOMAINS = "domains"

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    /**
     * 初始化。幂等，多次调用安全。
     * 在 [BrowserActivity.onCreate] 和 [HeadlessBrowserSession.start] 中调用。
     */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * 记录一个 URL 的域名。非 http/https 的 URL 会被忽略。
     * 在 [WebViewClient.onPageStarted] 中调用。
     */
    fun recordUrl(url: String?) {
        if (url.isNullOrBlank()) return
        val host = extractHost(url) ?: return
        prefs?.edit()?.putStringSet(KEY_DOMAINS, getDomains() + host)?.apply()
    }

    /** 获取所有已记录的域名，按字母排序。 */
    fun getDomains(): Set<String> {
        return prefs?.getStringSet(KEY_DOMAINS, emptySet())?.toSortedSet() ?: emptySet()
    }

    /** 删除某个域名的记录（不会删除 CookieManager 中的 Cookie）。 */
    fun removeDomain(domain: String) {
        prefs?.edit()?.putStringSet(KEY_DOMAINS, getDomains() - domain)?.apply()
    }

    /** 清空所有记录（同时调用 [CookieManager.removeAllCookies]）。 */
    fun clear() {
        prefs?.edit()?.remove(KEY_DOMAINS)?.apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /**
     * 获取某个域名的 Cookie 字符串。
     * 返回 [CookieManager.getCookie] 的原始值，格式为 "key1=value1; key2=value2"。
     */
    fun getCookieString(domain: String): String? {
        return CookieManager.getInstance().getCookie("https://$domain")
    }

    /**
     * 删除某个域名的所有 Cookie（同时删除记录）。
     * 通过 [CookieManager.setCookie] 写入空字符串来清除该域名的所有 Cookie。
     * 对标 OpenMinis: CookieManager.getInstance().setCookie(domain, "")
     */
    fun removeCookieForDomain(domain: String) {
        removeDomain(domain)
        CookieManager.getInstance().setCookie("https://$domain", "")
        CookieManager.getInstance().setCookie("http://$domain", "")
        CookieManager.getInstance().flush()
    }

    private fun extractHost(url: String): String? {
        return try {
            val uri = URI(url)
            val host = uri.host
            if (host != null && uri.scheme in listOf("http", "https")) {
                host.removePrefix("www.") // 统一域名，www.example.com 和 example.com 视为同一个
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
