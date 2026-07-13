package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid

/**
 * @Name 提及解析结果
 */
data class MentionAnalysis(
    /** 消息中依次出现的 @Name 关键词（小写） */
    val mentionedKeysInOrder: List<String>,
    /** 关键词 → 显示信息 + 可匹配的座位IDs */
    val keyToInfo: Map<String, MentionKeyInfo>,
    /** 匹配到多个座位的歧义关键词 */
    val ambiguousKeysInOrder: List<String>,
)

data class MentionKeyInfo(
    val displayName: String,
    val seatIds: List<Uuid>,
)

/**
 * 从文本中解析 @Name 提及，检测同名歧义。
 * 纯函数，不依赖任何注入/状态。
 *
 * @param text 用户输入文本
 * @param template 群聊模板
 * @param assistantsById 助手 ID → Assistant 映射
 * @param defaultName 默认显示名（用于座位查不到助手时的回退）
 */
fun analyzeGroupChatMentionText(
    text: String,
    template: GroupChatTemplate,
    assistantsById: Map<Uuid, Assistant>,
    defaultName: String = "Assistant",
): MentionAnalysis {
    if (text.isBlank() || !text.contains('@')) {
        return MentionAnalysis(
            mentionedKeysInOrder = emptyList(),
            keyToInfo = emptyMap(),
            ambiguousKeysInOrder = emptyList(),
        )
    }

    val seatDisplayNames = template.buildSeatDisplayNames(
        assistantsById = assistantsById,
        defaultName = defaultName,
    )

    // 构建关键词 → 座位ID 映射（大小写无关）
    val keyToInfo = mutableMapOf<String, MutableMentionKeyInfo>()
    template.seats.forEach { seat ->
        val key = seatDisplayNames[seat.id]?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
        val normalized = key.lowercase(java.util.Locale.ROOT)
        val info = keyToInfo.getOrPut(normalized) {
            MutableMentionKeyInfo(displayName = key, seatIds = mutableListOf())
        }
        if (info.displayName.isBlank()) info.displayName = key
        info.seatIds.add(seat.id)
    }

    if (keyToInfo.isEmpty()) {
        return MentionAnalysis(
            mentionedKeysInOrder = emptyList(),
            keyToInfo = emptyMap(),
            ambiguousKeysInOrder = emptyList(),
        )
    }

    // 按关键词长度降序匹配（优先匹配长词，如 "Claude#2" 不会被 "Claude" 吃掉）
    val sortedKeys = keyToInfo.keys.sortedByDescending { it.length }
    val lowerText = text.lowercase(java.util.Locale.ROOT)
    val mentionedKeysInOrder = mutableListOf<String>()
    val mentionedKeySet = mutableSetOf<String>()
    val ambiguousKeysInOrder = mutableListOf<String>()
    val ambiguousKeySet = mutableSetOf<String>()

    var cursor = 0
    while (true) {
        val atIndex = lowerText.indexOf('@', startIndex = cursor)
        if (atIndex < 0) break

        val after = lowerText.substring(atIndex + 1)
        val matchedKey = sortedKeys.firstOrNull { key -> after.startsWith(key) }
        if (matchedKey != null) {
            if (mentionedKeySet.add(matchedKey)) {
                mentionedKeysInOrder.add(matchedKey)
            }
            val seats = keyToInfo[matchedKey]?.seatIds.orEmpty()
            if (seats.size > 1 && ambiguousKeySet.add(matchedKey)) {
                ambiguousKeysInOrder.add(matchedKey)
            }
            cursor = atIndex + 1 + matchedKey.length
        } else {
            cursor = atIndex + 1
        }
    }

    val frozenKeyToInfo = keyToInfo.mapValues { (_, info) ->
        MentionKeyInfo(
            displayName = info.displayName,
            seatIds = info.seatIds.distinct(),
        )
    }

    return MentionAnalysis(
        mentionedKeysInOrder = mentionedKeysInOrder,
        keyToInfo = frozenKeyToInfo,
        ambiguousKeysInOrder = ambiguousKeysInOrder,
    )
}

/**
 * 根据消歧义选择结果，解析最终要发言的座位 IDs
 */
fun resolveMentionSeatOverride(
    analysis: MentionAnalysis,
    selectedSeatIdsByKey: Map<String, Set<Uuid>>,
    template: GroupChatTemplate,
): List<Uuid> {
    val validSeatIds = template.seats.map { it.id }.toSet()
    val result = mutableListOf<Uuid>()

    analysis.mentionedKeysInOrder.forEach { key ->
        val info = analysis.keyToInfo[key] ?: return@forEach
        val seatIds = if (info.seatIds.size <= 1) {
            info.seatIds
        } else {
            val selected = selectedSeatIdsByKey[key].orEmpty()
            info.seatIds.filter { it in selected }
        }
        seatIds.forEach { seatId ->
            if (seatId in validSeatIds && seatId !in result) {
                result.add(seatId)
            }
        }
    }

    return result
}

// 内部可变版本
private data class MutableMentionKeyInfo(
    var displayName: String,
    val seatIds: MutableList<Uuid>,
)
