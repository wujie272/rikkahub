package me.rerere.rikkahub.data.ai.group

/**
 * A speaker strategy determines which group member speaks next in a roleplay group turn.
 *
 * The strategy is stored as [GroupEntity.speakerStrategy] (a string key) and resolved
 * back to [SpeakerStrategy] via [byId]. Default is [ProbabilityBased].
 *
 * ## Available strategies
 *
 * | ID                    | Behaviour                                                |
 * |-----------------------|----------------------------------------------------------|
 * | PROBABILITY_BASED     | Weighted random draw — each member's [responseProbability]  |
 * |                       | is used as weight. Higher = more likely to speak.         |
 * | ROUND_ROBIN           | Cycles through members in priority-descending order.     |
 * | PRIORITY_BASED        | The member with the highest [priority] always speaks.    |
 * | RANDOM                | Equal probability for every member (ignores probability). |
 * | FORCED_ONLY           | Only forced-response members speak; if none, no one.     |
 */
sealed interface SpeakerStrategy {
    /** Stable identifier persisted in [GroupEntity.speakerStrategy]. */
    val id: String

    /** Human-readable label for the settings UI. */
    val displayName: String

    /**
     * Whether this strategy needs the [SpeakerSelector] to track per-group round-robin
     * state externally (via [GroupTurnContext.roundRobinIndex]).
     */
    val needsExternalState: Boolean get() = false

    // ── Built-in strategy singletons ──

    /**
     * Weighted random draw. Each member's [responseProbability] is used as weight
     * (0.0 = never, 1.0 = always). Members with `forcedResponse = true` are always
     * selected over everyone else.
     */
    data object ProbabilityBased : SpeakerStrategy {
        override val id = "PROBABILITY_BASED"
        override val displayName = "Probability Based"
    }

    /**
     * Strict round-robin. Members are sorted by priority descending; the selector
     * advances one position per turn. When the end is reached, wraps to the start.
     */
    data object RoundRobin : SpeakerStrategy {
        override val id = "ROUND_ROBIN"
        override val displayName = "Round Robin"
        override val needsExternalState: Boolean get() = true
    }

    /**
     * The member with the highest [priority] value always speaks. Ties are broken
     * by [responseProbability] descending, then by insertion order.
     */
    data object PriorityBased : SpeakerStrategy {
        override val id = "PRIORITY_BASED"
        override val displayName = "Priority Based"
    }

    /**
     * Every member has equal probability regardless of [responseProbability].
     * The only exceptions are `forcedResponse = true` members, who still get priority.
     */
    data object Random : SpeakerStrategy {
        override val id = "RANDOM"
        override val displayName = "Random"
    }

    /**
     * Only members with `forcedResponse = true` can speak. If no one is forced,
     * [SpeakerSelector.selectSpeaker] returns `null`.
     */
    data object ForcedOnly : SpeakerStrategy {
        override val id = "FORCED_ONLY"
        override val displayName = "Forced Only"
    }

    // ── Companion / resolvers ──

    companion object {
        /** All built-in strategies, keyed by [id]. */
        val ALL: Map<String, SpeakerStrategy> = listOf(
            ProbabilityBased,
            RoundRobin,
            PriorityBased,
            Random,
            ForcedOnly,
        ).associateBy { it.id }

        /**
         * Resolve a persisted strategy ID back to a [SpeakerStrategy].
         * Falls back to [ProbabilityBased] for unknown IDs (forward-compatible with
         * future strategies that may be added by external plugins).
         */
        fun byId(id: String): SpeakerStrategy =
            ALL[id] ?: ProbabilityBased

        /** The default strategy applied to newly created groups. */
        val DEFAULT: SpeakerStrategy = ProbabilityBased
    }
}
