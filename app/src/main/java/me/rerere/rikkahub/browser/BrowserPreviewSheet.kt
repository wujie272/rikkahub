package me.rerere.rikkahub.browser

import android.webkit.WebView
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Globe
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Tick01
import java.net.URLEncoder

/**
 * 聊天内嵌浏览器预览 Sheet
 *
 * 以 ModalBottomSheet 形式在 ChatPage 内部弹出，显示 WebView 实时预览。
 * AI 驱动浏览时覆盖呼吸灯遮罩，用户可手动接管或关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserPreviewSheet(
    onDismiss: () -> Unit,
    initialUrl: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // ── 多标签页状态：从 BrowserController 的 Tab Pool 读取 ──
    val tabs by BrowserController.tabs.collectAsState()
    val selectedTabIndex by BrowserController.selectedTabIndex.collectAsState()
    val selectedTab = tabs.getOrNull(selectedTabIndex)

    // 从当前选中标签的 StateFlow 读取
    val currentUrl = selectedTab?.currentUrl?.collectAsState()?.value.orEmpty()
    val pageTitle = selectedTab?.pageTitle?.collectAsState()?.value ?: ""
    val isLoading = selectedTab?.isLoading?.collectAsState()?.value ?: false
    val canGoBack = selectedTab?.canGoBack?.collectAsState()?.value ?: false
    val canGoForward = selectedTab?.canGoForward?.collectAsState()?.value ?: false

    val isAgentBusy by BrowserController.taskWindowActiveFlow().collectAsState()
    val actions by BrowserController.recentActionsFlow().collectAsState()

    var urlInput by remember(currentUrl) { mutableStateOf(currentUrl) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }

    // ── 下载管理（从 BrowserController 读取） ──
    val downloads by BrowserController.downloadsFlow.collectAsState()

    // ── 历史记录 ──
    val history by BrowserController.historyFlow.collectAsState()

    val accent = MaterialTheme.colorScheme.primary

    // 加载初始 URL
    // 启动空闲回收定时器
    LaunchedEffect(Unit) {
        BrowserController.startIdleSweep()
    }
    LaunchedEffect(initialUrl) {
        if (initialUrl != null && selectedTab != null) {
            selectedTab.loadUrl(initialUrl)
        }
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = {
            onDismiss()
        },
        dragHandle = { CompactDragHandle() },
    ) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            // ── 标题栏 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧：UA 设置按钮
                IconButton(
                    onClick = { showSettings = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        HugeIcons.SmartPhone01,
                        "UA 设置",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // 居中：标题
                Text(
                    text = "Minis Computer",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.weight(1f))

                // 右侧：关闭按钮（点击后销毁所有浏览器标签页）
                IconButton(
                    onClick = {
                        BrowserController.releaseAllTabs()
                        onDismiss()
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        HugeIcons.Cancel01,
                        "关闭浏览器",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // ── Tab Bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 新建标签
                IconButton(
                    onClick = { BrowserController.createTab(context) },
                    enabled = tabs.size < 3 && !isAgentBusy,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(HugeIcons.Add01, "新建标签", modifier = Modifier.size(20.dp))
                }

                // 标签芯片
                LazyRow(
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        val tabTitle by tab.pageTitle.collectAsState()
                        val tabUrl by tab.currentUrl.collectAsState()
                        val domain = try { java.net.URI(tabUrl).host } catch (_: Exception) { null }
                        val displayTitle = tabTitle.ifEmpty { domain ?: "Tab ${tab.id}" }.take(20)
                        val isSelected = tab.id == selectedTab?.id
                        val tabIndex = tabs.indexOf(tab)

                        TabChip(
                            title = displayTitle,
                            isSelected = isSelected,
                            showClose = tabs.size > 1 && !isAgentBusy,
                            accent = accent,
                            onClick = { BrowserController.selectTab(tabIndex) },
                            onClose = { BrowserController.closeTab(tabIndex) },
                        )
                    }
                    item {
                        Text(
                            "${tabs.size}/3",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }

                // 历史按钮
                IconButton(
                    onClick = { showHistory = true },
                    enabled = !isAgentBusy,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(HugeIcons.TransactionHistory, "历史", modifier = Modifier.size(20.dp))
                }
            }

            // ── URL 地址栏 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 地球图标
                    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        Icon(HugeIcons.Globe, null, modifier = Modifier.size(14.dp), tint = if (isLoading) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (isLoading) {
                            val transition = rememberInfiniteTransition(label = "urlSpin")
                            val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "angle")
                            CircularProgressIndicator(modifier = Modifier.size(22.dp).rotate(angle), color = accent, strokeWidth = 1.5.dp)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            singleLine = true,
                            enabled = !isAgentBusy,
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                val trimmed = urlInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    val normalized = normalizeUrl(trimmed)
                                    selectedTab?.loadUrl(normalized)
                                    urlInput = normalized
                                }
                                keyboardController?.hide(); focusManager.clearFocus()
                            }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (urlInput.isEmpty()) {
                            Text("搜索或输入网址", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (isLoading) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { selectedTab?.webView?.stopLoading() }, modifier = Modifier.size(28.dp)) {
                            Icon(HugeIcons.Cancel01, "停止", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // 加载进度条（不确定进度）
            AnimatedVisibility(visible = isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            }

            // ── WebView 区域 ──
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                selectedTab?.let { tab ->
                    BrowserWebView(
                        webView = tab.webView,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // AI 驾驶遮罩
                if (isAgentBusy) {
                    AgentBrowsingOverlay(accent = accent, onTakeover = { BrowserController.stopCurrentTask() })
                }
            }

            // ── 下载进度条 ──
            val activeDownload = downloads.lastOrNull { !it.completed }
            if (activeDownload != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(HugeIcons.Download04, null, modifier = Modifier.size(16.dp), tint = accent)
                    Text(activeDownload.filename, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (activeDownload.progress >= 0) {
                        LinearProgressIndicator(progress = { activeDownload.progress }, modifier = Modifier.weight(1f).height(3.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.weight(1f).height(3.dp))
                    }
                }
            }

            // ── AI 状态条 ──
            if (actions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(HugeIcons.Sparkles, null, modifier = Modifier.size(16.dp), tint = accent)
                    Text(actions.first(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }

            // ── 底部工具栏 ──
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selectedTab?.goBack() }, enabled = canGoBack && !isAgentBusy) {
                    Icon(HugeIcons.ArrowLeft01, "后退", modifier = Modifier.size(22.dp), tint = if (!canGoBack || isAgentBusy) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = { selectedTab?.goForward() }, enabled = canGoForward && !isAgentBusy) {
                    Icon(HugeIcons.ArrowRight01, "前进", modifier = Modifier.size(22.dp), tint = if (!canGoForward || isAgentBusy) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurface)
                }
                // 下载按钮（带角标）
                if (downloads.isNotEmpty()) {
                    BadgedBox(badge = { if (downloads.count { !it.completed } > 0) Badge { Text("${downloads.count { !it.completed }}") } }) {
                        IconButton(onClick = { showDownloads = true }, enabled = true) {
                            Icon(HugeIcons.Download04, "下载", tint = accent)
                        }
                    }
                }
                if (isLoading) {
                    IconButton(onClick = { selectedTab?.stopLoading() }, enabled = !isAgentBusy) {
                        Icon(HugeIcons.Cancel01, "停止", modifier = Modifier.size(22.dp), tint = if (isAgentBusy) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f) else accent)
                    }
                } else {
                    IconButton(onClick = { selectedTab?.reload() }, enabled = !isAgentBusy) {
                        Icon(HugeIcons.Refresh, "刷新", modifier = Modifier.size(22.dp), tint = if (isAgentBusy) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }

    // 设置 Sheet
    if (showSettings) {
        BrowserSettingsSheet(webView = selectedTab?.webView, onDismiss = { showSettings = false })
    }

    // 历史 Sheet
    if (showHistory) {
        BrowserHistorySheet(
            history = history,
            onNavigate = { url ->
                selectedTab?.loadUrl(url)
                showHistory = false
            },
            onDismiss = { showHistory = false },
        )
    }

    // 下载 Sheet
    if (showDownloads) {
        BrowserDownloadsSheet(downloads = BrowserController.downloadsFlow.value, onDismiss = { showDownloads = false })
    }

    // 注意：下滑关闭 Sheet 时不销毁 WebView（AI 可能还在后台操作）。
    // 点击标题栏右侧 ✕ 按钮才会销毁所有标签页并关闭 Sheet。
    // 会话由以下机制清理：
    //   1. 点击 ✕ 按钮显式关闭
    //   2. AI 停止调工具 15s 后自动过期释放 inUse
    //   3. 空闲回收定时器轮询清理过期标签页
    //   4. 新会话接管时跳过旧会话
}

@Composable
private fun TabChip(
    title: String,
    isSelected: Boolean,
    showClose: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val bg = if (isSelected) accent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest
    val borderColor = if (isSelected) accent.copy(alpha = 0.4f) else androidx.compose.ui.graphics.Color.Transparent
    val textColor = if (isSelected) accent else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .background(bg, CircleShape)
            .border(1.dp, borderColor, CircleShape)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = if (showClose) 6.dp else 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showClose) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onClose, modifier = Modifier.size(16.dp)) {
                Icon(HugeIcons.Cancel01, "关闭", modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


/**
 * 呼吸灯遮罩
 */
