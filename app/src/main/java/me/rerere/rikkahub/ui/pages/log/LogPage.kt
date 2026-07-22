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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import me.rerere.common.android.appTempFolder
import me.rerere.highlight.HighlightText
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.ai.requestlog.AIRequestLogEntity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.AtomOneDarkPalette
import me.rerere.rikkahub.ui.theme.AtomOneLightPalette
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@Composable
fun LogPage(vm: LogVM = koinViewModel()) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val selectedSource by vm.selectedSource.collectAsStateWithLifecycle()
    val errorOnly by vm.errorOnly.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 可用来源
    val availableSources = remember(logs) {
        logs.mapNotNull { log ->
            runCatching { AIRequestSource.valueOf(log.source) }.getOrNull()
        }.distinct().sortedBy { it.ordinal }
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
                                vm.exportLogs(context)
                            }
                        }
                    ) {
                        Icon(HugeIcons.Share01, stringResource(R.string.log_page_export))
                    }
                    IconButton(
                        onClick = { vm.clearAll() }
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
            logs = logs,
            sources = availableSources,
            selectedSource = selectedSource,
            onSelectSource = { vm.setSourceFilter(it) },
            errorOnly = errorOnly,
            onToggleErrorOnly = { vm.toggleErrorOnly() },
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        )
    }
}

@Composable
private fun UnifiedLogList(
    logs: List<AIRequestLogEntity>,
    sources: List<AIRequestSource>,
    selectedSource: AIRequestSource?,
    onSelectSource: (AIRequestSource?) -> Unit,
    errorOnly: Boolean,
    onToggleErrorOnly: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedLog by remember { mutableStateOf<AIRequestLogEntity?>(null) }
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
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

        items(logs, key = { it.id }, contentType = { "RequestLog" }) { log ->
            RequestLogCard(
                log = log,
                onClick = {
                    selectedLog = log
                    scope.launch { sheetState.show() }
                }
            )
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
    selectedSource: AIRequestSource?,
    onSelectSource: (AIRequestSource?) -> Unit,
    errorOnly: Boolean,
    onToggleErrorOnly: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        FilterChip(
            selected = selectedSource == null && !errorOnly,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSelectSource(null)
                if (errorOnly) onToggleErrorOnly()
            },
            label = { Text(stringResource(R.string.log_page_filter_all)) },
        )
        FilterChip(
            selected = errorOnly,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggleErrorOnly()
            },
            label = {
                Text(
                    text = stringResource(R.string.log_page_filter_error),
                    color = if (errorOnly) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        sources.forEach { source ->
            FilterChip(
                selected = selectedSource == source,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelectSource(if (selectedSource == source) null else source)
                },
                label = { Text(source.displayNameRes()) },
            )
        }
    }
}

