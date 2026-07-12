package me.rerere.rikkahub.data.model

/**
 * 群聊运行模式
 */
enum class GroupChatMode {
    /** 自由讨论：路由模型决定谁发言 */
    FREE,

    /** 轮流发言：按座位顺序循环 */
    ROUND_ROBIN,

    /** AI辩论：轮流发言 + 主持人检测结束 + 最大轮数限制 */
    DEBATE,
}
