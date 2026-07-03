package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.grove.GroveSearchService
import me.rerere.rikkahub.data.model.InjectionPosition

/**
 * Grove 注入转换器
 *
 * 类似 Lorebook 的触发式注入：当用户消息命中笔记库中的内容时，
 * 自动将相关 chunk 注入到系统提示词中。
 *
 * 只在用户明确发送消息时触发，固定取 top-2，相似度需 > 0.5。
 */
class GroveInjectionTransformer(
    private val groveSearchService: GroveSearchService,
) : InputMessageTransformer {

    companion object {
        private const val MIN_SCORE = 0.5f
        private const val MAX_RESULTS = 2
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

        // 语义搜索
        val results = try {
            groveSearchService.search(
                query = userText,
                limit = MAX_RESULTS,
                minScore = MIN_SCORE,
            )
        } catch (e: Exception) {
            emptyList()
        }

        if (results.isEmpty()) return messages

        // 构建注入内容
        val injectionContent = buildString {
            appendLine()
            appendLine("**Grove 笔记检索结果**")
            appendLine("以下内容来自你的笔记库，可能与当前对话相关：")
            appendLine()
            results.forEachIndexed { i, r ->
                appendLine("${i + 1}. **${r.filePath.substringAfterLast("/")}**（相似度: ${"%.0f".format(r.score * 100)}%）")
                appendLine("   ${r.chunkText.take(300)}")
                appendLine()
            }
        }

        // 注入到系统提示词末尾（AFTER_SYSTEM_PROMPT 位置）
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
}
