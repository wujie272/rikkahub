package me.rerere.rikkahub.data.files

object SkillFrontmatterParser {
    private val frontmatterEndRegex = Regex("""\r?\n---(?:\r?\n|$)""")

    /** UTF-8 BOM character. Some editors prepend this to UTF-8 files. */
    private const val BOM = '\ufeff'

    fun parse(content: String): Map<String, String> {
        val normalised = if (content.startsWith(BOM)) content.substring(1) else content
        val result = mutableMapOf<String, String>()
        if (!normalised.startsWith("---")) return result
        val endRange = findFrontmatterEndRange(normalised) ?: return result
        val yaml = normalised.substring(3, endRange.first).trim()
        yaml.lines().forEach { line ->
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim().removeSurrounding("\"")
                if (key.isNotBlank() && value.isNotBlank()) {
                    result[key] = value
                }
            }
        }
        return result
    }

    fun extractBody(content: String): String {
        val normalised = if (content.startsWith(BOM)) content.substring(1) else content
        if (!normalised.startsWith("---")) return normalised
        val endRange = findFrontmatterEndRange(normalised) ?: return normalised
        return normalised.substring(endRange.last + 1).trimStart('\r', '\n')
    }

    private fun findFrontmatterEndRange(content: String): IntRange? {
        if (!content.startsWith("---")) return null
        return frontmatterEndRegex.find(content, startIndex = 3)?.range
    }
}
