package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.GroupEntity
import me.rerere.rikkahub.data.db.entity.GroupMemberEntity

@Dao
interface GroupDao {
    // ── Groups ──

    @Query("SELECT * FROM `groups` ORDER BY updated_at_ms DESC")
    fun listFlow(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM `groups` ORDER BY updated_at_ms DESC")
    suspend fun getAll(): List<GroupEntity>

    @Query("SELECT * FROM `groups` WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): GroupEntity?

    @Query("SELECT * FROM `groups` WHERE id = :id LIMIT 1")
    fun getByIdFlow(id: String): Flow<GroupEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: GroupEntity)

    @Delete
    suspend fun delete(group: GroupEntity)

    @Query("DELETE FROM `groups` WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM `groups` WHERE assistant_id = :assistantId ORDER BY updated_at_ms DESC")
    suspend fun getByAssistantId(assistantId: String): List<GroupEntity>

    /** Phase 5C: Update the round-robin pointer for the next user turn. */
    @Query("UPDATE `groups` SET next_round_robin_index = :index, updated_at_ms = :updatedAtMs WHERE id = :groupId")
    suspend fun updateRoundRobinIndex(groupId: String, index: Int, updatedAtMs: Long)

    // ── Group Members ──

    @Query("SELECT * FROM group_members WHERE group_id = :groupId ORDER BY priority DESC")
    fun getMembersFlow(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE group_id = :groupId ORDER BY priority DESC")
    suspend fun getMembers(groupId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE group_id = :groupId AND assistant_id = :assistantId LIMIT 1")
    suspend fun getMember(groupId: String, assistantId: String): GroupMemberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: GroupMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<GroupMemberEntity>)

    @Query("DELETE FROM group_members WHERE group_id = :groupId AND assistant_id = :assistantId")
    suspend fun deleteMember(groupId: String, assistantId: String)

    @Query("DELETE FROM group_members WHERE group_id = :groupId")
    suspend fun deleteMembersByGroupId(groupId: String)

    @Query("SELECT COUNT(*) FROM group_members WHERE group_id = :groupId")
    suspend fun memberCount(groupId: String): Int
}
