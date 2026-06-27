package me.rerere.rikkahub.ui.pages.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.GroupEntity
import me.rerere.rikkahub.data.db.entity.GroupMemberEntity
import me.rerere.rikkahub.data.repository.GroupRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

class GroupDetailVM(
    private val groupId: String,
    private val repository: GroupRepository,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
) : ViewModel() {
    /** Start a group chat and return the conversation ID. */
    suspend fun startChat(): Uuid {
        return chatService.startGroupConversation(groupId)
    }

    val group: StateFlow<GroupEntity?> = repository.getByIdFlow(groupId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val members: StateFlow<List<GroupMemberEntity>> = repository.getMembersFlow(groupId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Map of assistantId -> assistantName from SettingsStore */
    val assistantNames: StateFlow<Map<String, String>> = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistants.associate { a ->
                a.id.toString() to a.name
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    /** All assistants, for the "add member" picker: id -> name */
    val allAssistants: StateFlow<Map<String, String>> = assistantNames

    fun updateGroup(name: String, description: String) {
        viewModelScope.launch {
            val g = repository.getById(groupId) ?: return@launch
            repository.upsert(
                g.copy(
                    name = name,
                    description = description,
                    updatedAtMs = System.currentTimeMillis(),
                )
            )
        }
    }

    fun addMember(assistantId: String) {
        viewModelScope.launch {
            val existing = repository.getMember(groupId, assistantId)
            if (existing != null) return@launch

            val member = GroupMemberEntity(
                groupId = groupId,
                assistantId = assistantId,
                createdAtMs = System.currentTimeMillis(),
            )
            repository.upsertMember(member)
        }
    }

    fun removeMember(assistantId: String) {
        viewModelScope.launch {
            repository.deleteMember(groupId, assistantId)
        }
    }

    fun updateMemberPriority(assistantId: String, priority: Int) {
        viewModelScope.launch {
            val member = repository.getMember(groupId, assistantId) ?: return@launch
            repository.upsertMember(member.copy(priority = priority))
        }
    }

    fun updateMemberProbability(assistantId: String, probability: Float) {
        viewModelScope.launch {
            val member = repository.getMember(groupId, assistantId) ?: return@launch
            repository.upsertMember(member.copy(responseProbability = probability))
        }
    }
}
