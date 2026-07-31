package me.rerere.rikkahub.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.Delete02
import org.koin.compose.koinInject

/**
 * 浏览器设置 Sheet —— 对标 OpenMinis BrowserSettingsSheet。
 *
 * 改进：
 * - 使用 UserAgentProfile 枚举管理 UA
 * - Viewport 通过 BrowserController 统一管理
 * - imePadding() + bringIntoViewOnFocus 防止键盘遮挡
 * - ViewportSection 抽离为独立组件
 * - 状态显示移到 Viewport 区域底部
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSettingsSheet(
    webView: WebView?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_settings", android.content.Context.MODE_PRIVATE) }
    val coroutineScope = rememberCoroutineScope()

    // ── UA 状态（对标 OpenMinis：UserAgentProfile 枚举） ──
    var selectedProfile by remember {
        mutableStateOf(
            UserAgentProfile.fromPrefString(prefs.getString("ua_profile", "mobile") ?: "mobile")
        )
    }
    var customUA by remember { mutableStateOf(prefs.getString("custom_ua", "") ?: "") }

    fun applyUA(profile: UserAgentProfile, custom: String = customUA) {
        val ua = profile.userAgentString ?: custom.ifBlank { UserAgentProfile.MOBILE.userAgentString!! }
        webView?.settings?.userAgentString = ua
        // 持久化
        prefs.edit()
            .putString("ua_profile", profile.value)
            .putString("custom_ua", custom)
            .apply()
    }

    // ── Viewport 状态（对标 OpenMinis：通过 tabPool 的 flow 双向绑定） ──
    val customWidthFromPool by BrowserController.customViewportWidth.collectAsState()
    val customHeightFromPool by BrowserController.customViewportHeight.collectAsState()
    val isCustomViewportActive = customWidthFromPool > 0 && customHeightFromPool > 0

    var viewportWidthText by remember(customWidthFromPool) {
        mutableStateOf(if (customWidthFromPool > 0) customWidthFromPool.toString() else "")
    }
    var viewportHeightText by remember(customHeightFromPool) {
        mutableStateOf(if (customHeightFromPool > 0) customHeightFromPool.toString() else "")
    }
    var viewportMode by remember(isCustomViewportActive) {
        mutableStateOf(if (isCustomViewportActive) ViewportMode.CUSTOM else ViewportMode.DEFAULT)
    }
    var showCustomViewportEditor by remember { mutableStateOf(isCustomViewportActive) }

    // ── 空闲超时 ──
    val browserPreferences = koinInject<BrowserPreferences>()
    val singleTaskTimeoutMs by browserPreferences.singleTaskTimeoutFlow().collectAsState(
        initial = BrowserController.singleTaskTimeoutMs
    )
    var idleTimeoutText by remember(singleTaskTimeoutMs) {
        mutableStateOf((singleTaskTimeoutMs / 60_000L).toInt().toString())
    }

    // ── 搜索引擎 ──
    var selectedSearchEngine by remember {
        mutableStateOf(
            prefs.getInt("search_engine_index", BrowserToolDefaults.DEFAULT_SEARCH_ENGINE_INDEX)
                .coerceIn(0, BrowserToolDefaults.SEARCH_ENGINES.size - 1)
        )
    }

    fun applySearchEngine(index: Int) {
        selectedSearchEngine = index
        BrowserController.searchEngineIndex = index
        prefs.edit().putInt("search_engine_index", index).apply()
    }

    // ── Cookies 状态 ──
    var showClearConfirm by remember { mutableStateOf(false) }
    var cookieFilterText by remember { mutableStateOf("") }
    CookieStore.init(context)
    val allDomains = remember { mutableStateListOf<String>() }.also { list ->
        if (list.isEmpty()) list.addAll(CookieStore.getDomains())
    }
    val domains = remember(allDomains.toList(), cookieFilterText) {
        val source = allDomains.toList()
        if (cookieFilterText.isBlank()) source
        else source.filter { it.contains(cookieFilterText.trim(), ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 对标 OpenMinis: imePadding() 防止键盘遮挡
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
        ) {
            // ── Header ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "浏览器设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("完成") }
            }

            Spacer(Modifier.height(16.dp))

            // ═══════════════════════════════════════════════
            //  User Agent（对标 OpenMinis）
            // ═══════════════════════════════════════════════
            UserAgentSection(
                selectedProfile = selectedProfile,
                customUA = customUA,
                onProfileChange = { profile ->
                    selectedProfile = profile
                    applyUA(profile)
                },
                onCustomUAChange = { customUA = it; prefs.edit().putString("custom_ua", it).apply() },
                onApplyCustomUA = { applyUA(UserAgentProfile.CUSTOM, customUA) },
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ═══════════════════════════════════════════════
            //  搜索引擎
            // ═══════════════════════════════════════════════
            SearchEngineSection(
                selectedIndex = selectedSearchEngine,
                engines = BrowserToolDefaults.SEARCH_ENGINES,
                onSelect = ::applySearchEngine,
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ═══════════════════════════════════════════════
            //  Web Viewport（对标 OpenMinis ViewportSection）
            // ═══════════════════════════════════════════════
            ViewportSection(
                selectedProfile = selectedProfile,
                viewportMode = viewportMode,
                isCustomActive = isCustomViewportActive,
                widthText = viewportWidthText,
                heightText = viewportHeightText,
                showEditor = showCustomViewportEditor,
                onWidthChange = { viewportWidthText = it.filter { ch -> ch.isDigit() }.take(5) },
                onHeightChange = { viewportHeightText = it.filter { ch -> ch.isDigit() }.take(5) },
                onSelectDefault = {
                    viewportMode = ViewportMode.DEFAULT
                    coroutineScope.launch {
                        BrowserController.setGlobalViewport(0, 0)
                    }
                    viewportWidthText = ""
                    viewportHeightText = ""
                    showCustomViewportEditor = false
                },
                onSelectCustom = {
                    viewportMode = ViewportMode.CUSTOM
                    showCustomViewportEditor = true
                    val w = viewportWidthText.toIntOrNull()
                    val h = viewportHeightText.toIntOrNull()
                    if (w != null && h != null && w > 0 && h > 0) {
                        coroutineScope.launch {
                            BrowserController.setGlobalViewport(
                                w.coerceIn(VIEWPORT_MIN, VIEWPORT_MAX),
                                h.coerceIn(VIEWPORT_MIN, VIEWPORT_MAX),
                            )
                        }
                    }
                },
                onApply = {
                    val w = viewportWidthText.toIntOrNull()
                    val h = viewportHeightText.toIntOrNull()
                    if (w != null && h != null && w > 0 && h > 0) {
                        val cw = w.coerceIn(VIEWPORT_MIN, VIEWPORT_MAX)
                        val ch = h.coerceIn(VIEWPORT_MIN, VIEWPORT_MAX)
                        viewportWidthText = cw.toString()
                        viewportHeightText = ch.toString()
                        viewportMode = ViewportMode.CUSTOM
                        coroutineScope.launch {
                            BrowserController.setGlobalViewport(cw, ch)
                        }
                    }
                },
                onPreset = { w, h ->
                    viewportWidthText = w.toString()
                    viewportHeightText = h.toString()
                    viewportMode = ViewportMode.CUSTOM
                    coroutineScope.launch {
                        BrowserController.setGlobalViewport(w, h)
                    }
                },
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ═══════════════════════════════════════════════
            //  空闲超时
            // ═══════════════════════════════════════════════
            SectionTitle("空闲标签页回收")
            Spacer(Modifier.height(4.dp))
            Text(
                "WebView 在空闲指定分钟后自动回收以释放内存（默认 15，范围 1-240）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = idleTimeoutText,
                    onValueChange = { idleTimeoutText = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text("分钟") },
                    singleLine = true,
                    // 对标 OpenMinis: bringIntoViewOnFocus 防止键盘遮挡
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = {
                    val minutes = idleTimeoutText.toIntOrNull()?.coerceIn(1, 240) ?: 15
                    idleTimeoutText = minutes.toString()
                    coroutineScope.launch {
                        browserPreferences.setSingleTaskTimeoutMs(minutes * 60 * 1000L)
                    }
                }) { Text("应用") }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ═══════════════════════════════════════════════
            //  Cookies & 网站数据（对标 OpenMinis）
            // ═══════════════════════════════════════════════
            SectionTitle("Cookies & 网站数据")
            Spacer(Modifier.height(8.dp))

            val hasCookies = CookieManager.getInstance().hasCookies()

            if (allDomains.isNotEmpty()) {
                OutlinedTextField(
                    value = cookieFilterText,
                    onValueChange = { cookieFilterText = it },
                    label = { Text("按域名筛选") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }

            if (domains.isEmpty()) {
                Text(
                    when {
                        cookieFilterText.isNotBlank() -> "没有匹配「${cookieFilterText}」的域名"
                        hasCookies -> "Cookies 已存储。"
                        else -> "没有存储的 Cookies。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.height((domains.size.coerceAtMost(6) * 44).dp)) {
                    items(domains) { domain ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                domain,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    CookieStore.removeCookieForDomain(domain)
                                    allDomains.remove(domain)
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    HugeIcons.Delete02,
                                    contentDescription = "删除 $domain 的 Cookies",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (hasCookies) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showClearConfirm = true },
                ) {
                    Text("清除所有 Cookies", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清除所有 Cookies？") },
            text = { Text("这将清除所有网站的 Cookies 和本地存储数据，操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    CookieManager.getInstance().removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                    showClearConfirm = false
                }) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }
}

// ── 组件（对标 OpenMinis 的组件化设计） ──────────────────────────────────

// ── User Agent Section ──

@Composable
private fun UserAgentSection(
    selectedProfile: UserAgentProfile,
    customUA: String,
    onProfileChange: (UserAgentProfile) -> Unit,
    onCustomUAChange: (String) -> Unit,
    onApplyCustomUA: () -> Unit,
) {
    SectionTitle("User Agent")
    Spacer(Modifier.height(8.dp))

    val notSetPlaceholder = "未设置"
    for (profile in UserAgentProfile.entries) {
        val uaSubtitle = UserAgentProfile.displayUA(profile, customUA, notSetPlaceholder)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selectedProfile == profile,
                onClick = { onProfileChange(profile) },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    uaSubtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (selectedProfile == UserAgentProfile.CUSTOM) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = customUA,
            onValueChange = onCustomUAChange,
            label = { Text("自定义 UA 字符串") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onApplyCustomUA) { Text("应用") }
    }
}

// ── Search Engine Section ──

@Composable
private fun SearchEngineSection(
    selectedIndex: Int,
    engines: List<BrowserToolDefaults.SearchEngine>,
    onSelect: (Int) -> Unit,
) {
    SectionTitle("搜索引擎")
    Spacer(Modifier.height(8.dp))
    Text(
        "在地址栏输入非 URL 内容时使用的搜索引擎。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    engines.forEachIndexed { index, engine ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
            )
            Text(
                engine.name,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ── Viewport Section ──

private enum class ViewportMode { DEFAULT, CUSTOM }

/** 对标 OpenMinis: VIEWPORT_MIN / VIEWPORT_MAX */
private const val VIEWPORT_MIN = 200
private const val VIEWPORT_MAX = 4096

