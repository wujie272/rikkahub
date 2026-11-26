package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid

/**
 * Utility functions for generating display names for group chat seats.
 * Ported from LastChat.
 */

/**
 * Ensure seat instance numbers are unique per assistant.
 * If there are gaps or duplicates, renumber them sequentially.
 */
fun GroupChatTemplate.ensureSeatInstanceNumbers(): GroupChatTemplate {
    if (seats.isEmpty()) return this

    val usedNumbersByAssistantId = mutableMapOf<Uuid, MutableSet<Int>>()
    val maxNumberByAssistantId = mutableMapOf<Uuid, Int>()

    fun nextNumber(assistantId: Uuid): Int {
        val next = (maxNumberByAssistantId[assistantId] ?: 0) + 1
        maxNumberByAssistantId[assistantId] = next
        usedNumbersByAssistantId.getOrPut(assistantId) { mutableSetOf() }.add(next)
        return next
    }

    val updatedSeats = seats.map { seat ->
        val assistantId = seat.assistantId
        val usedNumbers = usedNumbersByAssistantId.getOrPut(assistantId) { mutableSetOf() }
        val currentMax = maxNumberByAssistantId[assistantId] ?: 0
        if (currentMax < seat.instanceNumber) {
            maxNumberByAssistantId[assistantId] = seat.instanceNumber
        }

        val number = seat.instanceNumber
        val resolvedNumber = if (number >= 1 && number !in usedNumbers) {
            usedNumbers.add(number)
            number
        } else {
            nextNumber(assistantId)
        }

        if (resolvedNumber == seat.instanceNumber) seat else seat.copy(instanceNumber = resolvedNumber)
    }

    return if (updatedSeats == seats) this else copy(seats = updatedSeats)
}

/**
 * Build a map from seat ID to display name.
 * Single instances show the base name; multiple instances append "#N".
 */
fun GroupChatTemplate.buildSeatDisplayNames(
    assistantsById: Map<Uuid, Assistant>,
    defaultName: String = "Assistant",
): Map<Uuid, String> {
    if (seats.isEmpty()) return emptyMap()

    val safeDefaultName = defaultName.trim().ifBlank { "Assistant" }

    return seats.associate { seat ->
        val baseName = assistantsById[seat.assistantId]
            ?.name
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: safeDefaultName

        val number = seat.instanceNumber.coerceAtLeast(1)
        val displayName = if (number == 1) baseName else "$baseName#$number"
        seat.id to displayName
    }
}
