package me.rerere.rikkahub.data.knowledge

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * 基于 JetBrains Markdown 解析器 AST 的分块器。
 *
 * 相比 [TextChunker] 的纯正则方案，AST 分块天然解决：
 * - 代码块内 `#` 不被误识别为标题
 * - 表格、列表不被结构性地切碎
 * - 标题层级关系精确可追踪
 *
 * 使用方式：已通过 [TextChunker.chunk] 的 "markdown" 策略集成，
 * 也可直接调用 [chunk] 获取带元数据的分块结果。
 */
object MarkdownChunker {

    data class ChunkWithMeta(
        /** 分块纯文本（已注入标题路径上下文） */
        val text: String,
        /** 完整标题层级路径，如 ["父标题", "子标题"] */
        val headingPath: List<String>,
        /** 在原文中的起始偏移量 */
        val startOffset: Int,
        /** 在原文中的结束偏移量 */
        val endOffset: Int,
    )

    private val flavour = GFMFlavourDescriptor()
    private val parser = MarkdownParser(flavour)

    /** 匹配 Markdown 图片语法 ![alt](src) */
    private val imagePattern = Regex("""!\[([^\]]*)\]\([^)]*\)""")

    /** 匹配代码块，提取alt文本前先移除避免误匹配 */
    private val codeBlockPattern = Regex("""```[\s\S]*?```""")

    /**
     * 按 Markdown 标题结构分块，返回带元数据的分块。
     *
     * @param markdown 已清理 frontmatter 的 Markdown 文本
     * @param chunkSize 每块最大字符数
     * @param chunkOverlap 块重叠字符数（仅二级拆分时生效）
     * @param splitLevels 参与分块的标题级别，默认 1~6
     * @param injectHeadingPath 是否在分块文本中注入标题路径上下文
     */
    fun chunk(
        markdown: String,
        chunkSize: Int = 1000,
        chunkOverlap: Int = 200,
        splitLevels: IntRange = 1..6,
        injectHeadingPath: Boolean = true,
    ): List<ChunkWithMeta> {
        if (markdown.isBlank()) return emptyList()

        val ast = parser.buildMarkdownTreeFromString(markdown)

        // Phase 1: 按标题边界分组
        val sections = buildSections(ast, markdown, splitLevels)

        if (sections.isEmpty()) return emptyList()

        // Phase 2: 对超大 section 做二级拆分（段落 → 固定大小）
        val result = mutableListOf<ChunkWithMeta>()
        for (section in sections) {
            if (section.text.length <= chunkSize) {
                result.add(section)
            } else {
                result.addAll(splitLargeSection(section, chunkSize, chunkOverlap))
            }
        }

        // Phase 3: 注入标题路径上下文 + 图片alt文本
        return result.map { chunk ->
            var text = chunk.text

            // 注入标题路径上下文
            if (injectHeadingPath && chunk.headingPath.size > 1) {
                val contextPrefix = chunk.headingPath.dropLast(1).joinToString(" > ") + " > "
                val firstLine = text.lines().firstOrNull() ?: ""
                val rest = text.lines().drop(1).joinToString("\n")
                text = if (rest.isNotBlank()) {
                    "$contextPrefix$firstLine\n$rest"
                } else {
                    "$contextPrefix$firstLine"
                }
            }

            // 注入图片alt文本，让图片语义也能被搜索命中
            val alts = extractImageAlts(text)
            if (alts.isNotEmpty()) {
                text = text + "\n\n> 🖼️ " + alts.joinToString(", ")
            }

            chunk.copy(text = text)
        }
    }

    /**
     * 分块并返回纯文本列表（兼容 [TextChunker] 接口）
     */
    fun chunkToTexts(
        markdown: String,
        chunkSize: Int = 1000,
        chunkOverlap: Int = 200,
    ): List<String> {
        return chunk(markdown, chunkSize, chunkOverlap).map { it.text }
    }

    // ============ AST 分节 ============

    /**
     * 遍历 AST 根节点，按标题边界分组。
     * 每个标题及其后续内容组成一个 section，直到遇到同级或更高级标题。
     */
    private fun buildSections(
        root: ASTNode,
        source: String,
        splitLevels: IntRange,
    ): List<ChunkWithMeta> {
        val sections = mutableListOf<ChunkWithMeta>()
        val headingStack = mutableListOf<Pair<Int, String>>() // (level, text)

        val children = root.children
        if (children.isEmpty()) return emptyList()

        var sectionStart = children.first().startOffset

        for (child in children) {
            val headingInfo = extractHeading(child, source)
            if (headingInfo != null) {
                val (level, headingText) = headingInfo

                // 只在配置的标题级别范围内切分
                if (level in splitLevels) {
                    // 前一个 section 结束于当前标题前
                    val sectionEnd = child.startOffset
                    if (sectionEnd > sectionStart) {
                        val sectionText = source.substring(sectionStart, sectionEnd).trim()
                        if (sectionText.isNotBlank()) {
                            sections.add(
                                ChunkWithMeta(
                                    text = sectionText,
                                    headingPath = headingStack.map { it.second },
                                    startOffset = sectionStart,
                                    endOffset = sectionEnd,
                                )
                            )
                        }
                    }

                    // 更新标题栈：弹出 >= 当前层级的标题
                    while (headingStack.isNotEmpty() && headingStack.last().first >= level) {
                        headingStack.removeLast()
                    }
                    headingStack.add(level to headingText)

                    // 新 section 从当前标题开始
                    sectionStart = child.startOffset
                }
            }
        }

        // 最后一个 section（从最后一个标题/开头到文档结束）
        val tailText = source.substring(sectionStart).trim()
        if (tailText.isNotBlank()) {
            sections.add(
                ChunkWithMeta(
                    text = tailText,
                    headingPath = headingStack.map { it.second },
                    startOffset = sectionStart,
                    endOffset = source.length,
                )
            )
        }

        // 如果没有任何标题，回退到整个文档作为一个 section
        if (sections.isEmpty()) {
            sections.add(
                ChunkWithMeta(
                    text = source.trim(),
                    headingPath = emptyList(),
                    startOffset = 0,
                    endOffset = source.length,
                )
            )
        }

        return sections
    }

