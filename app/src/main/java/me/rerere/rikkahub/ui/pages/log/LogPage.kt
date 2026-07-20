package me.rerere.rikkahub.ui.pages.log

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Share01
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.common.android.appTempFolder
import me.rerere.highlight.HighlightText
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.ai.displayName
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.AtomOneDarkPalette
import me.rerere.rikkahub.ui.theme.AtomOneLightPalette
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@Composable
fun LogPage() {
    var logs by remember { mutableStateOf(Logging.getRecentLogs()) }
    var requestLoggingEnabled by remember { mutableStateOf(Logging.isRequestLoggingEnabled()) }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var errorOnly by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 计算可用来源
    val availableSources = remember(logs) {
        logs.mapNotNull { log ->
            if (log is LogEntry.RequestLog && log.source.isNotBlank()) {
                runCatching { AIRequestSource.valueOf(log.source) }.getOrNull()
            } else null
        }.distinct().sortedBy { it.ordinal }
    }

    // 筛选后的日志
    val filteredLogs = remember(logs, selectedSource, errorOnly) {
        logs.filter { log ->
            val matchesSource = if (selectedSource != null && log is LogEntry.RequestLog) {
                log.source == selectedSource
            } else true
            val matchesError = if (errorOnly && log is LogEntry.RequestLog) {
                log.error != null
            } else true
            matchesSource && matchesError
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Logs") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                exportLogs(context)
                            }
                        }
                    ) {
                        Icon(HugeIcons.Share01, stringResource(R.string.log_page_export))
                    }
                    IconButton(
                        onClick = {
                            Logging.clear()
                            logs = Logging.getRecentLogs()
                            selectedSource = null
                            errorOnly = false
                        }
                    ) {
                        Icon(HugeIcons.Delete01, null)
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        UnifiedLogList(
            logs = filteredLogs,
            requestLoggingEnabled = requestLoggingEnabled,
            onRequestLoggingChange = {
                requestLoggingEnabled = it
                Logging.setRequestLoggingEnabled(it)
            },
            sources = availableSources,
            selectedSource = selectedSource,
            onSelectSource = { selectedSource = it },
            errorOnly = errorOnly,
            onToggleErrorOnly = { errorOnly = !errorOnly },
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        )
    }
}

@Composable
private fun UnifiedLogList(
    logs: List<LogEntry>,
    requestLoggingEnabled: Boolean,
    onRequestLoggingChange: (Boolean) -> Unit,
    sources: List<AIRequestSource>,
    selectedSource: String?,
    onSelectSource: (String?) -> Unit,
    errorOnly: Boolean,
    onToggleErrorOnly: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedLog by remember { mutableStateOf<LogEntry.RequestLog?>(null) }
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            RequestLoggingSwitchCard(
                enabled = requestLoggingEnabled,
                onEnabledChange = onRequestLoggingChange
            )
        }

        if (sources.isNotEmpty()) {
            item {
                SourceFilterRow(
                    sources = sources,
                    selectedSource = selectedSource,
                    onSelectSource = onSelectSource,
                    errorOnly = errorOnly,
                    onToggleErrorOnly = onToggleErrorOnly,
                )
            }
        }

        if (logs.isEmpty()) {
            item {
                EmptyState(modifier = Modifier.fillParentMaxSize())
            }
        }

        items(logs, key = { it.id }, contentType = { it.javaClass.simpleName }) { log ->
            when (log) {
                is LogEntry.RequestLog -> RequestLogCard(
                    log = log,
                    onClick = {
                        selectedLog = log
                        scope.launch { sheetState.show() }
                    }
                )

                is LogEntry.TextLog -> TextLogCard(log = log)
            }
        }
    }

    selectedLog?.let { log ->
        ModalBottomSheet(
            onDismissRequest = { selectedLog = null },
            sheetState = sheetState
        ) {
            RequestLogDetail(log)
        }
    }
}

@Composable
private fun SourceFilterRow(
    sources: List<AIRequestSource>,
    selectedSource: String?,
    onSelectSource: (String?) -> Unit,
    errorOnly: Boolean,
    onToggleErrorOnly: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        FilterChip(
            selected = selectedSource == null && !errorOnly,
            onClick = {
                onSelectSource(null)
                if (errorOnly) onToggleErrorOnly()
            },
            label = { Text("All") },
        )
        FilterChip(
            selected = errorOnly,
            onClick = onToggleErrorOnly,
            label = {
                Text(
                    text = "Errors Only",
                    color = if (errorOnly) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        sources.forEach { source ->
            FilterChip(
                selected = selectedSource == source.name,
                onClick = { onSelectSource(if (selectedSource == source.name) null else source.name) },
                label = { Text(source.displayName()) },
            )
        }
    }
}

@Composable
private fun RequestLoggingSwitchCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.log_page_record_requests),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.log_page_record_requests_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}

