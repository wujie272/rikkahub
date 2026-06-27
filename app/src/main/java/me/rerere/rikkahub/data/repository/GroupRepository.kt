package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.GroupDao
import me.rerere.rikkahub.data.db.entity.GroupEntity
import me.rerere.rikkahub.data.db.entity.GroupMemberEntity

class GroupRepository(
    private val dao: GroupDao,
) {
    // ── Groups ──

    fun listFlow(): Flow<List<GroupEntity>> = dao.listFlow()

    suspend fun getAll(): List<GroupEntity> = dao.getAll()

    suspend fun getById(id: String): GroupEntity? = dao.getById(id)

    fun getByIdFlow(id: String): Flow<GroupEntity?> = dao.getByIdFlow(id)

    suspend fun upsert(group: GroupEntity) = dao.upsert(group)

    suspend fun delete(group: GroupEntity) = dao.delete(group)

    suspend fun deleteById(id: String) = dao.deleteById(id)

    suspend fun getByAssistantId(assistantId: String): List<GroupEntity> =
        dao.getByAssistantId(assistantId)

    /** Phase 5C: Persist the round-robin index for the next user turn. */
    suspend fun updateNextRoundRobinIndex(groupId: String, index: Int) {
        dao.updateRoundRobinIndex(groupId, index, System.currentTimeMillis())
    }

    // ── Group Members ──

    fun getMembersFlow(groupId: String): Flow<List<GroupMemberEntity>> =
        dao.getMembersFlow(groupId)

    suspend fun getMembers(groupId: String): List<GroupMemberEntity> =
        dao.getMembers(groupId)

    suspend fun getMember(groupId: String, assistantId: String): GroupMemberEntity? =
        dao.getMember(groupId, assistantId)

    suspend fun upsertMember(member: GroupMemberEntity) = dao.upsertMember(member)

    suspend fun upsertMembers(members: List<GroupMemberEntity>) = dao.upsertMembers(members)

    suspend fun deleteMember(groupId: String, assistantId: String) =
        dao.deleteMember(groupId, assistantId)

    suspend fun deleteMembersByGroupId(groupId: String) =
        dao.deleteMembersByGroupId(groupId)

    suspend fun memberCount(groupId: String): Int =
        dao.memberCount(groupId)
}
