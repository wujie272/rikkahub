package me.rerere.rikkahub.ui.components.textselection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.ChatTarget
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@Composable
fun TextSelectionTargetPickerSheet(
    selectedText: String,
    onDismiss: () -> Unit,
    onTargetSelected: (ChatTarget) -> Unit,
) {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    val defaultGroupChatName = stringResource(R.string.group_chat_default_name)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            IconButton(
                onClick = {
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                    }
                },
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.text_selection_send_to_conversation),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = selectedText,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(settings.assistants, key = { _, a -> a.id }) { index, assistant ->
                    Surface(
                        onClick = {
                            onTargetSelected(ChatTarget.Assistant(assistant.id))
                        },
                        shape = listItemShape(index, settings.assistants.lastIndex),
                        color = itemBackgroundColor(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            UIAvatar(
                                name = assistant.name.ifEmpty { defaultAssistantName },
                                value = assistant.avatar,
                                modifier = Modifier.size(40.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = assistant.name.ifEmpty { defaultAssistantName },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = assistant.systemPrompt.ifBlank {
                                        stringResource(R.string.assistant_page_no_system_prompt)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                if (settings.groupChatTemplates.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.group_chat_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }

                    itemsIndexed(settings.groupChatTemplates, key = { _, t -> t.id }) { index, template ->
                        Surface(
                            onClick = {
                                onTargetSelected(ChatTarget.GroupChat(template.id))
                            },
                            shape = listItemShape(index, settings.groupChatTemplates.lastIndex),
                            color = itemBackgroundColor(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    modifier = Modifier.size(40.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                ) {
                                    Icon(
                                        Icons.Rounded.Group,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp).padding(10.dp),
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = template.name.ifBlank { defaultGroupChatName },
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(R.string.group_chat_members_count, template.seats.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun itemBackgroundColor(): Color {
    return if (LocalDarkMode.current) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
}

private fun listItemShape(index: Int, lastIndex: Int): RoundedCornerShape {
    return when {
        lastIndex <= 0 -> RoundedCornerShape(24.dp)
        index == 0 -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 10.dp, bottomEnd = 10.dp)
        index == lastIndex -> RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
        else -> RoundedCornerShape(10.dp)
    }
}
