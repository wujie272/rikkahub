package me.rerere.rikkahub.data.knowledge

/**
 * 文本分块工具
 *
 * 支持两种策略：
 * - "markdown"（默认）：基于 JetBrains Markdown 解析器 AST，按标题层级分块，代码块/表格保护，带标题路径上下文
 * - "fixed"：固定字符大小分块，兜底方案
 *
 * 其他策略（paragraph / code / semantic）已废弃，统一由 markdown 策略覆盖。
 */
object TextChunker {

    /**
     * 将文本分成块
     * @param text 源文本（markdown 策略建议传入已清理 frontmatter 的文本）
     * @param chunkSize 每块最大字符数
     * @param chunkOverlap 块重叠字符数（仅 fixed 策略生效）
     * @param strategy 分块策略："markdown"（默认）或 "fixed"
     */
    fun chunk(
        text: String,
        chunkSize: Int = 1000,
        chunkOverlap: Int = 200,
        strategy: String = "markdown",
    ): List<String> {
        if (text.isBlank()) return emptyList()
        return when (strategy) {
            "markdown" -> chunkByMarkdown(text, chunkSize, chunkOverlap)
            else -> chunkByFixed(text, chunkSize, chunkOverlap)
        }
    }

    /** 固定大小分块 */
    private fun chunkByFixed(text: String, chunkSize: Int, overlap: Int): List<String> {
        if (text.length <= chunkSize) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            chunks.add(text.substring(start, end))
            start += chunkSize - overlap
            if (start >= text.length) break
        }
        return chunks
    }

    /**
     * 按 Markdown 标题分块（基于 JetBrains Markdown 解析器 AST）。
     *
     * 委托给 [MarkdownChunker] 实现。
     */
    private fun chunkByMarkdown(text: String, chunkSize: Int, overlap: Int): List<String> {
        return MarkdownChunker.chunkToTexts(text, chunkSize, overlap)
    }

}
