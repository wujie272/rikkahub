package me.rerere.rikkahub.ui.pages.groupchat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GroupChatTemplate
import me.rerere.rikkahub.data.model.MentionAnalysis
import me.rerere.rikkahub.data.model.analyzeGroupChatMentionText
import me.rerere.rikkahub.data.model.buildSeatDisplayNames
import me.rerere.rikkahub.data.model.resolveMentionSeatOverride
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.GroupChatRunState
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.pages.chat.ChatList
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatPage(id: Uuid) {
    val vm: GroupChatVM = koinViewModel(parameters = { parametersOf(id.toString()) })
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val settings by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val groupChatRunState by vm.groupChatRunState.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val inputState = vm.inputState

    val chatListState = rememberLazyListState()
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    val groupChatTemplate = remember(conversation.groupChatTemplateId, settings.groupChatTemplates) {
        conversation.groupChatTemplateId?.let { id ->
            settings.groupChatTemplates.firstOrNull { it.id == id }
        }
    }
    val assistantsById = remember(settings.assistants) {
        settings.assistants.associateBy { it.id }
    }

    val senderColors = remember(conversation.messageNodes) {
        conversation.messageNodes
            .mapNotNull { it.senderName }
            .distinct()
            .associateWith { name -> generateSenderColor(name) }
    }

    var mentionDisambiguationState by remember { mutableStateOf<MentionDisambiguationState?>(null) }

    LaunchedEffect(conversation.messageNodes.size) {
        if (conversation.messageNodes.isNotEmpty()) {
            chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
        }
    }

    val inputHazeState = rememberHazeState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = if (conversation.title.isNotBlank()) conversation.title
                        else groupChatTemplate?.name ?: "群聊"
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            ChatInput(
                state = inputState,
                loading = groupChatRunState is GroupChatRunState.Running,
                settings = settings,
                hazeState = inputHazeState,
                enableSearch = false,
                onToggleSearch = {},
                onUpdateChatModel = {},
                onUpdateAssistant = {},
                onUpdateSearchService = {},
                onMoreClick = {},
                onCancelClick = { vm.stopGeneration() },
                onSendClick = {
                    softwareKeyboardController?.hide()

                    val mentionState = checkMentionAndDisambiguate(
                        userText = inputState.getContents(),
                        template = groupChatTemplate,
                        assistantsById = assistantsById,
                    )
                    if (mentionState != null) {
                        mentionDisambiguationState = mentionState
                        return@ChatInput
                    }

                    vm.sendMessage(inputState.getContents())
                    inputState.clearInput()
                    scope.launch {
                        chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                    }
                },
                onLongSendClick = {
                    softwareKeyboardController?.hide()

                    val mentionState = checkMentionAndDisambiguate(
                        userText = inputState.getContents(),
                        template = groupChatTemplate,
                        assistantsById = assistantsById,
                    )
                    if (mentionState != null) {
                        mentionDisambiguationState = mentionState
                        return@ChatInput
                    }

                    vm.sendMessage(inputState.getContents())
                    inputState.clearInput()
                    scope.launch {
                        chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize()) {
            val isGroupChatRunning = groupChatRunState is GroupChatRunState.Running
            AnimatedVisibility(visible = isGroupChatRunning) {
                val running = groupChatRunState as? GroupChatRunState.Running
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val speakerLabel = running?.currentSeatName ?: "准备中"
                            Text(
                                text = "第${running?.currentRound ?: 0}/${running?.maxRounds ?: 0}轮 — ${speakerLabel}发言中",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Spacer(Modifier.height(2.dp))
                            LinearProgressIndicator(
                                progress = {
                                    val r = running
                                    if (r != null && r.maxRounds > 0) {
                                        (r.currentRound - 1 + r.currentSeatIndex.toFloat() / r.totalSeats.coerceAtLeast(1)) / r.maxRounds
                                    } else 0f
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                ChatList(
                    innerPadding = innerPadding,
                    conversation = conversation,
                    state = chatListState,
                    loading = groupChatRunState is GroupChatRunState.Running,
                    processingStatus = null,
                    previewMode = false,
                    settings = settings,
                    senderColors = senderColors,
                    hazeState = rememberHazeState(),
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                    onRegenerate = {},
                    onEdit = {},
                    onForkMessage = {},
                    onDelete = {},
                    onUpdateMessage = {},
                    onClickSuggestion = {},
                    onTranslate = null,
                    onClearTranslation = {},
                    onJumpToMessage = {},
                    onToolApproval = null,
                    onToolAnswer = null,
                    onToggleFavorite = null,
                    onConversationSystemPromptChange = null,
                )
            }
        }
    }

    mentionDisambiguationState?.let { state ->
        GroupChatMentionDisambiguationDialog(
            state = state,
            template = groupChatTemplate,
            assistantsById = assistantsById,
            defaultAssistantName = "助手",
            onConfirm = { selectedSeatIdsByKey ->
                val resolvedSeatIds = resolveMentionSeatOverride(
                    analysis = state.analysis,
                    selectedSeatIdsByKey = selectedSeatIdsByKey,
                    template = groupChatTemplate ?: return@GroupChatMentionDisambiguationDialog,
                )
                if (resolvedSeatIds.isNotEmpty()) {
                    vm.sendMessage(inputState.getContents(), resolvedSeatIds)
                    inputState.clearInput()
                    scope.launch {
                        chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                    }
                }
                mentionDisambiguationState = null
            },
            onDismiss = { mentionDisambiguationState = null },
        )
    }
}

// ---- @Name 检测 ----

private fun checkMentionAndDisambiguate(
    userText: List<me.rerere.ai.ui.UIMessagePart>,
    template: GroupChatTemplate?,
    assistantsById: Map<Uuid, Assistant>,
): MentionDisambiguationState? {
    if (template == null) return null

    val text = userText
        .filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
    if (!text.contains('@')) return null

    val analysis = analyzeGroupChatMentionText(
        text = text,
        template = template,
        assistantsById = assistantsById,
        defaultName = "助手",
    )
    if (analysis.ambiguousKeysInOrder.isEmpty()) return null

    return MentionDisambiguationState(
        analysis = analysis,
        selectedSeatIdsByKey = analysis.ambiguousKeysInOrder.associateWith { key ->
            analysis.keyToInfo[key]?.seatIds?.firstOrNull()?.let(::setOf).orEmpty()
        },
    )
}

private data class MentionDisambiguationState(
    val analysis: MentionAnalysis,
    val selectedSeatIdsByKey: Map<String, Set<Uuid>>,
)

// ---- @Name 消歧义对话框 ----

@Composable
private fun GroupChatMentionDisambiguationDialog(
    state: MentionDisambiguationState,
    template: GroupChatTemplate?,
    assistantsById: Map<Uuid, Assistant>,
    defaultAssistantName: String,
    onConfirm: (Map<String, Set<Uuid>>) -> Unit,
    onDismiss: () -> Unit,
) {
    val seatDisplayNames = remember(template, assistantsById) {
        template?.buildSeatDisplayNames(
            assistantsById = assistantsById,
            defaultName = defaultAssistantName,
        ) ?: emptyMap()
    }
    val seatsById = remember(template) {
        template?.seats?.associateBy { it.id } ?: emptyMap()
    }

    var selectedSeatIdsByKey by remember { mutableStateOf(state.selectedSeatIdsByKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要 @ 的具体成员") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "「@${state.analysis.ambiguousKeysInOrder.firstOrNull() ?: ""}」匹配到多个同名成员，请选择具体要@的座位：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.analysis.ambiguousKeysInOrder.forEach { key ->
                    val info = state.analysis.keyToInfo[key] ?: return@forEach
                    val currentSeatIds = selectedSeatIdsByKey[key].orEmpty()

                    Text("@${info.displayName}", style = MaterialTheme.typography.titleSmall)

                    info.seatIds.forEach { seatId ->
                        val seat = seatsById[seatId]
                        val name = seatDisplayNames[seatId]
                            ?: assistantsById[seat?.assistantId]?.name?.ifBlank { info.displayName }
                            ?: info.displayName
                        val isChecked = seatId in currentSeatIds

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    val updated = if (checked) currentSeatIds + seatId else currentSeatIds - seatId
                                    selectedSeatIdsByKey = selectedSeatIdsByKey + (key to updated)
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedSeatIdsByKey) }) {
                Text("发送")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ---- 颜色生成 ----

private val SENDER_COLOR_PALETTE = listOf(
    Color(0xFF4FC3F7), Color(0xFF81C784), Color(0xFFFFB74D),
    Color(0xFFE57373), Color(0xFFBA68C8), Color(0xFF4DB6AC),
    Color(0xFFFF8A65), Color(0xFFA1887F), Color(0xFF90A4AE),
    Color(0xFFF06292), Color(0xFFAED581), Color(0xFF7986CB),
)

private fun generateSenderColor(name: String): Color {
    val hash = name.hashCode() and Int.MAX_VALUE
    return SENDER_COLOR_PALETTE[hash % SENDER_COLOR_PALETTE.size]
}
