package me.rerere.rikkahub.ui.pages.setting.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import android.content.Intent
import me.rerere.rikkahub.browser.BrowserController
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * Settings → Browser. Three sections:
 *
 *  1. Browser — "Open browser" (launches BrowserActivity at about:blank for one-time
 *     manual use like signing into a site before the AI takes over) + "Clear browsing
 *     data" (wipes WebView profile dir + cookies; does NOT clear per-tool toggles —
 *     those are user config, not browsing data).
 *  2. Tools enabled — 22 individually-togglable browser tools. Read tools default ON,
 *     write tools default OFF. Per the spec, the per-tool granularity
 *     is intentional — the AI controlling a real browser is the highest-trust surface
 *     in the app, so the user must be able to grant only what they trust.
 *  3. Defaults & limits — search engine, per-tool timeout, single-task timeout.
 *     The two timeouts are editable (GitHub issue #4): values are clamped into a
 *     generous-but-bounded range in BrowserPreferences.
 */
@Composable
fun SettingBrowserPage(
    vm: SettingBrowserViewModel = koinViewModel(),
) {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val toolStates by vm.toolStates.collectAsStateWithLifecycle()
    val perToolTimeoutMs by vm.perToolTimeoutMs.collectAsStateWithLifecycle()
    val singleTaskTimeoutMs by vm.singleTaskTimeoutMs.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showClearConfirm by remember { mutableStateOf(false) }
    val cleared = stringResource(R.string.setting_browser_clear_data_done)

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.setting_browser_clear_data_confirm_title)) },
            text = { Text(stringResource(R.string.setting_browser_clear_data_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    vm.clearBrowsingData(ctx) {
                        toaster.show(cleared, type = ToastType.Success)
                    }
                }) {
                    Text(stringResource(R.string.setting_browser_clear_data_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_browser_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Section: Browser
            CardGroup(
                title = { Text(stringResource(R.string.setting_browser_section_browser)) },
            ) {
                item(
                    onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("about:blank"))) }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_browser_open)) },
                    supportingContent = { Text(stringResource(R.string.setting_browser_open_desc)) },
                )
                item(
                    onClick = { showClearConfirm = true },
                    headlineContent = { Text(stringResource(R.string.setting_browser_clear_data)) },
                    supportingContent = { Text(stringResource(R.string.setting_browser_clear_data_desc)) },
                )
            }

            // Section: Tools enabled — three sub-CardGroups grouped by category, each with
            // a small heading above. Mirrors AssistantLocalToolPage's category-divider pattern.
            Text(
                text = stringResource(R.string.setting_browser_tools_enabled_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )

            ToolCategorySection(
                heading = stringResource(R.string.setting_browser_category_read),
                tools = BrowserToolDefaults.READ_TOOLS.toList()
                    .filter { it in BrowserToolDefaults.ALL_TOOLS }
                    // Stable display order — preserves the spec's table sequence.
                    .sortedBy { BrowserToolDefaults.ALL_TOOLS.indexOf(it) },
                toolStates = toolStates,
                onToggle = vm::setToolEnabled,
            )
            ToolCategorySection(
                heading = stringResource(R.string.setting_browser_category_write),
                tools = BrowserToolDefaults.WRITE_TOOLS.toList()
                    .sortedBy { BrowserToolDefaults.ALL_TOOLS.indexOf(it) },
                toolStates = toolStates,
                onToggle = vm::setToolEnabled,
            )

            // Section: Defaults & limits
            CardGroup(
                title = { Text(stringResource(R.string.setting_browser_section_defaults)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_browser_search_engine)) },
                    supportingContent = { Text(stringResource(R.string.setting_browser_search_engine_desc)) },
                    trailingContent = {
                        val searchEngines = BrowserToolDefaults.SEARCH_ENGINES
                        val prefs = remember { ctx.getSharedPreferences("browser_settings", android.content.Context.MODE_PRIVATE) }
                        var currentIndex by remember {
                            mutableStateOf(
                                prefs.getInt("search_engine_index", BrowserToolDefaults.DEFAULT_SEARCH_ENGINE_INDEX)
                                    .coerceIn(0, searchEngines.size - 1)
                            )
                        }
                        me.rerere.rikkahub.ui.components.ui.Select(
                            options = searchEngines.indices.toList(),
                            selectedOption = currentIndex,
                            onOptionSelected = { idx ->
                                currentIndex = idx
                                BrowserController.searchEngineIndex = idx
                                prefs.edit().putInt("search_engine_index", idx).apply()
                            },
                            optionToString = { searchEngines[it].name },
                            modifier = Modifier.width(150.dp),
                        )
                    },
                )
                // Per-tool timeout — editable, expressed in seconds. Clamped to 10 s..10 min
                // in BrowserPreferences before persist (GitHub issue #4).
                item(
                    headlineContent = { Text(stringResource(R.string.setting_browser_per_tool_timeout)) },
                    supportingContent = { Text(stringResource(R.string.setting_browser_per_tool_timeout_desc)) },
                    trailingContent = {
                        TimeoutInput(
                            currentValue = perToolTimeoutMs / 1_000L,
                            unitLabel = stringResource(R.string.setting_browser_per_tool_timeout_unit),
                            onCommit = vm::setPerToolTimeoutSeconds,
                        )
                    },
                )
                // Single-task timeout — editable, expressed in minutes. Clamped to
                // 1 min..60 min in BrowserPreferences before persist.
                item(
                    headlineContent = { Text(stringResource(R.string.setting_browser_single_task_timeout)) },
                    supportingContent = { Text(stringResource(R.string.setting_browser_single_task_timeout_desc)) },
                    trailingContent = {
                        TimeoutInput(
                            currentValue = singleTaskTimeoutMs / 60_000L,
                            unitLabel = stringResource(R.string.setting_browser_single_task_timeout_unit),
                            onCommit = vm::setSingleTaskTimeoutMinutes,
                        )
                    },
                )
            }
        }
    }
}

