package me.rerere.rikkahub.data.ai.group

import android.util.Log
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.GroupEntity
import me.rerere.rikkahub.data.db.entity.GroupMemberEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.GroupRepository
import kotlin.uuid.Uuid

/**
 * High-level orchestrator for group roleplay turns.
 *
 * Responsibilities:
 * 1. Load group + members from [GroupRepository]
 * 2. Load assistant definitions from [SettingsStore]
 * 3. Use [SpeakerSelector] to pick the next speaker
 * 4. Build the speaker's persona context for the LLM
 * 5. Produce a [GroupTurnPlan] that Phase 4 (chat integration) will execute
 *
 * This class is injectable via Koin. It does NOT hold mutable per-turn state;
 * all state is managed by the caller via [GroupTurnContext].
 */
class GroupTurnOrchestrator(
    private val groupRepository: GroupRepository,
    private val settingsStore: SettingsStore,
) {
    private val TAG = "GroupTurnOrchestrator"

    /**
     * Prepare a turn plan for the next speaker in a group.
     *
     * This is the main entry point for Phase 4. It will:
     * 1. Validate the group exists and has members
     * 2. Select the next speaker using [SpeakerSelector]
     * 3. Build the speaker's context (persona + system prompt injection)
     * 4. Produce a [GroupTurnPlan] with everything needed to generate a response
     *
     * @param context Current turn context. Pass a fresh one via [GroupTurnContext.create]
     *                for the first member, or [GroupTurnContext.advance] for subsequent ones.
     * @return A [GroupTurnPlan] containing the selected speaker and the context to use
     *         for generation, or an error result.
     */
    suspend fun planNextSpeaker(
        context: GroupTurnContext,
    ): GroupTurnPlan {
        Log.d(TAG, "planNextSpeaker: round=${context.roundNumber}, members=${context.members.size}")
        // ── Validate state ──
        if (context.isTurnComplete) {
            return GroupTurnPlan.TurnComplete(
                reason = "Turn already complete (round ${context.roundNumber})",
                context = context,
            )
        }

        // ── Select speaker ──
        val selected = SpeakerSelector.selectSpeaker(context)
        Log.d(TAG, "planNextSpeaker: selected=${selected?.assistantId?.let { it.substring(0, minOf(8, it.length)) }}")
        if (selected == null) return GroupTurnPlan.TurnComplete(
                reason = "No eligible speaker found",
                context = context,
            )

        val advancedContext = context.withSpeaker(selected)

        // ── Build system addendum (Swap mode) ──
        val parsedId = runCatching { Uuid.parse(selected.assistantId) }.getOrNull()
        val assistant = context.assistants[parsedId]
        Log.d(TAG, "planNextSpeaker: assistant found=${assistant != null}, name=${assistant?.name}")
        val systemAddendum = buildSystemAddendum(
            member = selected,
            assistant = assistant,
            context = advancedContext,
        )

        // ── Prepare result ──
        val nextRoundRobinIndex = if (context.strategy.needsExternalState) {
            SpeakerSelector.nextRoundRobinIndex(context, selected)
        } else {
            context.roundRobinIndex
        }

        return GroupTurnPlan.SpeakerSelected(
            member = selected,
            assistant = assistant,
            systemAddendum = systemAddendum,
            nextContext = advancedContext,
            nextRoundRobinIndex = nextRoundRobinIndex,
        )
    }

    /**
     * Prepare a full multi-speaker turn plan.
     *
     * Calls [planNextSpeaker] repeatedly until the turn is complete or maxResponses is reached.
     * This is useful for batch planning in UI scenarios where you want to show "X will respond"
     * before generation starts.
     *
     * @param context The initial turn context.
     * @param maxPlans Maximum number of speakers to plan (capped by [GroupTurnContext.maxResponsesPerTurn]).
     * @return A list of [GroupTurnPlan.SpeakerSelected] in speaking order, followed by a
     *         [GroupTurnPlan.TurnComplete] at the end.
     */
    suspend fun planTurn(
        context: GroupTurnContext,
        maxPlans: Int = 4,
    ): List<GroupTurnPlan> {
        val plans = mutableListOf<GroupTurnPlan>()
        var currentCtx = context

        for (i in 0 until maxPlans) {
            val plan = planNextSpeaker(currentCtx)
            plans.add(plan)

            when (plan) {
                is GroupTurnPlan.TurnComplete -> break
                is GroupTurnPlan.SpeakerSelected -> {
                    // Save the round-robin index from the plan
                    currentCtx = plan.nextContext.advance(
                        newConversationHistory = currentCtx.conversationHistory,
                        nextRoundRobinIndex = plan.nextRoundRobinIndex,
                    )
                }
            }
        }

        return plans
    }

    /**
     * Load group data and construct the initial [GroupTurnContext] for a user message.
     *
     * This is the standard entry point: given a group ID and the user's message,
     * it loads everything from the database and returns a fully populated context.
     *
     * @param groupId The group ID to load.
     * @param userMessage The user's message text (null for greeting).
     * @param conversationHistory Any existing conversation history as [GroupTurnMessage]s.
     * @param maxResponsesPerTurn Maximum AI responses per user turn (-1 = unlimited).
     * @param roundRobinIndex Persisted round-robin pointer (null = start at 0).
     * @return A [GroupTurnContext] ready for [planNextSpeaker], or null if the group
     *         doesn't exist or has no members.
     */
    suspend fun createContext(
        groupId: String,
        userMessage: String?,
        conversationHistory: List<GroupTurnMessage> = emptyList(),
        maxResponsesPerTurn: Int = 3,
        roundRobinIndex: Int? = null,
    ): Result<GroupTurnContext> {
        // ── Load group ──
        val group = groupRepository.getById(groupId)
            ?: return Result.failure(IllegalStateException("Group not found: $groupId"))

        // ── Load members ──
        val members = groupRepository.getMembers(groupId)
        Log.d(TAG, "createContext: group=$groupId, members=${members.size}")
        if (members.isEmpty()) {
            return Result.failure(IllegalStateException("Group has no members: $groupId"))
        }
        members.forEach { m -> Log.d(TAG, "  member: ${m.assistantId.substring(0, minOf(8, m.assistantId.length))}, priority=${m.priority}, probability=${m.responseProbability}") }

        // ── Load assistants ──
        val settings = settingsStore.settingsFlowRaw.first()
        Log.d(TAG, "createContext: assistants in settings=${settings.assistants.size}")
        settings.assistants.forEach { a -> Log.d(TAG, "  assistant: ${a.id.toString().substring(0, 8)}, name=${a.name}") }
        val memberIds = members.mapNotNull {
            runCatching { Uuid.parse(it.assistantId) }.getOrNull()
        }
        val assistants = settings.assistants
            .filter { it.id in memberIds }
            .associateBy { it.id }

        // Log warnings for missing assistants
        memberIds.forEach { id ->
            if (id !in assistants) {
                Log.w(TAG, "Assistant not found for member: $id in group: $groupId")
            }
        }

        // ── Build context ──
        val context = GroupTurnContext.create(
            group = group,
            members = members,
            assistants = assistants,
            userMessage = userMessage,
            conversationHistory = conversationHistory,
            maxResponsesPerTurn = maxResponsesPerTurn,
            // Phase 5C: Use persisted round-robin index from the group entity
            roundRobinIndex = roundRobinIndex ?: group.nextRoundRobinIndex,
        )

        return Result.success(context)
    }

    // ── Context builders ──

    /**
     * Build a minimal system addendum for group roleplay (Swap mode).
     *
     * Swap mode: Only the current speaker's persona is injected.
     * The persona comes from [assistant.systemPrompt] (passed as the
     * system message by [GenerationHandler]). This addendum only adds:
     * - Group name
     * - Names of other participants (not their full personas)
     * - A speaking instruction for the current speaker
     *
     * Conversation history and user message are deliberately OMITTED
     * here — they are already passed via the `messages` parameter to
     * [GenerationHandler.generateText]. Duplicating them causes the
     * model to see the same content twice, which can confuse output.
     */
    private fun buildSystemAddendum(
        member: GroupMemberEntity,
        assistant: Assistant?,
        context: GroupTurnContext,
    ): String {
        val speakerName = assistant?.name?.ifBlank { member.assistantId } ?: member.assistantId
        val groupName = context.group.name.ifBlank { "Group" }

        return buildString {
            appendLine("## Group Roleplay")
            appendLine("This is a group conversation in \"$groupName\".")

            // Mention other members by name ONLY (no persona details)
            val otherMembers = context.members.filter { it.assistantId != member.assistantId }
            if (otherMembers.isNotEmpty()) {
                appendLine()
                appendLine("Other participants:")
                otherMembers.forEach { m ->
                    val name = context.assistants[runCatching { Uuid.parse(m.assistantId) }.getOrNull()]
                        ?.name?.ifBlank { m.assistantId } ?: m.assistantId
                    appendLine("- $name")
                }
            }
            appendLine()

            // Speaking instruction — concise, avoids ambiguity
            appendLine("## Your Turn")
            appendLine("You are $speakerName. Reply in character as $speakerName.")
            appendLine("Write ONLY what $speakerName says. Do NOT speak for any other character.")
            appendLine("Do NOT include \"$speakerName:\" before your message.")
        }
    }
}

