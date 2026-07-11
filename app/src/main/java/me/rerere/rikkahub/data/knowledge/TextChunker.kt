package me.rerere.rikkahub.data.knowledge

/**
 * 文本分块工具
 * 支持多种分块策略
 */
object TextChunker {

    /**
     * 将文本分成块
     * @param text 源文本
     * @param chunkSize 每块最大字符数
     * @param chunkOverlap 块重叠字符数
     * @param strategy 分块策略
     */
    fun chunk(
        text: String,
        chunkSize: Int = 1000,
        chunkOverlap: Int = 200,
        strategy: String = "fixed",
    ): List<String> {
        if (text.isBlank()) return emptyList()
        return when (strategy) {
            "paragraph" -> chunkByParagraph(text, chunkSize, chunkOverlap)
            "markdown" -> chunkByMarkdown(text, chunkSize, chunkOverlap)
            "code" -> chunkByCode(text, chunkSize, chunkOverlap)
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

    /** 按段落分块 */
    private fun chunkByParagraph(text: String, chunkSize: Int, overlap: Int): List<String> {
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        for (p in paragraphs) {
            if (p.isBlank()) continue
            if (current.length + p.length > chunkSize && current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current.clear()
                // 从上一个块末尾取 overlap 字符用于重叠
                if (overlap > 0 && chunks.isNotEmpty()) {
                    val last = chunks.last()
                    current.append(last.takeLast(minOf(overlap, last.length)))
                }
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(p.trim())
        }
        if (current.isNotBlank()) chunks.add(current.toString().trim())
        return chunks
    }

    /** 按 Markdown 标题分块 */
    private fun chunkByMarkdown(text: String, chunkSize: Int, overlap: Int): List<String> {
        val sections = text.split(Regex("(?=^#{1,6}\\s)", RegexOption.MULTILINE))
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        for (section in sections) {
            if (section.isBlank()) continue
            if (current.length + section.length > chunkSize && current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current.clear()
                if (overlap > 0 && chunks.isNotEmpty()) {
                    val last = chunks.last()
                    current.append(last.takeLast(minOf(overlap, last.length)))
                }
            }
            current.append(section.trim()).append("\n\n")
        }
        if (current.isNotBlank()) chunks.add(current.toString().trim())
        return chunks
    }

    /** 按代码块分块（按函数/类分割，不适合时回退到固定大小） */
    private fun chunkByCode(text: String, chunkSize: Int, overlap: Int): List<String> {
        // 尝试按函数/类定义分割
        val sections = text.split(Regex("(?=fun\\s+|class\\s+|object\\s+|interface\\s+|sealed\\s+)"))
        if (sections.size > 1) {
            val chunks = mutableListOf<String>()
            val current = StringBuilder()
            for (section in sections) {
                if (section.isBlank()) continue
                if (current.length + section.length > chunkSize && current.isNotEmpty()) {
                    chunks.add(current.toString().trim())
                    current.clear()
                    if (overlap > 0 && chunks.isNotEmpty()) {
                        val last = chunks.last()
                        current.append(last.takeLast(minOf(overlap, last.length)))
                    }
                }
                current.append(section.trim()).append("\n\n")
            }
            if (current.isNotBlank()) chunks.add(current.toString().trim())
            if (chunks.isNotEmpty()) return chunks
        }
        // 回退到固定大小
        return chunkByFixed(text, chunkSize, overlap)
    }
}
