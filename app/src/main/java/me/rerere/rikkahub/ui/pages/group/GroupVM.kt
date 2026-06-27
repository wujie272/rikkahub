package me.rerere.rikkahub.ui.pages.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.GroupEntity
import me.rerere.rikkahub.data.repository.GroupRepository
import kotlin.uuid.Uuid

class GroupVM(
    private val repository: GroupRepository,
) : ViewModel() {
    val groups = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun create(name: String) {
        val now = System.currentTimeMillis()
        val group = GroupEntity(
            id = Uuid.random().toString(),
            name = name,
            assistantId = "",
            createdAtMs = now,
            updatedAtMs = now,
        )
        viewModelScope.launch {
            repository.upsert(group)
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch {
            val group = repository.getById(id) ?: return@launch
            repository.upsert(
                group.copy(name = name, updatedAtMs = System.currentTimeMillis())
            )
        }
    }

    fun delete(group: GroupEntity) {
        viewModelScope.launch {
            repository.deleteMembersByGroupId(group.id)
            repository.delete(group)
        }
    }
}