/**
 * The result of planning the next speaker for a group turn.
 *
 * ## Sealed subclasses
 * - [SpeakerSelected] — A member was chosen. Phase 4 should use [assistant] and
 *   [systemAddendum] to generate a response via [GenerationHandler].
 * - [TurnComplete] — No more speakers in this turn. Phase 4 should return the
 *   conversation to the user.
 */
sealed interface GroupTurnPlan {
    /**
     * A specific member has been selected to speak next.
     *
     * @property member The group member (DB entity) who will speak.
     * @property assistant The [Assistant] definition for this member (null if not found
     *                     in settings, e.g. the assistant was deleted after being added).
     * @property personaContext Persona injection text for the system prompt.
     * @property systemAddendum Full system addendum to pass to [GenerationHandler.generateText].
     * @property nextContext The [GroupTurnContext] advanced past this selection.
     * @property nextRoundRobinIndex Updated round-robin pointer for persistence.
     */
    data class SpeakerSelected(
        val member: GroupMemberEntity,
        val assistant: Assistant?,
        val systemAddendum: String,
        val nextContext: GroupTurnContext,
        val nextRoundRobinIndex: Int,
    ) : GroupTurnPlan

    /**
     * The turn is complete — no more speakers should respond.
     *
     * @property reason Human-readable reason for stopping.
     * @property context The final [GroupTurnContext] state.
     */
    data class TurnComplete(
        val reason: String,
        val context: GroupTurnContext,
    ) : GroupTurnPlan
}