@Composable
private fun RequestLogCard(log: AIRequestLogEntity, onClick: () -> Unit) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "log_card_scale",
    )

    val sourceLabel = remember(log.source) {
        runCatching { AIRequestSource.valueOf(log.source).resolveLabel(context = context) }.getOrNull() ?: log.source
    }

    val statusColor = if (log.error != null) {
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
                onClick = onClick,
            )
            .animateContentSize(animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f)),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SourceTag(text = sourceLabel)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = dateFormat.format(Date(log.createdAt)),
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

            if (log.providerName.isNotBlank()) {
                Text(
                    text = "${log.providerName} · ${log.modelDisplayName.ifBlank { log.modelId }}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = log.requestUrl.ifBlank { "-" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )

            val requestPreview = log.requestPreview
            if (requestPreview.isNotBlank()) {
                Text(
                    text = requestPreview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (log.error != null) "Error" else "OK",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
                if (log.stream) {
                    Text(
                        text = "stream",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                log.error?.let { err ->
                    Text(
                        text = err,
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

// ---- Detail Sheet ----

private enum class DetailTab {
    BASIC, REQUEST, RESPONSE, RAW
}

@Composable
private fun RequestLogDetail(log: AIRequestLogEntity) {
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
                    text = { Text(when (tab) {
                        DetailTab.BASIC -> "Basic"
                        DetailTab.REQUEST -> "Request"
                        DetailTab.RESPONSE -> "Response"
                        DetailTab.RAW -> "Raw"
                    }) }
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
private fun BasicTab(log: AIRequestLogEntity) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    val sourceLabel = remember(log.source) {
        runCatching { AIRequestSource.valueOf(log.source).resolveLabel(context = context) }.getOrNull() ?: log.source
    }

    SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SectionTitle("Basic Info") }
            item { DetailSection("Time", dateFormat.format(Date(log.createdAt))) }
            item { DetailSection("Source", sourceLabel) }
            item { DetailSection("Provider", "${log.providerName} (${log.providerType})") }
            if (log.modelDisplayName.isNotBlank() || log.modelId.isNotBlank()) {
                item { DetailSection("Model", log.modelDisplayName.ifBlank { log.modelId }) }
            }
            item { DetailSection("Duration", "${log.durationMs}ms") }
            item { DetailSection("Stream", log.stream.toString()) }
            if (log.requestUrl.isNotBlank()) {
                item { DetailSection("URL", log.requestUrl) }
            }

            if (log.error != null) {
                item { HorizontalDivider() }
                item { SectionTitle("Error", color = MaterialTheme.colorScheme.error) }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            text = log.error,
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
private fun RequestTab(log: AIRequestLogEntity) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (log.requestMessagesJson.isNotBlank()) {
            item { SectionTitle("Request Body") }
            item {
                CodeCard(
                    code = formatJsonOrRaw(log.requestMessagesJson),
                    language = "json",
                )
            }
        }
    }
}

@Composable
private fun ResponseTab(log: AIRequestLogEntity) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (log.responseText.isNotBlank()) {
            item { SectionTitle("Response Text") }
            item {
                CodeCard(
                    code = log.responseText,
                    language = "txt",
                )
            }
        }
        if (log.responseRawText.isNotBlank()) {
            item { HorizontalDivider() }
            item { SectionTitle("Response Raw JSON") }
            item {
                CodeCard(
                    code = formatJsonOrRaw(log.responseRawText),
                    language = "json",
                )
            }
        }
        if (log.responsePreview.isNotBlank()) {
            item { HorizontalDivider() }
            item { SectionTitle("Response Preview") }
            item {
                CodeCard(
                    code = log.responsePreview,
                    language = "txt",
                )
            }
        }
    }
}

@Composable
private fun RawTab(log: AIRequestLogEntity) {
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
                    appendLine("Created At: ${log.createdAt}")
                    appendLine("Source: ${log.source}")
                    appendLine("Provider: ${log.providerName} (${log.providerType})")
                    appendLine("Model: ${log.modelDisplayName.ifBlank { log.modelId }}")
                    appendLine("Stream: ${log.stream}")
                    appendLine("Duration: ${log.durationMs}ms")
                    appendLine("Error: ${log.error}")
                    appendLine()
                    appendLine("--- Request URL ---")
                    appendLine(log.requestUrl)
                    appendLine()
                    appendLine("--- Request Messages ---")
                    appendLine(log.requestMessagesJson.take(5000))
                    appendLine()
                    appendLine("--- Response ---")
                    appendLine(log.responseText.take(5000))
                    appendLine()
                    appendLine("--- Response Raw ---")
                    appendLine(log.responseRawText.take(5000))
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

private fun AIRequestSource.resolveLabel(context: android.content.Context): String {
    return context.getString(
        when (this) {
            AIRequestSource.CHAT -> R.string.log_page_source_chat
            AIRequestSource.TITLE_SUMMARY -> R.string.log_page_source_title_summary
            AIRequestSource.CONTEXT_SUMMARY -> R.string.log_page_source_context_summary
            AIRequestSource.CHAT_SUGGESTION -> R.string.log_page_source_chat_suggestion
            AIRequestSource.GROUP_CHAT_ROUTING -> R.string.log_page_source_group_chat_routing
            AIRequestSource.WELCOME_PHRASES -> R.string.log_page_source_welcome_phrases
            AIRequestSource.MEMORY_CONSOLIDATION -> R.string.log_page_source_memory_consolidation
            AIRequestSource.MEMORY_EMBEDDING -> R.string.log_page_source_memory_embedding
            AIRequestSource.MEMORY_RETRIEVAL -> R.string.log_page_source_memory_retrieval
            AIRequestSource.TOOL_RESULT_EMBEDDING -> R.string.log_page_source_tool_result_embedding
            AIRequestSource.TOOL_RESULT_RAG -> R.string.log_page_source_tool_result_rag
            AIRequestSource.TRANSLATION -> R.string.log_page_source_translation
            AIRequestSource.OCR -> R.string.log_page_source_ocr
            AIRequestSource.DOCUMENT_SUMMARY -> R.string.log_page_source_document_summary
            AIRequestSource.SCHEDULED_MESSAGE -> R.string.log_page_source_scheduled_message
            AIRequestSource.SPONTANEOUS -> R.string.log_page_source_spontaneous
            AIRequestSource.MODEL_NAME_GENERATION -> R.string.log_page_source_model_name_generation
            AIRequestSource.SEARCH_AGENT -> R.string.log_page_source_search_agent
            AIRequestSource.SPEECH_TO_TEXT -> R.string.log_page_source_speech_to_text
            AIRequestSource.OTHER -> R.string.log_page_source_other
        }
    )
}

@Composable
private fun AIRequestSource.displayNameRes(): String {
    return stringResource(
        when (this) {
            AIRequestSource.CHAT -> R.string.log_page_source_chat
            AIRequestSource.TITLE_SUMMARY -> R.string.log_page_source_title_summary
            AIRequestSource.CONTEXT_SUMMARY -> R.string.log_page_source_context_summary
            AIRequestSource.CHAT_SUGGESTION -> R.string.log_page_source_chat_suggestion
            AIRequestSource.GROUP_CHAT_ROUTING -> R.string.log_page_source_group_chat_routing
            AIRequestSource.WELCOME_PHRASES -> R.string.log_page_source_welcome_phrases
            AIRequestSource.MEMORY_CONSOLIDATION -> R.string.log_page_source_memory_consolidation
            AIRequestSource.MEMORY_EMBEDDING -> R.string.log_page_source_memory_embedding
            AIRequestSource.MEMORY_RETRIEVAL -> R.string.log_page_source_memory_retrieval
            AIRequestSource.TOOL_RESULT_EMBEDDING -> R.string.log_page_source_tool_result_embedding
            AIRequestSource.TOOL_RESULT_RAG -> R.string.log_page_source_tool_result_rag
            AIRequestSource.TRANSLATION -> R.string.log_page_source_translation
            AIRequestSource.OCR -> R.string.log_page_source_ocr
            AIRequestSource.DOCUMENT_SUMMARY -> R.string.log_page_source_document_summary
            AIRequestSource.SCHEDULED_MESSAGE -> R.string.log_page_source_scheduled_message
            AIRequestSource.SPONTANEOUS -> R.string.log_page_source_spontaneous
            AIRequestSource.MODEL_NAME_GENERATION -> R.string.log_page_source_model_name_generation
            AIRequestSource.SEARCH_AGENT -> R.string.log_page_source_search_agent
            AIRequestSource.SPEECH_TO_TEXT -> R.string.log_page_source_speech_to_text
            AIRequestSource.OTHER -> R.string.log_page_source_other
        }
    )
}