@Composable
private fun RequestLogCard(log: LogEntry.RequestLog, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "log_card_scale",
    )

    val sourceLabel = remember(log.source) {
        if (log.source.isNotBlank()) {
            runCatching { AIRequestSource.valueOf(log.source).displayName() }.getOrNull() ?: log.source
        } else null
    }

    val statusColor = if (log.error != null) {
        MaterialTheme.colorScheme.error
    } else if (log.responseCode != null && log.responseCode !in 200..299) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    onClick()
                }
            )
            .animateContentSize(animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f)),
        shape = MaterialTheme.shapes.medium,
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 第一行：来源标签 + 时间 + 耗时
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (sourceLabel != null) {
                    SourceTag(text = sourceLabel)
                    Spacer(Modifier.width(8.dp))
                } else {
                    Text(
                        text = log.method,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Text(
                    text = dateFormat.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                log.durationMs?.let { duration ->
                    Text(
                        text = "${duration}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 第二行：Provider · Model（AI 日志）或 URL（HTTP 日志）
            if (log.providerName.isNotBlank()) {
                Text(
                    text = "${log.providerName} · ${log.modelDisplayName.ifBlank { log.modelId }}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // URL
            Text(
                text = log.url.ifBlank { "-" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // 第三行：状态 + 错误
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                log.responseCode?.let { code ->
                    Text(
                        text = "HTTP $code",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                    )
                }
                log.stream.let { isStream ->
                    if (isStream) {
                        Text(
                            text = "stream",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                log.error?.let { err ->
                    Text(
                        text = "Error: $err",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceTag(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun TextLogCard(log: LogEntry.TextLog) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = log.tag,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = dateFormat.format(Date(log.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = log.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetbrainsMono
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Delete01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            Text(
                text = "No request logs yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- Detail Sheet with Tabs ----

private enum class DetailTab {
    BASIC, REQUEST, RESPONSE, RAW
}

@Composable
private fun RequestLogDetail(log: LogEntry.RequestLog) {
    var tabIndex by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        PrimaryTabRow(selectedTabIndex = tabIndex) {
            DetailTab.values().forEachIndexed { index, tab ->
                Tab(
                    selected = tabIndex == index,
                    onClick = { tabIndex = index },
                    text = {
                        Text(
                            text = when (tab) {
                                DetailTab.BASIC -> "Basic"
                                DetailTab.REQUEST -> "Request"
                                DetailTab.RESPONSE -> "Response"
                                DetailTab.RAW -> "Raw"
                            }
                        )
                    }
                )
            }
        }

        when (DetailTab.values()[tabIndex]) {
            DetailTab.BASIC -> BasicTab(log)
            DetailTab.REQUEST -> RequestTab(log)
            DetailTab.RESPONSE -> ResponseTab(log)
            DetailTab.RAW -> RawTab(log)
        }
    }
}

@Composable
private fun BasicTab(log: LogEntry.RequestLog) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    val sourceLabel = remember(log.source) {
        if (log.source.isNotBlank()) {
            runCatching { AIRequestSource.valueOf(log.source).displayName() }.getOrNull() ?: log.source
        } else "-"
    }

    SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SectionTitle("Basic Info") }
            item { DetailSection("Time", dateFormat.format(Date(log.timestamp))) }
            item { DetailSection("Source", sourceLabel) }
            item { DetailSection("Method", log.method) }
            if (log.providerName.isNotBlank()) {
                item { DetailSection("Provider", log.providerName) }
            }
            if (log.modelDisplayName.isNotBlank() || log.modelId.isNotBlank()) {
                item { DetailSection("Model", log.modelDisplayName.ifBlank { log.modelId }) }
            }
            log.responseCode?.let { item { DetailSection("Status Code", it.toString()) } }
            log.durationMs?.let { item { DetailSection("Duration", "${it}ms") } }
            item { DetailSection("Stream", log.stream.toString()) }
            if (log.url.isNotBlank()) {
                item { DetailSection("URL", log.url) }
            }

            val logError = log.error
            if (logError != null) {
                item { HorizontalDivider() }
                item { SectionTitle("Error", color = MaterialTheme.colorScheme.error) }
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = logError,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetbrainsMono,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestTab(log: LogEntry.RequestLog) {
    val darkMode = LocalDarkMode.current
    val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (log.requestHeaders.isNotEmpty()) {
            item { SectionTitle("Request Headers") }
            log.requestHeaders.forEach { (key, value) ->
                item { HeaderItem(key, value) }
            }
        }

        if (log.requestBody != null) {
            val requestBody = log.requestBody
            item { HorizontalDivider() }
            item { SectionTitle("Request Body") }
            item {
                CodeCard(
                    code = formatJsonOrRaw(requestBody ?: ""),
                    language = "json",
                )
            }
        }
    }
}

@Composable
private fun ResponseTab(log: LogEntry.RequestLog) {
    val darkMode = LocalDarkMode.current
    val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (log.responseHeaders.isNotEmpty()) {
            item { SectionTitle("Response Headers") }
            log.responseHeaders.forEach { (key, value) ->
                item { HeaderItem(key, value) }
            }
        }

        val responseText = log.responseText
        val responseRawText = log.responseRawText
        if (responseText.isNotBlank()) {
            item { HorizontalDivider() }
            item { SectionTitle("Response Text (Filtered)") }
            item {
                CodeCard(
                    code = responseText,
                    language = "txt",
                )
            }
        }

        if (responseRawText.isNotBlank()) {
            item { HorizontalDivider() }
            item { SectionTitle("Response Raw JSON") }
            item {
                CodeCard(
                    code = formatJsonOrRaw(responseRawText),
                    language = "json",
                )
            }
        }
    }
}

@Composable
private fun RawTab(log: LogEntry.RequestLog) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle("Raw Log Entry") }
        item {
            CodeCard(
                code = buildString {
                    appendLine("ID: ${log.id}")
                    appendLine("Timestamp: ${log.timestamp}")
                    appendLine("Tag: ${log.tag}")
                    appendLine("Source: ${log.source}")
                    appendLine("URL: ${log.url}")
                    appendLine("Method: ${log.method}")
                    appendLine("Provider: ${log.providerName}")
                    appendLine("Model: ${log.modelDisplayName.ifBlank { log.modelId }}")
                    appendLine("Stream: ${log.stream}")
                    appendLine("Response Code: ${log.responseCode}")
                    appendLine("Duration: ${log.durationMs}ms")
                    appendLine("Error: ${log.error}")
                    appendLine()
                    appendLine("--- Request Headers ---")
                    log.requestHeaders.forEach { (k, v) -> appendLine("$k: $v") }
                    appendLine()
                    appendLine("--- Request Body ---")
                    appendLine(log.requestBody ?: "-")
                    appendLine()
                    appendLine("--- Response Headers ---")
                    log.responseHeaders.forEach { (k, v) -> appendLine("$k: $v") }
                    appendLine()
                    appendLine("--- Response Raw ---")
                    appendLine((log.responseRawText ?: "").take(5000))
                },
                language = "txt",
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

@Composable
private fun DetailSection(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = JetbrainsMono
        )
    }
}

@Composable
private fun HeaderItem(key: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = JetbrainsMono
        )
    }
}

@Composable
private fun CodeCard(
    code: String,
    language: String,
) {
    val darkMode = LocalDarkMode.current
    val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
    val horizontalScrollState = rememberScrollState()

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        SelectionContainer {
            HighlightText(
                code = code.ifBlank { "-" },
                language = language,
                modifier = Modifier
                    .padding(12.dp)
                    .horizontalScroll(horizontalScrollState),
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                colors = colorPalette,
                overflow = TextOverflow.Visible,
                softWrap = false,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun formatJsonOrRaw(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    return runCatching {
        val element = JsonInstantPretty.parseToJsonElement(trimmed)
        JsonInstantPretty.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element)
    }.getOrElse { raw }
}

private suspend fun exportLogs(context: android.content.Context) {
    withContext(Dispatchers.IO) {
        val logs = Logging.getRecentLogs()
        if (logs.isEmpty()) return@withContext

        val json = JsonInstantPretty.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(LogEntry.serializer()),
            logs
        )

        val filename = "logs-export-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.json"
        val dir = context.appTempFolder
        val file = dir.resolve(filename)
        file.createNewFile()
        FileOutputStream(file).use { it.write(json.toByteArray()) }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        withContext(Dispatchers.Main) {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(intent, context.getString(R.string.log_page_export))
            )
        }
    }
}