/**
 * Compact numeric input for a timeout row's trailing slot. [currentValue] is the persisted
 * value in display units (seconds or minutes); editing is buffered in local state and
 * committed on focus loss. The persisted value is clamped in [BrowserPreferences], so an
 * out-of-range entry snaps back to the nearest bound — the StateFlow round-trip refreshes
 * [currentValue] and the buffer follows it.
 */
@Composable
private fun TimeoutInput(
    currentValue: Long,
    unitLabel: String,
    onCommit: (Long) -> Unit,
) {
    // Local edit buffer. Re-seeds whenever the persisted value changes (including the
    // clamp-corrected value flowing back after a commit), so the field never goes stale.
    var text by remember(currentValue) { mutableStateOf(currentValue.toString()) }

    OutlinedTextField(
        value = text,
        onValueChange = { new -> text = new.filter { it.isDigit() }.take(4) },
        singleLine = true,
        suffix = { Text(unitLabel, style = MaterialTheme.typography.bodySmall) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .width(132.dp)
            .onFocusChanged { focus ->
                if (!focus.isFocused) {
                    val parsed = text.toLongOrNull()
                    if (parsed != null && parsed != currentValue) {
                        onCommit(parsed)
                    } else {
                        // Empty / unchanged — restore the canonical display value.
                        text = currentValue.toString()
                    }
                }
            },
    )
}

@Composable
private fun ToolCategorySection(
    heading: String,
    tools: List<String>,
    toolStates: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
) {
    Text(
        text = heading,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
    CardGroup {
        tools.forEach { toolName ->
            val checked = toolStates[toolName] ?: BrowserToolDefaults.DEFAULT_ENABLED[toolName] ?: false
            item(
                headlineContent = { Text(toolDisplayTitle(toolName)) },
                supportingContent = { Text(toolDisplayDesc(toolName)) },
                trailingContent = {
                    Switch(
                        checked = checked,
                        onCheckedChange = { onToggle(toolName, it) },
                    )
                },
            )
        }
    }
}

private fun toolDisplayTitle(toolName: String): String = when (toolName) {
        BrowserToolDefaults.NAVIGATE -> "Open URL"
        BrowserToolDefaults.GET_PAGE_INFO -> "Current URL"
        BrowserToolDefaults.SCREENSHOT -> "Screenshot"
        BrowserToolDefaults.GET_TEXT -> "Get text"
        BrowserToolDefaults.GET_READABLE -> "Readable"
        BrowserToolDefaults.GET_BACKBONE -> "DOM backbone"
        BrowserToolDefaults.FETCH -> "Fetch"
        BrowserToolDefaults.GET_COOKIES -> "Get cookies"
        BrowserToolDefaults.SET_COOKIES -> "Set cookies"
        BrowserToolDefaults.LIST_TABS -> "List tabs"
        BrowserToolDefaults.NEW_TAB -> "New tab"
        BrowserToolDefaults.CLOSE_TAB -> "Close tab"
        BrowserToolDefaults.FIND_ELEMENTS -> "Find elements"
        BrowserToolDefaults.WAIT_FOR_DOM_STABLE -> "Wait for element"
        BrowserToolDefaults.CLICK -> "Click"
        BrowserToolDefaults.TYPE -> "Type"
        BrowserToolDefaults.SCROLL -> "Scroll"
        BrowserToolDefaults.EXECUTE_JS -> "Run JavaScript"
        BrowserToolDefaults.HOVER -> "Hover"
        BrowserToolDefaults.SET_USER_AGENT -> "Set UA"
        BrowserToolDefaults.SET_VIEWPORT -> "Set viewport"
        BrowserToolDefaults.SCROLL_AND_COLLECT -> "Scroll & collect"
        else -> toolName
    }

@Composable
    private fun toolDisplayDesc(toolName: String): String = when (toolName) {
        BrowserToolDefaults.NAVIGATE -> stringResource(R.string.setting_browser_tool_open_desc)
        BrowserToolDefaults.GET_PAGE_INFO -> stringResource(R.string.setting_browser_tool_current_url_desc)
        BrowserToolDefaults.SCREENSHOT -> stringResource(R.string.setting_browser_tool_screenshot_desc)
        BrowserToolDefaults.GET_TEXT -> stringResource(R.string.setting_browser_tool_get_text_desc)
        BrowserToolDefaults.GET_READABLE -> stringResource(R.string.setting_browser_tool_readable_desc)
        BrowserToolDefaults.GET_BACKBONE -> stringResource(R.string.setting_browser_tool_get_dom_desc)
        BrowserToolDefaults.FETCH -> stringResource(R.string.setting_browser_tool_fetch_desc)
        BrowserToolDefaults.GET_COOKIES -> stringResource(R.string.setting_browser_tool_get_cookies_desc)
        BrowserToolDefaults.SET_COOKIES -> stringResource(R.string.setting_browser_tool_set_cookies_desc)
        BrowserToolDefaults.LIST_TABS -> stringResource(R.string.setting_browser_tool_list_tabs_desc)
        BrowserToolDefaults.NEW_TAB -> stringResource(R.string.setting_browser_tool_new_tab_desc)
        BrowserToolDefaults.CLOSE_TAB -> stringResource(R.string.setting_browser_tool_close_tab_desc)
        BrowserToolDefaults.FIND_ELEMENTS -> stringResource(R.string.setting_browser_tool_find_elements_desc)
        BrowserToolDefaults.WAIT_FOR_DOM_STABLE -> stringResource(R.string.setting_browser_tool_wait_for_desc)
        BrowserToolDefaults.CLICK -> stringResource(R.string.setting_browser_tool_click_desc)
        BrowserToolDefaults.TYPE -> stringResource(R.string.setting_browser_tool_type_desc)
        BrowserToolDefaults.SCROLL -> stringResource(R.string.setting_browser_tool_scroll_desc)
        BrowserToolDefaults.EXECUTE_JS -> stringResource(R.string.setting_browser_tool_eval_js_desc)
        BrowserToolDefaults.HOVER -> stringResource(R.string.setting_browser_tool_hover_desc)
        BrowserToolDefaults.SET_USER_AGENT -> stringResource(R.string.setting_browser_tool_set_ua_desc)
        BrowserToolDefaults.SET_VIEWPORT -> stringResource(R.string.setting_browser_tool_set_viewport_desc)
        BrowserToolDefaults.SCROLL_AND_COLLECT -> stringResource(R.string.setting_browser_tool_scroll_and_collect_desc)
        else -> ""
    }
