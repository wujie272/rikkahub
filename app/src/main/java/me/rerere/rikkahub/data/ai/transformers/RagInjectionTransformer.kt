package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.rag.RagSearchService

/**
 * RAG 知识库注入转换器
 *
 * 类似 Lorebook 的触发式注入：当用户消息命中笔记库中的内容时，
 * 自动将相关 chunk 注入到系统提示词中。
 *
 * 注入数量根据模型上下文窗口自适应：
 * - 大窗口模型（>=128K）：最多 5 条
 * - 中窗口模型（>=32K）：最多 3 条
 * - 小窗口模型（<32K）：最多 2 条
 * 同时根据已用 token 动态调整，避免超限。
 */
class RagInjectionTransformer(
    private val ragSearchService: RagSearchService,
) : InputMessageTransformer {

    companion object {
        private const val MIN_SCORE = 0.5f
        private const val MAX_RESULTS_MAX = 5
        private const val MAX_RESULTS_MEDIUM = 3
        private const val MAX_RESULTS_MIN = 2
        // 中文字符到 token 的粗略估算（4 字符 ≈ 1 token）
        private const val CHARS_PER_TOKEN = 4
        // 每条结果的开销（标题 + 格式 + 元数据 ≈ 50 tokens）
        private const val OVERHEAD_TOKENS_PER_RESULT = 50
        // 注入内容的安全余量（保留给模型回复的 token）
        private const val SAFETY_MARGIN_TOKENS = 2000
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // 只对用户消息做检索
        val lastUserMessage = messages.lastOrNull { it.role == me.rerere.ai.core.MessageRole.USER }
            ?: return messages
        val userText = lastUserMessage.toText().take(500)
        if (userText.isBlank()) return messages

        // 计算可用 token 预算，决定注入数量
        val maxResults = calculateMaxResults(ctx, messages)
        if (maxResults <= 0) return messages

        // 语义搜索
        val results = try {
            ragSearchService.search(
                query = userText,
                limit = maxResults,
                minScore = MIN_SCORE,
            )
        } catch (e: Exception) {
            emptyList()
        }

        if (results.isEmpty()) return messages

        // 构建注入内容（只取不超过预算的条数）
        val injectionContent = buildInjectionContent(results, ctx, messages)
        if (injectionContent.isBlank()) return messages

        // 注入到系统提示词末尾
        val systemIndex = messages.indexOfFirst { it.role == me.rerere.ai.core.MessageRole.SYSTEM }
        if (systemIndex >= 0) {
            val parts = messages[systemIndex].parts.toMutableList()
            val lastText = parts.indexOfLast { it is UIMessagePart.Text }
            if (lastText >= 0) {
                val part = parts[lastText] as UIMessagePart.Text
                parts[lastText] = part.copy(text = part.text + injectionContent)
            } else {
                parts.add(UIMessagePart.Text(injectionContent))
            }
            val result = messages.toMutableList()
            result[systemIndex] = messages[systemIndex].copy(parts = parts)
            return result
        }

        return messages
    }

    /**
     * 根据模型上下文窗口和已用 token 计算最大注入条数。
     */
    private fun calculateMaxResults(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): Int {
        val contextLength = ctx.model.contextLength ?: 8192
        val maxTokens = ctx.assistant.maxTokens?.takeIf { it > 0 } ?: 4096

        // 估算已用 token
        val usedTokens = messages.sumOf { msg ->
            msg.toText().length / CHARS_PER_TOKEN + 10 // 10 tokens for role overhead
        }

        // 可用预算 = 上下文窗口 - 回复预留 - 已用
        val budget = contextLength - maxTokens - SAFETY_MARGIN_TOKENS - usedTokens
        if (budget <= 0) return 0

        // 根据上下文窗口大小决定基础上限
        val baseMax = when {
            contextLength >= 128_000 -> MAX_RESULTS_MAX
            contextLength >= 32_000 -> MAX_RESULTS_MEDIUM
            else -> MAX_RESULTS_MIN
        }

        // 按预算计算实际能塞几条（每条大约 300 字 + 开销）
        val avgCharsPerResult = 300
        val tokensPerResult = avgCharsPerResult / CHARS_PER_TOKEN + OVERHEAD_TOKENS_PER_RESULT
        val byBudget = budget / tokensPerResult

        return minOf(baseMax, byBudget).coerceAtLeast(1)
    }

    /**
     * 构建注入内容，根据实际 token 预算截断文本。
     */
    private fun buildInjectionContent(
        results: List<RagSearchService.SearchResult>,
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): String {
        val usedTokens = messages.sumOf { msg ->
            msg.toText().length / CHARS_PER_TOKEN + 10
        }
        val contextLength = ctx.model.contextLength ?: 8192
        val maxTokens = ctx.assistant.maxTokens?.takeIf { it > 0 } ?: 4096
        val budget = contextLength - maxTokens - SAFETY_MARGIN_TOKENS - usedTokens
        if (budget <= 0) return ""

        // 估算每条结果的平均 token 开销，动态截断文本
        val totalBudgetForContent = budget - (results.size * OVERHEAD_TOKENS_PER_RESULT + 50) // 50 for header
        val charsPerResult = if (results.isNotEmpty()) {
            (totalBudgetForContent * CHARS_PER_TOKEN / results.size).coerceIn(50, 600)
        } else {
            300
        }

        return buildString {
            appendLine()
            appendLine("**RAG 知识库检索结果**")
            appendLine("以下内容来自你的笔记库，可能与当前对话相关：")
            appendLine()
            results.forEachIndexed { i, r ->
                val truncated = r.chunkText.take(charsPerResult)
                appendLine("${i + 1}. **${r.filePath.substringAfterLast("/")}**（相似度: ${"%.0f".format(r.score * 100)}%）")
                appendLine("   $truncated")
                appendLine()
            }
        }
    }
}