    /**
     * 从 AST 节点中提取标题信息。
     * 返回 (level, headingText) 或 null（如果不是标题节点）。
     */
    private fun extractHeading(node: ASTNode, source: String): Pair<Int, String>? {
        val level = when (node.type) {
            MarkdownElementTypes.ATX_1 -> 1
            MarkdownElementTypes.ATX_2 -> 2
            MarkdownElementTypes.ATX_3 -> 3
            MarkdownElementTypes.ATX_4 -> 4
            MarkdownElementTypes.ATX_5 -> 5
            MarkdownElementTypes.ATX_6 -> 6
            else -> return null
        }

        // 从 ATX_CONTENT 子节点提取标题文本
        val headingText = node.children
            .firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT }
            ?.getTextInNode(source)
            ?.trim()
            ?: ""

        return level to headingText
    }

    // ============ 二级拆分 ============

    /**
     * 对大 section 进行二级拆分：
     * 1. 按段落（双换行）拆
     * 2. 段落仍超限则按固定大小拆
     */
    private fun splitLargeSection(
        section: ChunkWithMeta,
        chunkSize: Int,
        overlap: Int,
    ): List<ChunkWithMeta> {
        val text = section.text
        if (text.length <= chunkSize) return listOf(section)

        // 按段落拆分
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (paragraphs.size <= 1) {
            // 没有段落结构，直接固定大小拆
            return splitByFixedWithMeta(text, section.headingPath, section.startOffset, chunkSize, overlap)
        }

        val chunks = mutableListOf<ChunkWithMeta>()
        val current = StringBuilder()
        var currentLen = 0
        var paraStartOffset = section.startOffset

        for (para in paragraphs) {
            val paraLen = para.length + 2 // +2 for "\n\n"
            if (currentLen + paraLen > chunkSize && current.isNotEmpty()) {
                chunks.add(
                    ChunkWithMeta(
                        text = current.toString().trim(),
                        headingPath = section.headingPath,
                        startOffset = paraStartOffset,
                        endOffset = paraStartOffset + currentLen,
                    )
                )
                current.clear()
                // overlap
                if (overlap > 0 && chunks.isNotEmpty()) {
                    val last = chunks.last().text
                    val overlapText = last.takeLast(minOf(overlap, last.length))
                    current.append(overlapText)
                    currentLen = overlapText.length
                } else {
                    currentLen = 0
                }
                paraStartOffset = section.startOffset + (paragraphs.takeWhile { it != para }.sumOf { it.length + 2 })
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(para)
            currentLen += paraLen
        }

        if (current.isNotBlank()) {
            chunks.add(
                ChunkWithMeta(
                    text = current.toString().trim(),
                    headingPath = section.headingPath,
                    startOffset = paraStartOffset,
                    endOffset = section.endOffset,
                )
            )
        }

        return chunks
    }

    /**
     * 固定大小拆分的带元数据版本
     */
    private fun splitByFixedWithMeta(
        text: String,
        headingPath: List<String>,
        baseOffset: Int,
        chunkSize: Int,
        overlap: Int,
    ): List<ChunkWithMeta> {
        if (text.length <= chunkSize) {
            return listOf(ChunkWithMeta(text, headingPath, baseOffset, baseOffset + text.length))
        }

        val chunks = mutableListOf<ChunkWithMeta>()
        var start = 0
        var chunkIdx = 0
        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            chunks.add(
                ChunkWithMeta(
                    text = text.substring(start, end),
                    headingPath = headingPath,
                    startOffset = baseOffset + start,
                    endOffset = baseOffset + end,
                )
            )
            chunkIdx++
            start += chunkSize - overlap
            if (start >= text.length) break
        }
        return chunks
    }

    /** 从 AST 节点提取文本（与 Markdown.kt 中一致的方法） */
    private fun ASTNode.getTextInNode(text: String): String {

        return text.substring(startOffset, endOffset)
    }

    /**
     * 从文本中提取所有图片的alt文本。
     * 先移除代码块避免 ``` 内 `![...](...)` 被误匹配。
     */
    private fun extractImageAlts(text: String): List<String> {
        val withoutCode = text.replace(codeBlockPattern, "")
        return imagePattern.findAll(withoutCode)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }
}
