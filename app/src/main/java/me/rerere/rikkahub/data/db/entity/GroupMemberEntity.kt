package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * A member of a roleplay group — maps an assistant to a character role.
 *
 * Composite primary key: (group_id, assistant_id) prevents duplicate members.
 */
@Entity(
    tableName = "group_members",
    primaryKeys = ["group_id", "assistant_id"],
    indices = [
        Index(value = ["group_id"]),
        Index(value = ["assistant_id"]),
    ],
)
data class GroupMemberEntity(
    @ColumnInfo("group_id")
    val groupId: String,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("priority", defaultValue = "0")
    val priority: Int = 0,
    @ColumnInfo("response_probability", defaultValue = "1.0")
    val responseProbability: Float = 1.0f,
    @ColumnInfo("forced_response", defaultValue = "0")
    val forcedResponse: Boolean = false,
    @ColumnInfo("created_at_ms")
    val createdAtMs: Long,
)
