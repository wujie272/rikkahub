package me.rerere.rikkahub.data.knowledge

/**
 * Markdown 工具函数
 * 前置元数据解析 + 标题提取 + 标签提取
 */
object MarkdownUtils {

    /**
     * 解析 Markdown 前置元数据（YAML frontmatter）
     * 支持格式：
     * ```yaml
     * ---
     * title: 我的笔记
     * tags: [tag1, tag2]
     * date: 2024-01-01
     * ---
     * 正文内容...
     * ```
     */
    data class Frontmatter(
        val title: String = "",
        val tags: List<String> = emptyList(),
        val raw: Map<String, String> = emptyMap(),
    )

    /**
     * 解析 frontmatter，返回 (frontmatter, 剩余正文)
     */
    fun parseFrontmatter(content: String): Pair<Frontmatter, String> {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) return Frontmatter() to content

        val endIndex = trimmed.indexOf("---", startIndex = 3)
        if (endIndex == -1) return Frontmatter() to content

        val yamlBlock = trimmed.substring(3, endIndex).trim()
        val body = trimmed.substring(endIndex + 3).trim()

        if (yamlBlock.isBlank()) return Frontmatter() to body

        val raw = mutableMapOf<String, String>()
        var title = ""
        val tags = mutableListOf<String>()

        for (line in yamlBlock.lines()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx == -1) continue
            val key = line.substring(0, colonIdx).trim().lowercase()
            var value = line.substring(colonIdx + 1).trim()

            // 处理数组格式: [a, b, c] 或 [a, b, c]
            if (value.startsWith("[") && value.endsWith("]")) {
                val listValue = value.removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                    .filter { it.isNotBlank() }
                if (key == "tags" || key == "tag" || key == "categories" || key == "category") {
                    tags.addAll(listValue)
                }
                raw[key] = listValue.joinToString(", ")
                continue
            }

            // 处理 YAML 列表格式: - item
            if (value.startsWith("- ")) {
                val listItems = yamlBlock.lines()
                    .dropWhile { it != line }
                    .takeWhile { it.trimStart().startsWith("- ") }
                    .map { it.trimStart().removePrefix("- ").trim() }
                if (key == "tags" || key == "tag" || key == "categories" || key == "category") {
                    tags.addAll(listItems)
                }
                raw[key] = listItems.joinToString(", ")
                continue
            }

            // 普通键值对
            value = value.removeSurrounding("\"").removeSurrounding("'")
            raw[key] = value

            when (key) {
                "title" -> title = value
                "tag", "tags" -> tags.addAll(value.split(Regex("[,，]")).map { it.trim() }.filter { it.isNotBlank() })
                "category", "categories" -> tags.addAll(value.split(Regex("[,，]")).map { it.trim() }.filter { it.isNotBlank() })
            }
        }

        return Frontmatter(title = title, tags = tags.distinct(), raw = raw) to body
    }

    /**
     * 从正文中提取第一个一级标题（# Title）
     */
    fun extractFirstTitle(content: String): String? {
        val lines = content.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                return trimmed.removePrefix("# ").trim()
            }
        }
        return null
    }

    /**
     * 从正文中提取 #tag 格式的标签
     */
    fun extractInlineTags(content: String): List<String> {
        val tagRegex = Regex("(?:^|\\s)#([\\u4e00-\\u9fff\\w\\-]+)")
        return tagRegex.findAll(content)
            .map { it.groupValues[1] }
            .filter { it.length in 2..30 }
            .distinct()
            .toList()
    }

    /**
     * 清理 Obsidian 双链和 Markdown 链接，提取可读文本
     *
     * 处理格式：
     * - [[Note Name]] → Note Name
     * - [[Note|Display]] → Display
     * - [[Note#Heading]] → Note > Heading
     * - [[Note#^block]] → Note
     * - [text](url) → text
     * - ![alt](img.png) → 移除
     * - [](url) → 移除
     */
    fun cleanObsidianLinks(text: String): String {
        if (!text.contains("[[") && !text.contains("](")) return text

        var result = text

        // 1. 处理 Obsidian 双链 [[...]]
        // 优先匹配带显示文本的 [[link|display]]
        result = result.replace(Regex("\\[\\[([^|#]]+)\\|([^]]+)]\\]")) { match ->
            match.groupValues[2].trim()
        }
        // 匹配带标题的 [[link#Heading]]
        result = result.replace(Regex("\\[\\[([^|#^]]+)#([^]]+)]]")) { match ->
            val note = match.groupValues[1].trim()
            val heading = match.groupValues[2].trim()
            // 如果是块引用 ^block-id，只保留笔记名
            if (heading.startsWith("^")) note else "$note > $heading"
        }
        // 匹配纯链接 [[Note Name]]
        result = result.replace(Regex("\\[\\[([^]]+)]]")) { match ->
            match.groupValues[1].trim()
        }

        // 2. 处理图片 ![](url) — 完全移除
        result = result.replace(Regex("!\\[([^]]*)\\]\\([^)]*\\)")) { match ->
            val alt = match.groupValues[1].trim()
            if (alt.isNotBlank()) alt else ""
        }

        // 3. 处理普通链接 [text](url) — 保留文字，去掉 URL
        result = result.replace(Regex("\\[([^]]+)]\\(([^)]+)\\)")) { match ->
            match.groupValues[1].trim()
        }

        // 4. 清理残留的空括号和多余空白
        result = result.replace(Regex("\\[\\]\\(\\)"), "")
        result = result.replace(Regex("\\s{2,}"), " ")

        return result.trim()
    }
}
