package me.rerere.rikkahub.data.knowledge

import kotlin.math.sqrt

/**
 * 文本分块工具
 * 支持多种分块策略
 */
object TextChunker {

    /** 语义分块：相邻句子相似度低于此阈值时断开 */
    private const val SEMANTIC_BREAK_THRESHOLD = 0.65f
    /** 语义分块：最小分块数（避免分得太碎） */
    private const val MIN_SEMANTIC_CHUNKS = 2

    /**
     * 将文本分成块
     * @param text 源文本
     * @param chunkSize 每块最大字符数
     * @param chunkOverlap 块重叠字符数
     * @param strategy 分块策略
     * @param semanticEmbedder 语义分块时使用的嵌入函数（strategy="semantic" 时必传）
     */
    fun chunk(
        text: String,
        chunkSize: Int = 1000,
        chunkOverlap: Int = 200,
        strategy: String = "fixed",
        semanticEmbedder: ((String) -> List<Float>)? = null,
    ): List<String> {
        if (text.isBlank()) return emptyList()
        return when (strategy) {
            "paragraph" -> chunkByParagraph(text, chunkSize, chunkOverlap)
            "markdown" -> chunkByMarkdown(text, chunkSize, chunkOverlap)
            "code" -> chunkByCode(text, chunkSize, chunkOverlap)
            "semantic" -> chunkBySemantic(text, chunkSize, chunkOverlap, semanticEmbedder)
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
    /** 按 Markdown 标题分块（带标题层级上下文） */
    private fun chunkByMarkdown(text: String, chunkSize: Int, overlap: Int): List<String> {
        val sections = text.split(Regex("(?=^#{1,6}\\s)", RegexOption.MULTILINE))
        if (sections.size <= 1) return chunkByFixed(text, chunkSize, overlap)

        // 跟踪当前标题层级链，用于给子标题添加上下文
        val headingStack = mutableListOf<String>()
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        for (section in sections) {
            if (section.isBlank()) continue

            // 解析当前标题的层级
            val firstLine = section.lines().firstOrNull()?.trim() ?: continue
            val headingMatch = Regex("^(#{1,6})\\s+(.+)").find(firstLine)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val headingText = headingMatch.groupValues[2].trim()

                // 更新层级栈：弹出比当前层级更深或相等的标题
                while (headingStack.isNotEmpty() && headingStack.size >= level) {
                    headingStack.removeLastOrNull()
                }
                headingStack.add(headingText)
            }

            // 构建带层级上下文的标题前缀
            val contextPrefix = if (headingStack.size > 1) {
                headingStack.dropLast(1).joinToString(" > ") + " > "
            } else ""

            val sectionText = section.trim()
            val sectionWithContext = if (contextPrefix.isNotEmpty()) {
                // 在当前标题前插入父级路径
                val lines_ = sectionText.lines()
                val firstHeading = lines_.firstOrNull() ?: sectionText
                val rest = lines_.drop(1).joinToString("\n")
                "$contextPrefix$firstHeading\n$rest"
            } else sectionText

            if (current.length + sectionWithContext.length > chunkSize && current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current.clear()
                if (overlap > 0 && chunks.isNotEmpty()) {
                    val last = chunks.last()
                    current.append(last.takeLast(minOf(overlap, last.length)))
                }
            }
            current.append(sectionWithContext).append("\n\n")
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

    /**
     * 语义分块：按句子 embedding 相似度突变点断开
     *
     * 1. 将文本拆成句子
     * 2. 对每个句子生成 embedding
     * 3. 计算相邻句子余弦相似度
     * 4. 相似度低于阈值处断开，形成语义段落
     * 5. 合并段落成 chunk，确保不超过 chunkSize
     */
    private fun chunkBySemantic(
        text: String,
        chunkSize: Int,
        overlap: Int,
        embedder: ((String) -> List<Float>)?,
    ): List<String> {
        // 如果没有 embedder，回退到段落分块
        if (embedder == null) return chunkByParagraph(text, chunkSize, overlap)

        // 1. 拆句子（按中文/英文句号、问号、感叹号、换行分割）
        val sentences = text.split(Regex("(?<=[。！？.!?\n])\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (sentences.size <= MIN_SEMANTIC_CHUNKS) {
            return chunkByFixed(text, chunkSize, overlap)
        }

        // 2. 对每个句子生成 embedding
        val embeddings = try {
            sentences.map { embedder(it) }
        } catch (_: Exception) {
            return chunkByParagraph(text, chunkSize, overlap)
        }

        // 如果有句子 embedding 为空，回退
        if (embeddings.any { it.isEmpty() }) {
            return chunkByParagraph(text, chunkSize, overlap)
        }

        // 3. 计算相邻句子的相似度，标记断点
        val breakPoints = mutableSetOf<Int>()
        for (i in 0 until sentences.size - 1) {
            val sim = cosineSimilarity(embeddings[i], embeddings[i + 1])
            if (sim < SEMANTIC_BREAK_THRESHOLD) {
                breakPoints.add(i + 1) // 在第 i+1 句前断开
            }
        }

        // 4. 按断点合并句子成语义段落
        val paragraphs = mutableListOf<String>()
        var start = 0
        for (bp in (breakPoints.sorted() + sentences.size)) {
            val para = sentences.subList(start, bp).joinToString("").trim()
            if (para.isNotBlank()) paragraphs.add(para)
            start = bp
        }

        if (paragraphs.isEmpty()) return chunkByFixed(text, chunkSize, overlap)

        // 5. 合并段落成最终 chunk，不超过 chunkSize
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (para in paragraphs) {
            if (current.length + para.length > chunkSize && current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current.clear()
                if (overlap > 0 && chunks.isNotEmpty()) {
                    current.append(chunks.last().takeLast(minOf(overlap, chunks.last().length)))
                }
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(para)
        }
        if (current.isNotBlank()) chunks.add(current.toString().trim())

        return if (chunks.isEmpty()) listOf(text) else chunks
    }

    /** 计算两个向量的余弦相似度 */
    private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0.0) 0f else (dotProduct / denom).toFloat()
    }

}
