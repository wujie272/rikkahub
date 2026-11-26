package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.TOOL_RESULT_KEEP_USER_MESSAGES_MAX
import me.rerere.rikkahub.data.datastore.TOOL_RESULT_KEEP_USER_MESSAGES_MIN
import me.rerere.rikkahub.data.datastore.getToolResultKeepUserMessages
import me.rerere.rikkahub.data.datastore.ToolResultHistoryMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@Composable
fun SettingAdvancedPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(
            settings.copy(
                displaySetting = setting
            )
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lazyListState = rememberLazyListState()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("高级设置") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            state = lazyListState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ⏱ 超时与重试
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("⏱ 超时与重试") },
                ) {
                    item(
                        headlineContent = { Text("嵌入检索超时") },
                        supportingContent = { Text("超时后将跳过该次检索，单位：秒") },
                        trailingContent = {
                            var timeoutText by remember(displaySetting.embeddingRetrievalTimeoutSeconds) {
                                mutableStateOf(displaySetting.embeddingRetrievalTimeoutSeconds.toString())
                            }
                            OutlinedTextField(
                                value = timeoutText,
                                onValueChange = { value ->
                                    val filtered = value.filter { it.isDigit() }
                                    val parsed = filtered.toIntOrNull()
                                    val safe = parsed?.coerceAtLeast(1)
                                    timeoutText = (safe ?: filtered).toString()
                                    if (safe != null) {
                                        updateDisplaySetting(displaySetting.copy(embeddingRetrievalTimeoutSeconds = safe))
                                    }
                                },
                                modifier = Modifier.widthIn(min = 80.dp, max = 120.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    )
                    item(
                        headlineContent = { Text("MCP 工具调用超时") },
                        supportingContent = { Text("单位：秒") },
                        trailingContent = {
                            var timeoutText by remember(displaySetting.mcpToolCallTimeoutSeconds) {
                                mutableStateOf(displaySetting.mcpToolCallTimeoutSeconds.toString())
                            }
                            OutlinedTextField(
                                value = timeoutText,
                                onValueChange = { value ->
                                    val filtered = value.filter { it.isDigit() }
                                    val parsed = filtered.toIntOrNull()
                                    val safe = parsed?.coerceAtLeast(1)
                                    timeoutText = (safe ?: filtered).toString()
                                    if (safe != null) {
                                        updateDisplaySetting(displaySetting.copy(mcpToolCallTimeoutSeconds = safe))
                                    }
                                },
                                modifier = Modifier.widthIn(min = 80.dp, max = 120.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    )
                }
            }

            // HTTP 重试
            item {
                HttpRetrySection(
                    displaySetting = displaySetting,
                    onUpdate = ::updateDisplaySetting
                )
            }

            // 🛠 工具结果优化
            item {
                val keepUserMessages = displaySetting.getToolResultKeepUserMessages()
                val discardOldToolResults = displaySetting.toolResultHistoryMode != ToolResultHistoryMode.KEEP_ALL

                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("🛠 工具结果优化") },
                ) {
                    item(
                        headlineContent = { Text("丢弃历史工具结果") },
                        supportingContent = { Text("超过 $keepUserMessages 条用户消息的工具结果将被丢弃") },
                        trailingContent = {
                            Switch(
                                checked = discardOldToolResults,
                                onCheckedChange = { enabled ->
                                    updateDisplaySetting(
                                        displaySetting.copy(
                                            toolResultHistoryMode = if (enabled) {
                                                ToolResultHistoryMode.DISCARD
                                            } else {
                                                ToolResultHistoryMode.KEEP_ALL
                                            }
                                        )
                                    )
                                }
                            )
                        }
                    )
                }

                if (discardOldToolResults) {
                    IntegerSliderSettingItem(
                        title = "保留范围",
                        subtitle = "保留最近 $keepUserMessages 条用户消息内的工具结果",
                        value = keepUserMessages,
                        valueText = keepUserMessages.toString(),
                        valueRange = TOOL_RESULT_KEEP_USER_MESSAGES_MIN..TOOL_RESULT_KEEP_USER_MESSAGES_MAX,
                        onValueChange = { value ->
                            updateDisplaySetting(displaySetting.copy(toolResultKeepUserMessages = value))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HttpRetrySection(
    displaySetting: DisplaySetting,
    onUpdate: (DisplaySetting) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "HTTP 重试",
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
        )

        IntegerSliderSettingItem(
            title = "HTTP 最大重试次数",
            subtitle = "遇到临时服务或网络错误时自动重试",
            value = displaySetting.httpRetryMaxRetries,
            valueText = if (displaySetting.httpRetryMaxRetries == 0) "关闭" else "${displaySetting.httpRetryMaxRetries} 次",
            valueRange = 0..10,
            onValueChange = { retries ->
                onUpdate(displaySetting.copy(httpRetryMaxRetries = retries))
            }
        )
        IntegerSliderSettingItem(
            title = "HTTP 重试间隔",
            subtitle = "每次重试前等待的时间",
            value = displaySetting.httpRetryDelaySeconds,
            valueText = "${displaySetting.httpRetryDelaySeconds} 秒",
            valueRange = 1..30,
            onValueChange = { delaySeconds ->
                onUpdate(displaySetting.copy(httpRetryDelaySeconds = delaySeconds))
            }
        )
    }
}

@Composable
private fun IntegerSliderSettingItem(
    title: String,
    subtitle: String,
    value: Int,
    valueText: String,
    valueRange: IntRange,
    onValueChange: (Int) -> Unit,
) {
    val safeValue = value.coerceIn(valueRange.first, valueRange.last)
    var sliderValue by remember(safeValue, valueRange.first, valueRange.last) {
        mutableFloatStateOf(safeValue.toFloat())
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (LocalDarkMode.current) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { newValue ->
                    val roundedValue = newValue
                        .roundToInt()
                        .coerceIn(valueRange.first, valueRange.last)
                    if (roundedValue != sliderValue.roundToInt()) {
                        onValueChange(roundedValue)
                    }
                    sliderValue = roundedValue.toFloat()
                },
                valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                steps = (valueRange.last - valueRange.first - 1).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