/** 对标 OpenMinis: VIEWPORT_UA_BREAKPOINT */
private const val VIEWPORT_UA_BREAKPOINT = 768

private val viewportPresets = listOf(
    "Phone" to (412 to 915),
    "Phone Pro" to (430 to 932),
    "Tablet" to (820 to 1180),
    "Laptop" to (1280 to 800),
    "Desktop" to (1440 to 900),
    "Full HD" to (1920 to 1080),
)

@Composable
private fun ViewportSection(
    selectedProfile: UserAgentProfile,
    viewportMode: ViewportMode,
    isCustomActive: Boolean,
    widthText: String,
    heightText: String,
    showEditor: Boolean,
    onWidthChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
    onSelectDefault: () -> Unit,
    onSelectCustom: () -> Unit,
    onApply: () -> Unit,
    onPreset: (Int, Int) -> Unit,
) {
    SectionTitle("Web Viewport")
    Spacer(Modifier.height(8.dp))

    // Default（对标 OpenMinis）
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = viewportMode == ViewportMode.DEFAULT,
            onClick = onSelectDefault,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text("默认（按 UA 自动）", style = MaterialTheme.typography.bodyMedium)
            val (uaW, uaH) = selectedProfile.viewportSize
            Text("$uaW × $uaH", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // Custom（对标 OpenMinis）
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = viewportMode == ViewportMode.CUSTOM,
            onClick = onSelectCustom,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text("自定义", style = MaterialTheme.typography.bodyMedium)
            Text("设置宽 × 高", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showEditor) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = widthText,
                onValueChange = onWidthChange,
                label = { Text("Width") },
                singleLine = true,
                modifier = Modifier.width(110.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.size(8.dp))
            Text("×", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = heightText,
                onValueChange = onHeightChange,
                label = { Text("Height") },
                singleLine = true,
                modifier = Modifier.width(110.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onApply) { Text("应用") }
        }

        // UA 不匹配警告（对标 OpenMinis UaMismatchBanner）
        val warning = uaMismatchWarning(selectedProfile, widthText)
        if (warning != null) {
            Spacer(Modifier.height(8.dp))
            UaMismatchBanner(warning)
        }

        Spacer(Modifier.height(12.dp))
        Text("快速设置", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            viewportPresets.forEach { preset ->
                val label = preset.first
                val w = preset.second.first
                val h = preset.second.second
                val active = widthText.toIntOrNull() == w && heightText.toIntOrNull() == h
                val bg = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                         else MaterialTheme.colorScheme.surfaceVariant
                val fg = if (active) MaterialTheme.colorScheme.primary
                         else MaterialTheme.colorScheme.onSurfaceVariant
                Surface(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                    color = bg,
                    onClick = { onPreset(w, h) },
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodySmall,
                            color = fg, fontWeight = FontWeight.Medium)
                        Text("$w × $h", style = MaterialTheme.typography.labelSmall,
                            color = fg, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }

    // 当前状态显示（对标 OpenMinis: 在 Viewport 区域底部显示）
    Spacer(Modifier.height(8.dp))
    val (resW, resH) = BrowserController.resolvedViewportSize()
    val uaLabel = selectedProfile.displayName
    Text(
        if (isCustomActive) {
            "当前：$uaLabel ｜ $resW×$resH（自定义）。点击「默认」恢复 UA 默认值。"
        } else {
            "当前：$uaLabel ｜ $resW×$resH（UA 默认）。设置自定义尺寸以覆盖。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ── UA 不匹配警告（对标 OpenMinis uaMismatchWarning + UaMismatchBanner） ──

private fun uaMismatchWarning(profile: UserAgentProfile, widthText: String): String? {
    val width = widthText.toIntOrNull() ?: return null
    if (width <= 0) return null
    return when (profile) {
        UserAgentProfile.MOBILE, UserAgentProfile.CUSTOM -> {
            if (width >= VIEWPORT_UA_BREAKPOINT) {
                "视口 ≥${VIEWPORT_UA_BREAKPOINT}px 但 UA 为移动端——网站可能提供桌面版布局，显示效果不佳。建议切换到桌面 UA。"
            } else null
        }
        UserAgentProfile.DESKTOP -> {
            if (width < VIEWPORT_UA_BREAKPOINT) {
                "视口 <${VIEWPORT_UA_BREAKPOINT}px 但 UA 为桌面端——网站可能提供移动版布局，显示效果不佳。建议切换到移动 UA。"
            } else null
        }
    }
}

@Composable
private fun UaMismatchBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
            .padding(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(HugeIcons.Alert02, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ── 通用组件 ──

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}
