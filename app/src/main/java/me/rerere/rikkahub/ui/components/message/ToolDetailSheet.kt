package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Globe
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.browser.BrowserController
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.ui.components.message.tools.rememberSystemResourceMonitor
import kotlinx.coroutines.flow.collect
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.text.selection.SelectionContainer

// ─── OpenMinis 颜色常量 ────────────────────────────────────────────────
private val ToolCheckColor = Color(0xFF34C759) // iOS green
private val ToolCancelColor = Color(0xFFFFCC00) // iOS yellow
private val ToolMemoryAccent = Color(0xFFFF2D55) // iOS pink
private val ShellGreen = Color(0xFF34C759)
private val EditOrange = Color(0xFFFF9500)
private val BrowserActionBlue = Color(0xFF007AFF)
private val BrowserUrlBg = Color(0xFFD9D9D9)
private val BrowserUrlText = Color(0xFF595959)
private const val VIEWPORT_UA_BREAKPOINT = 768

/**
 * 实时浏览器截图 —— 每 3s 轮询 WebView，对标 OpenMinis 的 rememberBrowserLiveSnapshot。
 *
 * 当 browser_use 工具正在运行时，以固定间隔（3s）从 WebView 捕获当前帧。
 * 轮询方案确保浮窗截图持续更新，不会因为事件丢失而漏帧。
 *
 * @param toolName 工具名称，仅 "browser_use" 时生效
 * @param isLive 工具是否仍在运行/流式传输
 * @param intervalMs 轮询间隔（默认 3000ms，对标 OpenMinis）
 * @return 当前 WebView 的 Bitmap 截图，或 null（无绑定/工具未运行）
 */
@Composable
private fun rememberBrowserLiveSnapshot(
    toolName: String,
    isLive: Boolean,
    intervalMs: Long = 3000L,
): Bitmap? {
    if (toolName !in BrowserToolDefaults.ALL_TOOLS) return null
    if (!isLive) return null
    if (!BrowserController.isBound()) return null
    if (!BrowserController.hasActivePage()) return null

    val snapshot by produceState<Bitmap?>(
        initialValue = null,
        key1 = toolName,
        key2 = isLive,
    ) {
        // 首次捕获
        val first = BrowserController.captureLiveSnapshot()
        if (first != null) value = first
        // 每 3s 轮询（对标 OpenMinis 无限循环）
        while (true) {
            kotlinx.coroutines.delay(intervalMs)
            val next = BrowserController.captureLiveSnapshot() ?: continue
            value = next
        }
    }
    return snapshot
}

