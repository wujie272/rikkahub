package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import kotlin.uuid.Uuid

/**
 * A group chat template — bundles multiple assistants into a single conversation.
 *
 * Stored entirely in DataStore (serialized as JSON inside [me.rerere.rikkahub.data.datastore.Settings]),
 * not in Room. This keeps the schema flexible and avoids DB migrations for template-level options.
 *
 * Inspired by LastChat's GroupChatTemplate design.
 */
@Serializable
data class GroupChatTemplate(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val intro: String = "",
    /** Model used for routing which seat should speak (null = fallback, pick first N). */
    val hostModelId: Uuid? = null,
    /** System prompt for the host routing model. */
    val hostSystemPrompt: String = "",
    /** Model used for consolidating/merging memories after the conversation. */
    val integrationModelId: Uuid? = null,
    /** Minutes to wait before consolidating memories. */
    val consolidationDelayMinutes: Int = 30,
    /** The seats in this group chat. */
    val seats: List<GroupChatSeat> = emptyList(),
)

/**
 * A single seat = one assistant instance (or multiple instances of the same assistant).
 */
@Serializable
data class GroupChatSeat(
    val id: Uuid = Uuid.random(),
    /** Which assistant occupies this seat. */
    val assistantId: Uuid,
    /**
     * Instance number — allows the same assistant to sit in multiple seats.
     * Displayed as "Name#2", "Name#3" etc when > 1.
     */
    val instanceNumber: Int = 1,
    /** Per-seat overrides that can override the assistant's defaults. */
    val overrides: GroupChatSeatOverrides = GroupChatSeatOverrides(),
    /** Whether this seat is enabled by default (disabled seats don't auto-respond). */
    val defaultEnabled: Boolean = true,
)

/**
 * Per-seat overrides for the assistant's configuration.
 * All nullable fields = inherit from the assistant's base config.
 */
@Serializable
data class GroupChatSeatOverrides(
    val chatModelId: Uuid? = null,
    val systemPrompt: String? = null,
    val reasoningLevel: ReasoningLevel? = null,
    val maxTokens: Int? = null,
    val searchEnabled: Boolean = false,
    val memoryEnabled: Boolean = false,
    val searchMode: AssistantSearchMode = AssistantSearchMode.Off,
    val preferBuiltInSearch: Boolean = false,
    val mcpServerIds: Set<Uuid> = emptySet(),
)
