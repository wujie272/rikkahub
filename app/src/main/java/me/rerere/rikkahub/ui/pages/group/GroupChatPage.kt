package me.rerere.rikkahub.ui.pages.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.UserMultiple
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.GroupMemberEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.util.Locale
import kotlin.uuid.Uuid

/** Stable avatar color palette for member initials. */
private val avatarColors = listOf(
    Color(0xFFE67E22), // 橙
    Color(0xFF2ECC71), // 绿
    Color(0xFF9B59B6), // 紫
    Color(0xFF1ABC9C), // 青
    Color(0xFF3498DB), // 蓝
    Color(0xFFE74C3C), // 红
    Color(0xFFF39C12), // 黄
    Color(0xFF2980B9), // 深蓝
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatPage(id: String) {
    val vm: GroupChatVM = koinViewModel(parameters = { parametersOf(id) })
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val groupId = group?.id
    val groupName = group?.name ?: ""
    val memberCount = members.size

    // Resolve member display names from settings.assistants
    val memberDisplayNames = remember(members, settings) {
        val assistantMap = settings.assistants.associate { a -> a.id.toString() to a.name }
        members.mapNotNull { member ->
            val name = assistantMap[member.assistantId]
            if (name != null) member.assistantId to name else null
        }.toMap()
    }

    val speakerStrategy = when (group?.speakerStrategy) {
        "PROBABILITY_BASED" -> "概率"
        "ROUND_ROBIN" -> "轮询"
        "PRIORITY_BASED" -> "优先级"
        "RANDOM" -> "随机"
        else -> group?.speakerStrategy ?: ""
    }

    val chatListState = rememberLazyListState()

    // Initial scroll to bottom
    androidx.compose.runtime.LaunchedEffect(conversation.messageNodes.size) {
        if (conversation.messageNodes.isNotEmpty()) {
            chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
        }
    }

    Scaffold(
        topBar = {
            GroupChatTopBar(
                groupName = groupName,
                memberCount = memberCount,
                members = members,
                memberDisplayNames = memberDisplayNames,
                speakerStrategy = speakerStrategy,
                onBack = { navController.popBackStack() },
                onGroupManagement = if (groupId != null) {
                    { navController.navigate(Screen.GroupDetail(id = groupId)) }
                } else null,
            )
        },
        bottomBar = {
            GroupChatInputBar(
                vm = vm,
                loadingJob = loadingJob,
                settings = settings,
                chatListState = chatListState,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // Chat message list (reuses the same composable pattern as ChatPage)
        GroupChatList(
            innerPadding = innerPadding,
            conversation = conversation,
            state = chatListState,
            loading = loadingJob != null,
            processingStatus = processingStatus,
            errors = errors,
            settings = settings,
            onDismissError = { vm.dismissError(it) },
            onClearAllErrors = { vm.clearAllErrors() },
            onRegenerate = { message: UIMessage -> vm.regenerateAtMessage(message) },
            onEdit = { message ->
                vm.inputState.editingMessage = message.id
                vm.inputState.setContents(message.parts)
            },
            onDelete = { message -> vm.deleteMessage(message) },
            onToggleFavorite = { vm.toggleMessageFavorite(it) },
            onToolApproval = { toolCallId, approved, reason, scope, toolName ->
                vm.handleToolApproval(toolCallId, approved, reason, scope, toolName)
            },
            onToolAnswer = { toolCallId, answer ->
                vm.handleToolAnswer(toolCallId, answer)
            },
            onTranslate = { message, locale ->
                vm.translateMessage(message, locale)
            },
            onClearTranslation = { message -> vm.clearTranslationField(message.id) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupChatTopBar(
    groupName: String,
    memberCount: Int,
    members: List<GroupMemberEntity>,
    memberDisplayNames: Map<String, String>,
    speakerStrategy: String,
    onBack: () -> Unit,
    onGroupManagement: (() -> Unit)?,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(HugeIcons.Cancel01, contentDescription = "Back")
            }
        },
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = HugeIcons.Play,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = groupName.ifBlank { stringResource(R.string.group_chat_title) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (memberCount > 0) {
                    Text(
                        text = "$memberCount 位成员 · $speakerStrategy",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // ── Member avatar strip ──
                if (members.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((-6).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val shown = members.take(8)
                        shown.forEach { member ->
                            val name = memberDisplayNames[member.assistantId] ?: "?"
                            val initial = name.firstOrNull()?.toString() ?: "?"
                            val colorIndex = kotlin.math.abs(name.hashCode()) % avatarColors.size
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(avatarColors[colorIndex]),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = initial,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        if (members.size > 8) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "+${members.size - 8}",
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        actions = {
            if (onGroupManagement != null) {
                IconButton(onClick = onGroupManagement) {
                    Icon(HugeIcons.Settings03, contentDescription = stringResource(R.string.group_chat_manage))
                }
            }
        },
    )
}

@Composable
private fun GroupChatInputBar(
    vm: GroupChatVM,
    loadingJob: kotlinx.coroutines.Job?,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    chatListState: LazyListState,
) {
    val scope = rememberCoroutineScope()
    val hazeState = dev.chrisbanes.haze.rememberHazeState()

    ChatInput(
        state = vm.inputState,
        loading = loadingJob != null,
        settings = settings,
        hazeState = hazeState,
        completionProviders = emptyList(),
        onCancelClick = { vm.stopGeneration() },
        enableSearch = false,
        onToggleSearch = {},
        onSendClick = {
            vm.sendMessage(vm.inputState.getContents())
            scope.launch {
                chatListState.requestScrollToItem(
                    vm.conversation.value.currentMessages.size + 5
                )
            }
            vm.inputState.clearInput()
        },
        onLongSendClick = {
            vm.sendMessage(vm.inputState.getContents(), answer = false)
            scope.launch {
                chatListState.requestScrollToItem(
                    vm.conversation.value.currentMessages.size + 5
                )
            }
            vm.inputState.clearInput()
        },
        onUpdateChatModel = {},
        onUpdateAssistant = {},
        onUpdateSearchService = {},
        onMoreClick = {},
    )
}

@Composable
private fun GroupChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    onRegenerate: (me.rerere.ai.ui.UIMessage) -> Unit,
    onEdit: (me.rerere.ai.ui.UIMessage) -> Unit,
    onDelete: (me.rerere.ai.ui.UIMessage) -> Unit,
    onToggleFavorite: (me.rerere.rikkahub.data.model.MessageNode) -> Unit,
    onToolApproval: (String, Boolean, String, ChatService.ApprovalScope, String?) -> Unit,
    onToolAnswer: (String, String) -> Unit,
    onTranslate: (me.rerere.ai.ui.UIMessage, Locale) -> Unit,
    onClearTranslation: (me.rerere.ai.ui.UIMessage) -> Unit,
) {
    // TODO: Phase 2 — Build a proper group chat message list with:
    //   - Sender name labels (from node.senderName)
    //   - Sender avatar chips
    //   - Group-style "who said what" bubbles
    //   - "Continue" floating button
    // Phase 1: Reuse ChatPage's ChatList composable as-is.
    me.rerere.rikkahub.ui.pages.chat.ChatList(
        innerPadding = innerPadding,
        conversation = conversation,
        state = state,
        loading = loading,
        processingStatus = processingStatus,
        previewMode = false,
        settings = settings,
        hazeState = dev.chrisbanes.haze.rememberHazeState(),
        errors = errors,
        onDismissError = onDismissError,
        onClearAllErrors = onClearAllErrors,
        onRegenerate = onRegenerate,
        onEdit = onEdit,
        onForkMessage = {},
        onDelete = onDelete,
        onUpdateMessage = { _ -> },
        onClickSuggestion = { _ -> },
        onTranslate = onTranslate,
        onClearTranslation = onClearTranslation,
        onJumpToMessage = {},
        onToolApproval = onToolApproval,
        onToolAnswer = onToolAnswer,
        onToggleFavorite = onToggleFavorite,
        onConversationSystemPromptChange = {},
    )
}
