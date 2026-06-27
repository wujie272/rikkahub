package me.rerere.rikkahub.data.ai.group

import android.util.Log
import me.rerere.rikkahub.data.db.entity.GroupMemberEntity
import kotlin.random.Random

/**
 * Pure selection engine for group roleplay speaker choice.
 *
 * All methods are stateless (the caller manages state via [GroupTurnContext]).
 * This makes the logic easily testable and free of Android dependencies.
 */
object SpeakerSelector {
    private const val TAG = "SpeakerSelector"

    /**
     * Select the next speaker from [context.members] based on [context.strategy].
     *
     * ## Overrides
     * 1. **Forced members first** — any member with `forcedResponse = true` is always
     *    selected, regardless of strategy. If multiple forced members exist, one is
     *    chosen randomly among them.
     * 2. **Turn-level deduplication** — unless the strategy is [SpeakerStrategy.RoundRobin]
     *    or [SpeakerStrategy.PriorityBased], a member who already spoke in this turn
     *    will not be selected again.
     *
     * @return The selected member, or `null` if no eligible member exists (empty group
     *         or no one passes the probability check).
     */
    fun selectSpeaker(
        context: GroupTurnContext,
        rng: Random = Random,
    ): GroupMemberEntity? {
        val members = context.members
        if (members.isEmpty()) return null

        // ── Step 1: Forced members take priority ──
        val forced = members.filter { it.forcedResponse }
        if (forced.isNotEmpty()) {
            // If exactly one forced member, select them directly
            if (forced.size == 1) return forced.first()
            // Multiple forced members: pick randomly
            return forced[rng.nextInt(forced.size)]
        }

        // ── Step 2: Apply strategy ──
        return when (context.strategy) {
            is SpeakerStrategy.ProbabilityBased -> selectProbabilityBased(
                members, context, rng
            )
            is SpeakerStrategy.RoundRobin -> selectRoundRobin(
                members, context, rng
            )
            is SpeakerStrategy.PriorityBased -> selectPriorityBased(
                members, context
            )
            is SpeakerStrategy.Random -> selectRandom(
                members, context, rng
            )
            is SpeakerStrategy.ForcedOnly -> {
                // Already handled forced above; if none forced, nobody speaks
                Log.d(TAG, "ForcedOnly strategy: no forced members, returning null")
                null
            }
        }
    }

    /**
     * Weighted random draw using each member's [responseProbability] as the weight.
     *
     * Members with probability >= 1.0 always pass. Members with probability <= 0.0
     * never pass (unless they're the only eligible member left).
     *
     * @return The selected member, or `null` if no one passed the probability check.
     */
    private fun selectProbabilityBased(
        members: List<GroupMemberEntity>,
        context: GroupTurnContext,
        rng: Random,
    ): GroupMemberEntity? {
        val eligible = context.remainingMembers

        // Step 1: Filter by probability check
        val passed = eligible.filter { passesProbabilityCheck(it, rng) }
        if (passed.isEmpty()) {
            // No one passed — fall back to the member with the highest probability
            Log.d(TAG, "ProbabilityBased: no one passed, falling back to highest-probability member")
            return eligible.maxByOrNull { it.responseProbability }
        }

        // Step 2: Weighted random selection among those who passed
        return weightedRandom(passed, rng)
    }

    /**
     * Round-robin selection. Advances the index through members sorted by priority.
     *
     * Unlike other strategies, round-robin explicitly cycles through ALL members
     * regardless of probability — each member gets a turn when their index comes up.
     * The [context.roundRobinIndex] is used as the starting position.
     */
    private fun selectRoundRobin(
        members: List<GroupMemberEntity>,
        context: GroupTurnContext,
        rng: Random,
    ): GroupMemberEntity? {
        if (members.isEmpty()) return null

        // Sort by priority descending, then by assistant ID for stability
        val sorted = members.sortedWith(
            compareByDescending<GroupMemberEntity> { it.priority }
                .thenBy { it.assistantId }
        )

        val index = context.roundRobinIndex % sorted.size
        val selected = sorted[index]

        // Apply probability check even for round-robin (skip with low prob)
        if (!passesProbabilityCheck(selected, rng)) {
            // Try the next member in line
            for (i in 1 until sorted.size) {
                val candidate = sorted[(index + i) % sorted.size]
                if (passesProbabilityCheck(candidate, rng)) {
                    return candidate
                }
            }
            // All members failed probability — return the selected one anyway
            Log.d(TAG, "RoundRobin: all members failed probability, returning default")
            return selected
        }

        return selected
    }

