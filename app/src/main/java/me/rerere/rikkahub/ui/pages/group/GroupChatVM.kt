package me.rerere.rikkahub.ui.pages.group

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.db.entity.GroupEntity
import me.rerere.rikkahub.data.db.entity.GroupMemberEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.GroupRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import java.util.Locale
import kotlin.uuid.Uuid

class GroupChatVM(
    private val conversationId: Uuid,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    private val updateChecker: UpdateChecker,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
    private val groupRepository: GroupRepository,
) : ViewModel() {
    // ── Conversation state (delegated to ChatService) ──
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(conversationId)
    val conversationJob: StateFlow<Job?> =
        chatService.getGenerationJobStateFlow(conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null as Job?)

    val processingStatus: StateFlow<String?> =
        chatService.getProcessingStatusFlow(conversationId)

    val conversationJobs = chatService.getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val errors: StateFlow<List<ChatError>> = chatService.errors

    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    val mcpManager = chatService.mcpManager

    // ── Group info ──
    val group: StateFlow<GroupEntity?> = groupRepository.getByIdFlow(
        chatService.getGroupId(conversationId) ?: ""
    ).stateIn(viewModelScope, SharingStarted.Lazily, null)

    val members: StateFlow<List<GroupMemberEntity>> =
    groupRepository.getMembersFlow(
        chatService.getGroupId(conversationId) ?: ""
    ).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Settings ──
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val currentChatModel = settingsStore.settingsFlow
        .map { it.getCurrentChatModel() }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val updateState = updateChecker.checkUpdate()
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    // ── Input state ──
    val inputState = ChatInputState()

    init {
        chatService.addConversationReference(conversationId)
        viewModelScope.launch {
            chatService.initializeConversation(conversationId)
            chatService.syncGroupTitle(conversationId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        chatService.removeConversationReference(conversationId)
    }

    // ── Actions delegated to ChatService ──

    fun sendMessage(content: List<UIMessagePart>, answer: Boolean = true) {
        chatService.sendMessage(conversationId, content, answer)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        viewModelScope.launch {
            chatService.editMessage(conversationId, messageId, parts)
        }
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(conversationId)
        }
    }

    fun regenerateAtMessage(message: UIMessage, regenerateAssistantMsg: Boolean = true) {
        chatService.regenerateAtMessage(conversationId, message, regenerateAssistantMsg)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(conversationId, message)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updated = conversation.value.copy(title = title)
            chatService.saveConversation(conversationId, updated)
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(conversationId, conversation.value)
        }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(conversationId) { newConversation }
    }

    fun updateSettings(newSettings: Settings) {
        viewModelScope.launch {
            settingsStore.update(newSettings)
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(conversationId, message, targetLanguage)
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(conversationId, messageId)
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }
            chatService.updateConversationState(conversationId) { current ->
                current.copy(
                    messageNodes = current.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        scope: ChatService.ApprovalScope = ChatService.ApprovalScope.Once,
        toolName: String? = null,
    ) {
        chatService.handleToolApproval(
            conversationId = conversationId,
            toolCallId = toolCallId,
            approved = approved,
            reason = reason,
            scope = scope,
            toolName = toolName,
        )
    }

    fun handleToolAnswer(toolCallId: String, answer: String) {
        chatService.handleToolApproval(
            conversationId, toolCallId, approved = true, answer = answer
        )
    }

    fun dismissError(id: Uuid) = chatService.dismissError(id)
    fun clearAllErrors() = chatService.clearAllErrors()
}
