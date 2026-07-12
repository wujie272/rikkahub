package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 群聊运行时配置（每次运行时指定，不影响模板）
 */
@Serializable
data class GroupChatRuntimeConfig(
    /** 运行模式 */
    val mode: GroupChatMode = GroupChatMode.ROUND_ROBIN,

    /** 最大轮数（仅 ROUND_ROBIN / DEBATE 模式生效） */
    val maxRounds: Int = 5,

    /** 轮间延迟（毫秒） */
    val interSeatDelayMs: Long = 2000L,

    /** 主持人是否启用结束检测（仅 DEBATE 模式） */
    val moderatorEndCheckEnabled: Boolean = true,

    /** 结束后是否生成总结（仅 DEBATE 模式） */
    val summaryEnabled: Boolean = true,

    /** 需要排除的座位 ID 列表（在运行时动态启用/禁用） */
    val disabledSeatIds: Set<Uuid> = emptySet(),
) {
    companion object {
        fun debateDefaults() = GroupChatRuntimeConfig(
            mode = GroupChatMode.DEBATE,
            maxRounds = 5,
            interSeatDelayMs = 3000L,
            moderatorEndCheckEnabled = true,
            summaryEnabled = true,
        )

        fun roundRobinDefaults() = GroupChatRuntimeConfig(
            mode = GroupChatMode.ROUND_ROBIN,
            maxRounds = 3,
            interSeatDelayMs = 2000L,
            moderatorEndCheckEnabled = false,
            summaryEnabled = false,
        )
    }
}
