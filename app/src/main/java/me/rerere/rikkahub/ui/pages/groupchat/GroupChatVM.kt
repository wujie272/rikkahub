package me.rerere.rikkahub.ui.pages.groupchat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GroupChatTemplate
import me.rerere.rikkahub.data.model.MentionAnalysis
import me.rerere.rikkahub.data.model.analyzeGroupChatMentionText
import me.rerere.rikkahub.data.model.buildSeatDisplayNames
import me.rerere.rikkahub.data.model.resolveMentionSeatOverride
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.GroupChatRunState
import me.rerere.rikkahub.ui.hooks.ChatInputState
import kotlin.uuid.Uuid

private const val TAG = "GroupChatVM"

class GroupChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false)

    val inputState = ChatInputState()

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val groupChatRunState: StateFlow<GroupChatRunState> = chatService.groupChatRunner.runState
        .stateIn(viewModelScope, SharingStarted.Eagerly, GroupChatRunState.Idle)

    val errors: StateFlow<List<ChatError>> = chatService.errors

    init {
        chatService.addConversationReference(_conversationId)
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        chatService.removeConversationReference(_conversationId)
    }

    fun dismissError(id: Uuid) = chatService.dismissError(id)
    fun clearAllErrors() = chatService.clearAllErrors()

    fun sendMessage(content: List<UIMessagePart>, groupChatSpeakerSeatIdsOverride: List<Uuid>? = null) {
        if (content.isEmptyInputMessage()) return
        chatService.sendMessage(_conversationId, content, true, groupChatSpeakerSeatIdsOverride)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    // 群聊 @Name 检测
    fun handleGroupChatMentionCheck(
        userText: String,
        template: GroupChatTemplate?,
        assistantsById: Map<Uuid, me.rerere.rikkahub.data.model.Assistant>,
    ): MentionAnalysis? {
        if (template == null || !userText.contains('@')) return null
        val analysis = analyzeGroupChatMentionText(
            text = userText,
            template = template,
            assistantsById = assistantsById,
            defaultName = "助手",
        )
        if (analysis.ambiguousKeysInOrder.isNotEmpty()) {
            return analysis
        }
        return null
    }

    fun resolveMentionSeatIds(
        analysis: MentionAnalysis?,
        template: GroupChatTemplate?,
        selectedSeatIdsByKey: Map<String, Set<Uuid>>,
    ): List<Uuid>? {
        if (analysis == null || template == null) return null
        return resolveMentionSeatOverride(
            analysis = analysis,
            selectedSeatIdsByKey = selectedSeatIdsByKey,
            template = template,
        )
    }
}
