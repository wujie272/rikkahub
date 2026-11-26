package me.rerere.rikkahub.ui.pages.assistant.groupchat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantSearchMode
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.GroupChatSeatOverrides
import me.rerere.rikkahub.ui.components.ai.McpPickerButton
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.SearchPickerButton
import me.rerere.rikkahub.ui.components.ai.SearchMode
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun SeatCard(
    seatId: Uuid,
    displayName: String,
    assistant: Assistant?,
    defaultEnabled: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
    overrides: GroupChatSeatOverrides,
    onUpdateOverrides: ((GroupChatSeatOverrides) -> GroupChatSeatOverrides) -> Unit,
    settings: Settings,
    onEditPrompt: () -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    val effectiveModelId = overrides.chatModelId ?: assistant?.chatModelId ?: settings.chatModelId

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(spring()),
        colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ── Header row ──
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggleExpand() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UIAvatar(
                    name = displayName,
                    value = assistant?.avatar ?: Avatar.Dummy,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(displayName, style = MaterialTheme.typography.bodyLarge)
                    settings.findModelById(effectiveModelId)?.let { model ->
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Switch(
                    checked = defaultEnabled,
                    onCheckedChange = onToggleEnabled,
                )

                IconButton(onClick = onRemove) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = "移除成员",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // ── Expanded overrides ──
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    // Model override
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "聊天模型",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        ModelSelector(
                            modelId = effectiveModelId,
                            providers = settings.providers,
                            type = ModelType.CHAT,
                            onSelect = { model ->
                                onUpdateOverrides { it.copy(chatModelId = model.id) }
                            },
                        )
                        IconButton(
                            enabled = overrides.chatModelId != null,
                            onClick = { onUpdateOverrides { it.copy(chatModelId = null) } },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                HugeIcons.Cancel01,
                                contentDescription = "清除",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Max tokens
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Max Tokens",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = overrides.maxTokens?.toString() ?: "",
                            onValueChange = { raw ->
                                val tokens = raw.toIntOrNull()?.takeIf { it > 0 }
                                onUpdateOverrides { it.copy(maxTokens = tokens) }
                            },
                            modifier = Modifier.width(120.dp),
                            singleLine = true,
                            placeholder = { Text("默认") },
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Search
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "网络搜索",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        SearchPickerButton(
                            enableSearch = overrides.searchEnabled,
                            settings = settings,
                            onUpdateSearchMode = { mode ->
                                onUpdateOverrides { it.copy(searchEnabled = mode != SearchMode.OFF) }
                            },
                            onUpdateSearchService = { index ->
                                onUpdateOverrides { overrides ->
                                    overrides.copy(
                                        searchEnabled = true,
                                        searchMode = AssistantSearchMode.Provider(index),
                                    )
                                }
                            },
                            model = settings.findModelById(effectiveModelId),
                        )
                    }

                    // MCP
                    if (settings.mcpServers.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        val mcpAssistant = (assistant ?: Assistant(id = seatId)).copy(
                            id = assistant?.id ?: seatId,
                            name = assistant?.name.orEmpty(),
                            avatar = assistant?.avatar ?: Avatar.Dummy,
                            mcpServers = overrides.mcpServerIds,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "MCP 服务器",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            McpPickerButton(
                                assistant = mcpAssistant,
                                servers = settings.mcpServers,
                                mcpManager = mcpManager,
                                onUpdateAssistant = { updated ->
                                    onUpdateOverrides { it.copy(mcpServerIds = updated.mcpServers) }
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Memory
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "使用记忆",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = overrides.memoryEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateOverrides { it.copy(memoryEnabled = enabled) }
                            },
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))

                    // Bottom buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = onEditPrompt) {
                            Text(text = "编辑提示词")
                        }

                        TextButton(onClick = onRemove) {
                            Text(
                                text = "移除成员",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
