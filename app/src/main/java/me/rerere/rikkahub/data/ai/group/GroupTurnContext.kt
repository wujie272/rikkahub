package me.rerere.rikkahub.data.ai.group

import me.rerere.rikkahub.data.db.entity.GroupEntity
import me.rerere.rikkahub.data.db.entity.GroupMemberEntity
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * Mutable state for a single group roleplay turn session.
 *
 * A turn begins when a user sends a message to a group conversation and ends when
 * all planned member responses have been generated (or the engine decides to stop).
 *
 * ## Lifecycle
 * 1. Created via [GroupTurnContext.create] when a new user message arrives.
 * 2. [selectNextSpeaker] is called to pick the responding member.
 * 3. [speaker] is set to the selected [GroupMemberEntity].
 * 4. The orchestrator generates the response, then [advance] moves to the next turn.
 * 5. Continue until [isTurnComplete] is true.
 */
data class GroupTurnContext(
    /** The group being roleplayed. */
    val group: GroupEntity,

    /** All members of this group, pre-loaded once. */
    val members: List<GroupMemberEntity>,

    /** Assistant definitions keyed by [GroupMemberEntity.assistantId]. */
    val assistants: Map<Uuid, Assistant>,

    /** Resolved [SpeakerStrategy] from [GroupEntity.speakerStrategy]. */
    val strategy: SpeakerStrategy,

    /**
     * The user message that started this turn. Null for the very first message in a
     * conversation (the greeting stanza), where the group may auto-generate one.
     */
    val userMessage: String?,

    /**
     * The full conversation history as text (for context injection). Updated after every
     * member response so each subsequent member can see what was said before them.
     */
    val conversationHistory: List<GroupTurnMessage>,

    /**
     * Which member has been selected to speak for the current round.
     * Reset to null by [advance] and set by [selectNextSpeaker].
     */
    val currentSpeaker: GroupMemberEntity?,

    /**
     * Track which members have spoken in this turn, in order.
     * Used by round-robin to know the current position and by probability-based to
     * avoid selecting the same member twice in a row.
     */
    val speakersThisTurn: List<Uuid>,

    /**
     * Current round number. Increments after each member speaks.
     * Starts at 0. After all planned responses, [isTurnComplete] becomes true.
     */
    val roundNumber: Int,

    /**
     * Maximum number of member responses per user message.
     * -1 means unlimited (let the strategy decide when to stop).
     */
    val maxResponsesPerTurn: Int,

    /**
     * External round-robin pointer. Persisted by the caller so it carries across turns.
     * Only meaningful when [SpeakerStrategy.needsExternalState] is true.
     */
    val roundRobinIndex: Int,

    /**
     * Whether the first member response has been generated (for multi-member turns).
     * When true, at least one member has spoken and the engine may stop if appropriate.
     */
    val hasAtLeastOneResponse: Boolean,
) {
    /** True when no more responses should be generated for this user message. */
    val isTurnComplete: Boolean
        get() {
            if (maxResponsesPerTurn >= 0 && roundNumber >= maxResponsesPerTurn) return true
            if (members.isEmpty()) return true
            // For forced-only, turn is complete when no one is forced
            if (strategy is SpeakerStrategy.ForcedOnly && !members.any { it.forcedResponse }) return true
            return false
        }

    /** Whether the same assistant can speak multiple times in this turn. */
    val allowRepeatedSpeaker: Boolean
        get() = strategy is SpeakerStrategy.RoundRobin || strategy is SpeakerStrategy.PriorityBased

    /** The assistants who have NOT yet spoken this turn. */
    val remainingMembers: List<GroupMemberEntity>
        get() {
            if (allowRepeatedSpeaker) return members
            val spokenIds = speakersThisTurn.mapTo(mutableSetOf()) { it.toString() }
            return members.filter { it.assistantId !in spokenIds }
        }

    /** Create an initial context for a new user message. */
    companion object {
        fun create(
            group: GroupEntity,
            members: List<GroupMemberEntity>,
            assistants: Map<Uuid, Assistant>,
            userMessage: String?,
            conversationHistory: List<GroupTurnMessage> = emptyList(),
            maxResponsesPerTurn: Int = -1,
            roundRobinIndex: Int = 0,
        ): GroupTurnContext = GroupTurnContext(
            group = group,
            members = members,
            assistants = assistants,
            strategy = SpeakerStrategy.byId(group.speakerStrategy),
            userMessage = userMessage,
            conversationHistory = conversationHistory,
            currentSpeaker = null,
            speakersThisTurn = emptyList(),
            roundNumber = 0,
            maxResponsesPerTurn = maxResponsesPerTurn,
            roundRobinIndex = roundRobinIndex,
            hasAtLeastOneResponse = false,
        )
    }

    /** Create the next context after a member has spoken. */
    fun advance(
        newConversationHistory: List<GroupTurnMessage>,
        nextRoundRobinIndex: Int = this.roundRobinIndex,
    ): GroupTurnContext = copy(
        conversationHistory = newConversationHistory,
        currentSpeaker = null,
        speakersThisTurn = speakersThisTurn + (currentSpeaker?.let {
            runCatching { Uuid.parse(it.assistantId) }.getOrNull()
        }?.let { listOf(it) } ?: emptyList()),
        roundNumber = roundNumber + 1,
        roundRobinIndex = nextRoundRobinIndex,
        hasAtLeastOneResponse = true,
    )

    /** Set the current speaker (called by [SpeakerSelector.selectSpeaker]). */
    fun withSpeaker(member: GroupMemberEntity): GroupTurnContext = copy(
        currentSpeaker = member,
        speakersThisTurn = speakersThisTurn + (runCatching { Uuid.parse(member.assistantId) }.getOrNull()?.let { listOf(it) } ?: emptyList())
    )
}

/**
 * A single message in a group conversation, tagged with the speaking member.
 */
data class GroupTurnMessage(
    /** The member who said (or triggered) this message. Null for system/user messages. */
    val memberAssistantId: Uuid?,

    /** The display name of the speaker (from [Assistant.name] or fallback). */
    val speakerName: String,

    /** The text content. */
    val text: String,

    /** Whether this message was generated by an AI member (vs. a human user). */
    val isAiGenerated: Boolean,

    /** Raw [UIMessage] parts for richer rendering (optional, not serialized). */
    val rawParts: @Suppress("unused") Any? = null,
)
