package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A group (roleplay group) that bundles multiple assistants as characters.
 *
 * Members are stored in [GroupMemberEntity], keyed by group id.
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("description", defaultValue = "")
    val description: String = "",
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("speaker_strategy", defaultValue = "PROBABILITY_BASED")
    val speakerStrategy: String = "PROBABILITY_BASED",
    @ColumnInfo("avatar_url")
    val avatarUrl: String? = null,
    @ColumnInfo("created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo("updated_at_ms")
    val updatedAtMs: Long,
    /** Phase 5C: Persisted round-robin index for the next user turn. */
    @ColumnInfo("next_round_robin_index", defaultValue = "0")
    val nextRoundRobinIndex: Int = 0,
)
