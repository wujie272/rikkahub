package me.rerere.rikkahub.ui.pages.setting.browser

import android.content.Context
import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.browser.BrowserPreferences
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.browser.CookieStore
import java.io.File

class SettingBrowserViewModel(
    private val prefs: BrowserPreferences,
) : ViewModel() {

    /** 已保存 Cookie 的域名列表。通过 [refreshCookieDomains] 刷新。 */
    val cookieDomains: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())

    /** Per-tool enabled map, keyed by [me.rerere.rikkahub.browser.BrowserToolDefaults.ALL_TOOLS]. */
    val toolStates: StateFlow<Map<String, Boolean>> = prefs.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyMap(),
    )

    /** Per-tool timeout, in milliseconds. Always clamped into the supported range. */
    val perToolTimeoutMs: StateFlow<Long> = prefs.perToolTimeoutFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BrowserToolDefaults.DEFAULT_PER_TOOL_TIMEOUT_MS,
    )

    /** Single-task timeout, in milliseconds. Always clamped into the supported range. */
    val singleTaskTimeoutMs: StateFlow<Long> = prefs.singleTaskTimeoutFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BrowserToolDefaults.DEFAULT_SINGLE_TASK_TIMEOUT_MS,
    )

    /** Search engine index (0-based into [BrowserToolDefaults.SEARCH_ENGINES]). */
    val searchEngineIndex: StateFlow<Int> = prefs.searchEngineIndexFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BrowserToolDefaults.DEFAULT_SEARCH_ENGINE_INDEX,
    )

    // ─────────────────────────────────────────────────────
    // Cookie 域名记录
    // ─────────────────────────────────────────────────────

    /** 刷新 Cookie 域名列表。在页面显示时调用。 */
    fun refreshCookieDomains() {
        cookieDomains.value = CookieStore.getDomains().toList()
    }

    /** 获取某个域名的 Cookie 详情。 */
    fun getCookieString(domain: String): String? = CookieStore.getCookieString(domain)

    /** 删除某个域名的 Cookie 记录。 */
    fun removeCookieDomain(domain: String) {
        CookieStore.removeDomain(domain)
        refreshCookieDomains()
    }

    // ─────────────────────────────────────────────────────
    // Tool toggles
    // ─────────────────────────────────────────────────────

    fun setToolEnabled(toolName: String, enabled: Boolean) {
        viewModelScope.launch { prefs.setToolEnabled(toolName, enabled) }
    }

    fun setPerToolTimeoutSeconds(seconds: Long) {
        viewModelScope.launch { prefs.setPerToolTimeoutMs(seconds * 1_000L) }
    }

    fun setSingleTaskTimeoutMinutes(minutes: Long) {
        viewModelScope.launch { prefs.setSingleTaskTimeoutMs(minutes * 60_000L) }
    }

    fun setSearchEngineIndex(index: Int) {
        viewModelScope.launch { prefs.setSearchEngineIndex(index) }
    }

    /**
     * Wipes the WebView profile dir + cookies. Tool-toggle state is intentionally NOT
     * cleared — those are user config, not browsing data.
     */
    fun clearBrowsingData(context: Context, onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val profileDir = File(context.filesDir, "browser-profile")
                    if (profileDir.exists()) {
                        profileDir.deleteRecursively()
                    }
                    profileDir.mkdirs()
                }
            }
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            CookieStore.clear()
            onDone()
        }
    }
}