@Composable
private fun AgentBrowsingOverlay(
    accent: Color,
    onTakeover: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "breathing")
    val breathingAlpha by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "breathingAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(8.dp).background(accent.copy(alpha = breathingAlpha), CircleShape))
            Spacer(Modifier.width(12.dp))
            Text("AI 正在浏览", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.width(1.dp).height(14.dp).background(Color.White.copy(alpha = 0.3f)))
            Spacer(Modifier.width(12.dp))
            Text("接管", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onTakeover))
        }
    }
}

/**
 * WebView 封装
 *
 * 关键：通过 OnTouchListener 调用 requestDisallowInterceptTouchEvent(true)，
 * 阻止父 View（ModalBottomSheet）劫持触摸事件，确保 WebView 可滚动。
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun BrowserWebView(
    webView: WebView,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> FrameLayout(context) },
        update = { container ->
            val mounted = container.getChildAt(0)
            if (mounted !== webView) {
                container.removeAllViews()
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_MOVE,
                        MotionEvent.ACTION_POINTER_DOWN ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        onRelease = { container ->
            container.removeAllViews()
        },
        modifier = modifier,
    )
}


/**
 * 紧凑拖拽手柄
 */
@Composable
private fun CompactDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(2.dp),
                ),
        )
    }
}

/** 规范化 URL 输入 */
private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.contains("://")) return trimmed
    if (trimmed.contains(' ') || !trimmed.contains('.')) {
        val template = BrowserController.currentSearchEngineUrlTemplate()
        return "$template${URLEncoder.encode(trimmed, "UTF-8")}"
    }
    return "https://$trimmed"
}