    /**
     * Priority-based selection. The member with the highest [priority] always speaks.
     * Ties are broken by [responseProbability] descending, then arbitrarily.
     */
    private fun selectPriorityBased(
        members: List<GroupMemberEntity>,
        context: GroupTurnContext,
    ): GroupMemberEntity? {
        val eligible = context.remainingMembers
        if (eligible.isEmpty()) return members.maxByOrNull { it.priority }

        return eligible.maxWithOrNull(
            compareByDescending<GroupMemberEntity> { it.priority }
                .thenByDescending { it.responseProbability }
        )
    }

    /**
     * Pure random selection: every eligible member has equal weight.
     * Probability check still applies (low-prob members may be skipped unless
     * they're the only option).
     */
    private fun selectRandom(
        members: List<GroupMemberEntity>,
        context: GroupTurnContext,
        rng: Random,
    ): GroupMemberEntity? {
        val eligible = context.remainingMembers
        if (eligible.isEmpty()) return null

        // Filter by probability, but fall back to any if none pass
        val passed = eligible.filter { passesProbabilityCheck(it, rng) }
        if (passed.isNotEmpty()) {
            return passed[rng.nextInt(passed.size)]
        }

        // Fall back to random among all eligible
        Log.d(TAG, "Random: no one passed probability, picking randomly from all eligible")
        return eligible[rng.nextInt(eligible.size)]
    }

    // ── Utility methods ──

    /**
     * Check whether a member should respond based on [responseProbability].
     *
     * - `forcedResponse = true` → always respond (100%)
     * - `responseProbability >= 1.0` → always respond
     * - `responseProbability <= 0.0` → never respond
     * - Otherwise → random check with the given probability
     */
    fun passesProbabilityCheck(
        member: GroupMemberEntity,
        rng: Random = Random,
    ): Boolean {
        if (member.forcedResponse) return true
        if (member.responseProbability >= 1.0f) return true
        if (member.responseProbability <= 0.0f) return false
        return rng.nextFloat() < member.responseProbability
    }

    /**
     * Weighted random selection from a list of members.
     * Each member's weight is [responseProbability] (clamped to [0.01, 1.0]).
     */
    private fun weightedRandom(
        members: List<GroupMemberEntity>,
        rng: Random,
    ): GroupMemberEntity {
        if (members.size == 1) return members.first()

        val weights = members.map { maxOf(it.responseProbability, 0.01f).toDouble() }
        val totalWeight = weights.sum()
        var random = rng.nextDouble() * totalWeight

        for (i in members.indices) {
            random -= weights[i]
            if (random <= 0.0) return members[i]
        }

        // Fallback (should not reach here with non-empty list + positive weights)
        return members.last()
    }

    /**
     * Calculate the next round-robin index given the current context and selected member.
     *
     * @param context Current turn context
     * @param selected The member that was just selected (or null if none)
     * @return The next [roundRobinIndex] to store in the advanced context
     */
    fun nextRoundRobinIndex(
        context: GroupTurnContext,
        selected: GroupMemberEntity?,
    ): Int {
        if (selected == null) return context.roundRobinIndex

        val sorted = context.members.sortedWith(
            compareByDescending<GroupMemberEntity> { it.priority }
                .thenBy { it.assistantId }
        )
        if (sorted.isEmpty()) return 0

        val currentIndex = sorted.indexOfFirst { it.assistantId == selected.assistantId }
        if (currentIndex < 0) return 0

        return (currentIndex + 1) % sorted.size
    }
}
