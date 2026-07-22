package me.rerere.rikkahub.ui.pages.log

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonElement
import me.rerere.highlight.HighlightText
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.ai.requestlog.AIRequestLogEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.AtomOneDarkPalette
import me.rerere.rikkahub.ui.theme.AtomOneLightPalette
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DetailTab {
    BASIC, REQUEST, RESPONSE, RAW
}

@Composable
fun LogDetailPage(
    id: Long,
    vm: LogDetailVM = koinViewModel(parameters = { parametersOf(id) }),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val log by vm.log.collectAsStateWithLifecycle(initialValue = null)
    val pagerState = rememberPagerState(pageCount = { DetailTab.values().size })
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.request_log_detail_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                DetailTab.values().forEachIndexed { index, tab ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = { Text(when (tab) {
                            DetailTab.BASIC -> stringResource(R.string.request_log_section_basic)
                            DetailTab.REQUEST -> stringResource(R.string.request_log_section_request)
                            DetailTab.RESPONSE -> stringResource(R.string.request_log_section_response)
                            DetailTab.RAW -> stringResource(R.string.request_log_section_raw)
                        }) }
                    )
                }
            }

            log?.let { entity ->
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (DetailTab.values()[page]) {
                        DetailTab.BASIC -> BasicTab(entity)
                        DetailTab.REQUEST -> RequestTab(entity)
                        DetailTab.RESPONSE -> ResponseTab(entity)
                        DetailTab.RAW -> RawTab(entity)
                    }
                }
            }
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
            item { SectionTitle(stringResource(R.string.request_log_section_basic)) }
            item { DetailSection(stringResource(R.string.request_log_field_time), dateFormat.format(Date(log.createdAt))) }
            item { DetailSection(stringResource(R.string.request_log_field_source), sourceLabel) }
            item { DetailSection(stringResource(R.string.request_log_field_provider), "${log.providerName} (${log.providerType})") }
            if (log.modelDisplayName.isNotBlank() || log.modelId.isNotBlank()) {
                item { DetailSection(stringResource(R.string.request_log_field_model), log.modelDisplayName.ifBlank { log.modelId }) }
            }
            item { DetailSection(stringResource(R.string.request_log_field_duration), "${log.durationMs}ms") }
            item { DetailSection(stringResource(R.string.request_log_field_stream), log.stream.toString()) }
            if (log.requestUrl.isNotBlank()) {
                item { DetailSection(stringResource(R.string.request_log_field_url), log.requestUrl) }
            }

            if (log.error != null) {
                val errorText = log.error
                val clipboard = LocalContext.current.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

                item { HorizontalDivider() }
                item {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SectionTitle(
                            text = stringResource(R.string.request_log_section_error),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText("error", errorText)
                                )
                            }
                        ) {
                            Icon(
                                imageVector = HugeIcons.Copy01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            text = errorText,
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
            item { SectionTitle(stringResource(R.string.request_log_section_request)) }
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
        if (log.responseRawText.isNotBlank()) {
            item { SectionTitle(stringResource(R.string.request_log_section_response_raw)) }
            item {
                CodeCard(
                    code = formatJsonOrRaw(log.responseRawText),
                    language = detectLogLanguage(log.responseRawText),
                )
            }
        }
        if (log.responseText.isNotBlank()) {
            item { HorizontalDivider() }
            item { SectionTitle(stringResource(R.string.request_log_section_response_filtered)) }
            item {
                CodeCard(
                    code = log.responseText,
                    language = detectLogLanguage(log.responseText),
                )
            }
        }
    }
}

private fun detectLogLanguage(content: String): String {
    val trimmed = content.trim()
    if (trimmed.isBlank()) return "txt"
    return runCatching {
        JsonInstant.parseToJsonElement(trimmed)
        "json"
    }.getOrElse { "txt" }
}

@Composable
private fun RawTab(log: AIRequestLogEntity) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle(stringResource(R.string.request_log_section_raw)) }
        item {
            CodeCard(
                code = buildString {
                    appendLine("${stringResource(R.string.request_log_field_time)}: ${log.createdAt}")
                    appendLine("${stringResource(R.string.request_log_field_source)}: ${log.source}")
                    appendLine("${stringResource(R.string.request_log_field_provider)}: ${log.providerName} (${log.providerType})")
                    appendLine("${stringResource(R.string.request_log_field_model)}: ${log.modelDisplayName.ifBlank { log.modelId }}")
                    appendLine("${stringResource(R.string.request_log_field_stream)}: ${log.stream}")
                    appendLine("${stringResource(R.string.request_log_field_duration)}: ${log.durationMs}ms")
                    appendLine("${stringResource(R.string.request_log_field_error)}: ${log.error}")
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
private fun SectionTitle(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
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
        JsonInstantPretty.encodeToString(JsonElement.serializer(), element)
    }.getOrElse { raw }
}
