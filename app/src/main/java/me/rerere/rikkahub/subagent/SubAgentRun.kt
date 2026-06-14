package me.rerere.rikkahub.subagent

/** 子代理运行状态 */
enum class SubAgentStatus {
    PENDING,      // 排队中
    RUNNING,      // 运行中
    SUCCEEDED,    // 成功
    FAILED,       // 失败
    TIMED_OUT,    // 超时
    CANCELLED,    // 取消
}

/** 子代理运行记录 */
data class SubAgentRun(
    val id: String,
    val parentChatId: String? = null,
    val parentAssistantId: String = "",
    val label: String = "",
    val runInBackground: Boolean = false,
    val timeoutSeconds: Long = 300L,
    val task: String = "",
    val status: SubAgentStatus = SubAgentStatus.PENDING,
    val startedAtMs: Long = System.currentTimeMillis(),
    val finishedAtMs: Long? = null,
    val result: String? = null,   // 最终的文本结果
    val error: String? = null,
    val tripCount: Int = 0,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
)

/** 子代理请求参数 */
data class SubAgentRequest(
    val task: String,
    val label: String? = null,
    val runInBackground: Boolean = false,
    val timeoutSeconds: Long = 300, // 默认5分钟
)
