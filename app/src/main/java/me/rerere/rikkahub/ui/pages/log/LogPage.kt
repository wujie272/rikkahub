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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.ai.requestlog.AIRequestLogEntity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import org.koin.androidx.compose.koinViewModel
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController


@Composable
fun LogPage(vm: LogVM = koinViewModel()) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val selectedSource by vm.selectedSource.collectAsStateWithLifecycle()
    val errorOnly by vm.errorOnly.collectAsStateWithLifecycle()
    val availableSources by vm.availableSources.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.log_page_title)) },
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
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current

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
                    scope.launch {
                        navController.navigate(Screen.LogDetail(log.id))
                    }
                }
            )
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
                    onSelectSource(source)
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
                    text = if (log.error != null) stringResource(R.string.request_log_status_error) else stringResource(R.string.request_log_status_ok),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
                if (log.stream) {
                    Text(
                        text = stringResource(R.string.request_log_field_stream),
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
                text = stringResource(R.string.log_page_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}



internal fun AIRequestSource.resolveLabel(context: android.content.Context): String {
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
