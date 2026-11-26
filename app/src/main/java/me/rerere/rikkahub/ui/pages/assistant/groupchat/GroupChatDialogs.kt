package me.rerere.rikkahub.ui.pages.assistant.groupchat

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.GroupChatTemplate
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import kotlin.uuid.Uuid

// ──── 删除模板对话框 ────

@Composable
fun DeleteTemplateDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (show) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("删除模板") },
            text = { Text("确定要删除这个群聊模板吗？所有相关配置将丢失。") },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            },
        )
    }
}

// ──── 添加成员 Bottom Sheet ────

@Composable
fun AddMemberBottomSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    availableAssistants: List<Assistant>,
    defaultAssistantName: String,
    onAddSeat: (Uuid) -> Unit,
) {
    if (show) {
        val sheetState = rememberModalBottomSheetState()

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text("选择助手加入群聊", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                if (availableAssistants.isEmpty()) {
                    Text(
                        text = "所有助手已加入群聊",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }

                availableAssistants.forEach { assistant ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onAddSeat(assistant.id)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UIAvatar(
                            name = assistant.name.ifBlank { defaultAssistantName },
                            value = assistant.avatar,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = assistant.name.ifBlank { defaultAssistantName },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ──── 编辑简介对话框 ────

@Composable
fun EditIntroDialog(
    show: Boolean,
    template: GroupChatTemplate?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    if (show && template != null) {
        var localIntro by remember(template.id) { mutableStateOf(template.intro) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("编辑简介") },
            text = {
                OutlinedTextField(
                    value = localIntro,
                    onValueChange = { localIntro = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSave(localIntro)
                    onDismiss()
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        )
    }
}

// ──── 路由模型提示词对话框 ────

@Composable
fun EditHostPromptDialog(
    show: Boolean,
    template: GroupChatTemplate?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    if (show && template != null) {
        var localPrompt by remember(template.id) { mutableStateOf(template.hostSystemPrompt) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("路由模型提示词") },
            text = {
                OutlinedTextField(
                    value = localPrompt,
                    onValueChange = { localPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSave(localPrompt)
                    onDismiss()
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        )
    }
}

// ──── 成员提示词编辑对话框 ────

@Composable
fun EditSeatPromptDialog(
    show: Boolean,
    template: GroupChatTemplate?,
    seatId: Uuid?,
    assistants: List<Assistant>,
    onDismiss: () -> Unit,
    onSave: (Uuid, String?) -> Unit,
) {
    if (show && template != null) {
        val seat = seatId?.let { id -> template.seats.firstOrNull { it.id == id } }
        val assistant = seat?.assistantId?.let { assistantId -> assistants.firstOrNull { it.id == assistantId } }
        val basePrompt = assistant?.systemPrompt.orEmpty()
        val currentOverride = seat?.overrides?.systemPrompt
        var localPrompt by remember(template.id, seatId) { mutableStateOf(currentOverride ?: basePrompt) }

        AlertDialog(
            onDismissRequest = {
                onDismiss()
            },
            title = { Text("编辑成员提示词") },
            text = {
                OutlinedTextField(
                    value = localPrompt,
                    onValueChange = { localPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                )
            },
            confirmButton = {},
            dismissButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = { localPrompt = basePrompt },
                        enabled = localPrompt != basePrompt,
                    ) {
                        Text("恢复默认")
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = {
                            onDismiss()
                        }) {
                            Text("取消")
                        }
                        TextButton(onClick = {
                            val resolvedSeatId = seatId ?: return@TextButton
                            val normalized = localPrompt.takeIf { it != basePrompt }
                            onSave(resolvedSeatId, normalized)
                            onDismiss()
                        }) {
                            Text("保存")
                        }
                    }
                }
            },
        )
    }
}

