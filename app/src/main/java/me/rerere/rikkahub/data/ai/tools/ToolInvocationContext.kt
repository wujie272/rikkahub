package me.rerere.rikkahub.data.ai.tools

/**
 * 工具调用上下文 — 在 [LocalTools.getTools] 构建时传入，
 * 供各 Tool factory 读取调用者信息。
 *
 * 替代原来散装传参（conversationId, assistantId）的方式，
 * 统一管理和扩展。
 */
data class ToolInvocationContext(
    /** 发起调用的助手 ID */
    val callerAssistantId: String? = null,
    /** 发起调用的会话 ID */
    val callerConversationId: String? = null,
    /** 是否在 headless（无 UI）环境下运行 */
    val isHeadless: Boolean = false,
) {
    companion object {
        /** 无上下文时的回退值 */
        val EMPTY = ToolInvocationContext()
    }
}