/**
 * 工具详情 Sheet — 对标 OpenMinis 的 ToolDetailSheet ("Minis Computer")
 *
 * 统一容器：顶部导航栏 + 工具专属内容 + 底部状态/翻页栏
 * 渲染在 ChatPage 层面（LazyColumn 外部），通过 ViewModel 状态控制
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailSheet(
    toolBlocks: List<UIMessagePart.Tool>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onOpenBrowserForUrl: (String) -> Unit = {},
) {
    if (toolBlocks.isEmpty()) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var currentIdx by remember { mutableStateOf(initialIndex.coerceIn(0, toolBlocks.lastIndex.coerceAtLeast(0))) }
    val tool = toolBlocks.getOrNull(currentIdx) ?: return onDismiss()

    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val isLive = tool.approvalState is ToolApprovalState.Pending
    val context = remember(tool, currentIdx) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = if (tool.isExecuted) {
                runCatching {
                    JsonInstant.parseToJsonElement(
                        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    )
                }.getOrElse { JsonObject(emptyMap()) }
            } else {
                null
            },
            loading = isLive,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
        ) {
            // ── Top Nav Bar ──
            TopNavBar(
                tool = tool,
                isLive = isLive,
                onDismiss = onDismiss,
                onOpenBrowserForUrl = onOpenBrowserForUrl,
            )

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

            // ── Content Area ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                ToolContent(
                    tool = tool,
                    context = context,
                    isLive = isLive,
                    renderer = renderer,
                )
            }

            // ── Bottom Bar ──
            BottomBar(
                tool = tool,
                isLive = isLive,
                currentIdx = currentIdx,
                totalCount = toolBlocks.size,
                onPrev = { if (currentIdx > 0) currentIdx-- },
                onNext = { if (currentIdx < toolBlocks.lastIndex) currentIdx++ },
            )
        }
    }
}

@Composable
private fun TopNavBar(
    tool: UIMessagePart.Tool,
    isLive: Boolean,
    onDismiss: () -> Unit,
    onOpenBrowserForUrl: (String) -> Unit = {},
) {
    val clipboardManager = LocalClipboardManager.current
    var copyDone by remember { mutableStateOf(false) }
    val outputText = remember(tool.output) {
        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    }
    val isShellTool = tool.toolName.startsWith("shell_") || tool.toolName == "termux_run_command"
    val isBrowserTool = tool.toolName in BrowserToolDefaults.ALL_TOOLS
    val args = remember(tool) { tool.inputAsJson().jsonObject }

    // 提取命令（shell_execute）或 URL（browser_use）
    val command = remember(args) {
        args["command"]?.jsonPrimitive?.contentOrNull ?: ""
    }
    val browserUrl = remember(args) {
        args["url"]?.jsonPrimitive?.contentOrNull ?: ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clip(CircleShape)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                HugeIcons.Cancel01,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Minis Computer",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clip(CircleShape)
                .clickable {
                    when {
                        isShellTool && command.isNotEmpty() -> {
                            // shell_execute: 复制命令到剪贴板
                            clipboardManager.setText(AnnotatedString(command))
                            copyDone = true
                        }
                        isBrowserTool && browserUrl.isNotEmpty() -> {
                            // browser_use: 打开浏览器预览 Sheet
                            onOpenBrowserForUrl(browserUrl)
                            onDismiss()
                        }
                        outputText.isNotEmpty() -> {
                            // 其他工具: 复制输出到剪贴板
                            clipboardManager.setText(AnnotatedString(outputText))
                            copyDone = true
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when {
                    copyDone -> HugeIcons.Tick01
                    isShellTool -> HugeIcons.ComputerTerminal01
                    isBrowserTool -> HugeIcons.Globe
                    else -> HugeIcons.Copy01
                },
                contentDescription = when {
                    isShellTool -> "Copy command"
                    isBrowserTool -> "Open in browser"
                    else -> "Copy"
                },
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp),
            )
        }
        LaunchedEffect(copyDone) {
            if (copyDone) {
                kotlinx.coroutines.delay(2000)
                copyDone = false
            }
        }
    }
}

@Composable
private fun BottomBar(
    tool: UIMessagePart.Tool,
    isLive: Boolean,
    currentIdx: Int,
    totalCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val context = remember(tool) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = null,
            loading = false,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(bottom = 8.dp),
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isLive) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = ToolCheckColor,
                    strokeWidth = 2.dp,
                )
            } else {
                val (icon, tint) = when (tool.approvalState) {
                    is ToolApprovalState.Answered -> HugeIcons.CheckmarkCircle01 to ToolCheckColor
                    is ToolApprovalState.Denied -> HugeIcons.Cancel01 to ToolCancelColor
                    else -> HugeIcons.CheckmarkCircle01 to ToolCheckColor
                }
                Icon(
                    icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = renderer.title(context),
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tool.toolName,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (tool.executionStartedAt != null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (tool.executionStartedAt != null && !isLive) {
                        val elapsed = System.currentTimeMillis() - tool.executionStartedAt!!
                        Text(
                            text = formatDuration(elapsed),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (tool.executionStartedAt != null) {
                        val startTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date(tool.executionStartedAt!!))
                        Text(
                            text = startTime,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onPrev,
                enabled = currentIdx > 0,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    HugeIcons.ArrowLeft01,
                    contentDescription = "Previous",
                    tint = if (currentIdx > 0) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isLive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(ToolCheckColor, CircleShape),
                    )
                    Text(
                        "Live",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Text(
                    "${currentIdx + 1} / ${totalCount}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(
                onClick = onNext,
                enabled = currentIdx < totalCount - 1,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = "Next",
                    tint = if (currentIdx < totalCount - 1) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolContent(
    tool: UIMessagePart.Tool,
    context: ToolUIContext,
    isLive: Boolean,
    renderer: me.rerere.rikkahub.ui.components.message.tools.ToolUIRenderer,
) {
    val outputText = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val args = tool.inputAsJson().jsonObject

    when {
        tool.toolName.startsWith("shell_") || tool.toolName == "termux_run_command" -> ShellContent(args, outputText, isLive)
        tool.toolName == "file_edit" -> FileEditContent(args, outputText, isLive)
        tool.toolName.startsWith("file_") -> FileReadWriteContent(tool, args, outputText, isLive)
        tool.toolName in BrowserToolDefaults.ALL_TOOLS -> BrowserContent(tool, args, outputText, isLive)
        tool.toolName.startsWith("memory_") -> MemoryContent(tool, args, outputText)
        tool.toolName == "read_image" -> ReadImageContent(tool, outputText)
        else -> {
            // 未显式处理的工具：显示纯文本输出（不 fallback 到 renderer.Preview）
            if (outputText.isNotEmpty()) {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = outputText,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 18.sp,
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No output",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShellContent(
    args: JsonObject,
    outputText: String,
    isLive: Boolean,
) {
    val command = args["command"]?.jsonPrimitive?.contentOrNull ?: ""
    val scrollState = rememberScrollState()
    val hudReservePadding = if (isLive) 28.dp else 0.dp
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = 12.dp, bottom = 16.dp),
    ) {
        val cardMinHeight = maxWidth * 3f / 4f
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .heightIn(min = cardMinHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black)
                .border(0.5.dp, Color(0xFF404040), RoundedCornerShape(10.dp)),
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .verticalScroll(scrollState)
                        .padding(14.dp)
                        .padding(bottom = hudReservePadding),
                ) {
                    Text(
                        text = "$ $command",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                        lineHeight = 18.sp,
                    )
                    if (outputText.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = outputText,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = ShellGreen,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
            // 运行中 CPU/MEM HUD（真实数据，对标 OpenMinis SystemResourceMonitor）
            if (isLive) {
                val sheetMonitor = rememberSystemResourceMonitor(active = true)
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xFF141414))
                        .padding(vertical = 2.5.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = sheetMonitor.formattedCpu(),
                        fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace, color = ShellGreen,
                    )
                    Text(
                        text = sheetMonitor.formattedMem(),
                        fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace, color = ShellGreen,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileEditContent(
    args: JsonObject,
    outputText: String,
    isLive: Boolean,
) {
    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val oldStr = args["old_string"]?.jsonPrimitive?.contentOrNull ?: ""
    val newStr = args["new_string"]?.jsonPrimitive?.contentOrNull ?: ""
    val fileName = if (path.contains("/")) path.substringAfterLast("/") else path
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF0F0F0)
    val cardBorder = if (isDark) Color(0xFF404040) else Color(0xFFD1D1D1)
    val redBg = if (isDark) Color(0xFF4D1414) else Color(0xFFFFE5E5)
    val redText = if (isDark) Color(0xFFFF6666) else Color(0xFFCC1A1A)
    val greenBg = if (isDark) Color(0xFF144D14) else Color(0xFFE5FFE5)
    val greenText = if (isDark) Color(0xFF66FF66) else Color(0xFF1A991A)
    val scrollState = rememberScrollState()

    val bytes = (oldStr.toByteArray(Charsets.UTF_8).size + newStr.toByteArray(Charsets.UTF_8).size)
    val sizeLabel = when {
        isLive -> "streaming…"
        bytes < 1024 -> "$bytes B"
        else -> "${bytes / 1024} KB"
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(12.dp),
    ) {
        val cardMinHeight = maxWidth * 3f / 4f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .heightIn(min = cardMinHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(cardBg)
                .border(0.5.dp, cardBorder, RoundedCornerShape(10.dp)),
        ) {
            // 标题栏 —— 文件名 + 大小标签
            Row(
                modifier = Modifier.fillMaxWidth().background(if (isDark) Color(0xFF212121) else Color(0xFFEBEBEB)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HugeIcons.Edit01, null, tint = EditOrange, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(fileName.ifEmpty { "(file)" }, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = if (isDark) Color.White else Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Text("($sizeLabel)", fontSize = 11.sp, color = if (isLive) EditOrange.copy(alpha = 0.8f) else if (isDark) Color(0xFF888888) else Color(0xFF999999))
            }
            HorizontalDivider(thickness = 0.5.dp, color = cardBorder)

            // Diff 正文
            SelectionContainer {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    if (oldStr.isNotEmpty()) {
                        oldStr.lines().forEach { line ->
                            Text("- $line", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = redText, lineHeight = 17.sp,
                                modifier = Modifier.fillMaxWidth().background(redBg).padding(horizontal = 14.dp, vertical = 2.dp))
                        }
                    }
                    if (newStr.isNotEmpty()) {
                        newStr.lines().forEach { line ->
                            Text("+ $line", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = greenText, lineHeight = 17.sp,
                                modifier = Modifier.fillMaxWidth().background(greenBg).padding(horizontal = 14.dp, vertical = 2.dp))
                        }
                    }
                }
            }

            // 底部 —— 完成后显示 "Edited <path>"
            if (!isLive && path.isNotEmpty()) {
                Spacer(Modifier.weight(1f))
                HorizontalDivider(thickness = 0.5.dp, color = cardBorder)
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Edited", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (isDark) Color.White else Color.Black)
                        if (path.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text(path, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (isDark) Color(0xFF888888) else Color(0xFF666666), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileReadWriteContent(
    tool: UIMessagePart.Tool,
    args: JsonObject,
    outputText: String,
    isLive: Boolean,
) {
    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val fileName = if (path.contains("/")) path.substringAfterLast("/") else path
    val displayText = if (tool.toolName == "file_write") {
        args["content"]?.jsonPrimitive?.contentOrNull ?: outputText
    } else {
        outputText
    }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF0F0F0)
    val headerBg = if (isDark) Color(0xFF212121) else Color(0xFFEBEBEB)
    val cardBorder = if (isDark) Color(0xFF404040) else Color(0xFFD1D1D1)
    val scrollState = rememberScrollState()
    val bytes = displayText.toByteArray(Charsets.UTF_8).size
    val sizeLabel = if (bytes >= 1024) String.format("%.1f KB", bytes / 1024.0) else "$bytes B"

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(cardBg)
                .border(0.5.dp, cardBorder, RoundedCornerShape(10.dp)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().background(headerBg).padding(vertical = 10.dp, horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(HugeIcons.File02, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(fileName.ifEmpty { "file" }, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("($sizeLabel)", fontSize = 11.sp, color = if (isLive) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(thickness = 0.5.dp, color = cardBorder)

            if (displayText.isNotEmpty()) {
                LazyRevealText(
                    text = displayText,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun BrowserContent(
    tool: UIMessagePart.Tool,
    args: JsonObject,
    outputText: String,
    isLive: Boolean = false,
) {
    val action = args["action"]?.jsonPrimitive?.contentOrNull ?: ""
    val resolvedUrl = args["url"]?.jsonPrimitive?.contentOrNull ?: ""
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()

    // 实时截图轮询 —— 工具运行时每 3s 从 WebView 拉一帧
    val liveBitmap = rememberBrowserLiveSnapshot(
        toolName = tool.toolName,
        isLive = isLive,
    )

    // 对标 OpenMinis：produceState + BitmapFactory.decodeFile 直接解码保存截图
    val savedImagePath = images.firstOrNull()?.url
        ?.removePrefix("file://")
    val savedBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = savedImagePath,
    ) {
        value = if (savedImagePath == null) null else withContext(Dispatchers.IO) {
            try { BitmapFactory.decodeFile(savedImagePath) } catch (_: Exception) { null }
        }
    }

    val screenshotBitmap = liveBitmap ?: savedBitmap

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(top = 12.dp, bottom = 16.dp),
    ) {
        if (action.isNotEmpty() || resolvedUrl.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (action.isNotEmpty()) {
                    Text(action, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        modifier = Modifier.background(BrowserActionBlue, CircleShape).padding(horizontal = 10.dp, vertical = 2.dp))
                }
                if (resolvedUrl.isNotEmpty()) {
                    Text(resolvedUrl, fontSize = 12.sp, color = BrowserUrlText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).background(BrowserUrlBg, CircleShape)
                            .clip(CircleShape)
                            .clickable { clipboardManager.setText(AnnotatedString(resolvedUrl)) }
                            .padding(horizontal = 10.dp, vertical = 2.dp))
                }
            }
        }

        // 截图（对标 OpenMinis：liveBitmap → savedBitmap 统一渲染）
        if (screenshotBitmap != null) {
            Spacer(Modifier.height(12.dp))
            val aspect = screenshotBitmap.width.toFloat() / screenshotBitmap.height.coerceAtLeast(1)
            Image(
                bitmap = screenshotBitmap.asImageBitmap(),
                contentDescription = "Browser screenshot",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .aspectRatio(aspect)
                    .shadow(8.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
            )
        } else if (isLive && BrowserController.isBound() && BrowserController.hasActivePage()) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(200.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    Text("Loading...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Result（对标 OpenMinis：block.content）
        if (outputText.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(HugeIcons.Globe, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Result", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SelectionContainer {
                    Text(outputText, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface, lineHeight = 16.sp, modifier = Modifier.padding(14.dp))
                }
            }
        }
    }
}

@Composable
private fun MemoryContent(
    tool: UIMessagePart.Tool,
    args: JsonObject,
    outputText: String,
) {
    val memContent = if (tool.toolName == "memory_write") {
        args["content"]?.jsonPrimitive?.contentOrNull ?: outputText
    } else {
        outputText
    }
    val keywords = args["keywords"]?.jsonPrimitive?.contentOrNull ?: ""
    val prefix = if (keywords.isNotEmpty() && tool.toolName == "memory_get") "Keywords: $keywords\n\n" else ""
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF0F0F0)
    val headerBg = if (isDark) Color(0xFF212121) else Color(0xFFEBEBEB)
    val cardBorder = if (isDark) Color(0xFF404040) else Color(0xFFD1D1D1)
    val accentColor = ToolMemoryAccent
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(cardBg)
                .border(0.5.dp, cardBorder, RoundedCornerShape(10.dp)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().background(headerBg).padding(vertical = 10.dp, horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(HugeIcons.AiBrain01, null, tint = accentColor.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                Text(tool.toolName, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = accentColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(thickness = 0.5.dp, color = cardBorder)
            if ((prefix + memContent).isNotEmpty()) {
                LazyRevealText(
                    text = prefix + memContent,
                    color = accentColor.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun ReadImageContent(
    tool: UIMessagePart.Tool,
    outputText: String,
) {
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
    ) {
        if (images.isNotEmpty()) {
            images.forEach { img ->
                ZoomableAsyncImage(
                    model = img.url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        if (outputText.isNotEmpty()) {
            Text(outputText, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp)
        }
    }
}

// ─── Lazy text reveal for large outputs ────────────────────────────────
private const val LAZY_CHUNK_LINES = 40
private const val LAZY_INITIAL_CHUNKS = 5
private const val LAZY_BATCH_CHUNKS = 5
private const val LAZY_INITIAL_BYTE_CAP = 10 * 1024

@Composable
private fun LazyRevealText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val chunks = remember(text) { chunkText(text) }
    var revealed by remember(text) { mutableStateOf(initialChunks(chunks)) }
    val total = chunks.size
    val shownText = remember(text, revealed) {
        chunks.take(revealed.coerceIn(1, total.coerceAtLeast(1))).joinToString("\n")
    }

    Column(modifier = modifier) {
        Text(
            shownText,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            lineHeight = 18.sp,
        )
        if (revealed < total) {
            val remainingChunks = total - revealed
            val nextLines = minOf(LAZY_BATCH_CHUNKS, remainingChunks) * LAZY_CHUNK_LINES
            val remainingLines = remainingChunks * LAZY_CHUNK_LINES
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            ) {
                Text(
                    "Load more ($nextLines lines)",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color.copy(alpha = 0.9f),
                    modifier = Modifier.clickable { revealed = (revealed + LAZY_BATCH_CHUNKS).coerceAtMost(total) },
                )
                Text(
                    "Load all (~$remainingLines)",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color.copy(alpha = 0.9f),
                    modifier = Modifier.clickable { revealed = total },
                )
            }
        }
    }
}

private fun chunkText(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split("\n")
    val out = ArrayList<String>((lines.size / LAZY_CHUNK_LINES) + 1)
    var i = 0
    while (i < lines.size) {
        val end = minOf(i + LAZY_CHUNK_LINES, lines.size)
        out.add(lines.subList(i, end).joinToString("\n"))
        i = end
    }
    return out
}

private fun initialChunks(chunks: List<String>): Int {
    if (chunks.isEmpty()) return 0
    var count = 0
    var bytes = 0
    for (chunk in chunks.take(LAZY_INITIAL_CHUNKS)) {
        bytes += chunk.toByteArray(Charsets.UTF_8).size
        count++
        if (bytes >= LAZY_INITIAL_BYTE_CAP) break
    }
    return count.coerceAtLeast(1)
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    return when {
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        seconds > 0 -> "${seconds}.${(ms % 1000) / 100}s"
        else -> "${ms}ms"
    }
}

private fun Color.luminance(): Float {
    return 0.299f * red + 0.587f * green + 0.114f * blue
}
