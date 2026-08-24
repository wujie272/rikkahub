package me.rerere.rikkahub.data.files

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

object SkillFrontmatterParser {
    private val frontmatterEndRegex = Regex("""\r?\n---(?:\r?\n|$)""")

    /** UTF-8 BOM character. Some editors prepend this to UTF-8 files. */
    private const val BOM = '\ufeff'

    fun parse(content: String): SkillFrontmatter {
        val normalised = if (content.startsWith(BOM)) content.substring(1) else content
        if (!normalised.startsWith("---")) return SkillFrontmatter.Empty
        val endRange = findFrontmatterEndRange(normalised) ?: return SkillFrontmatter.Empty
        val yamlContent = normalised.substring(3, endRange.first).trim()
        if (yamlContent.isEmpty()) return SkillFrontmatter.Empty

        return runCatching {
            val values = createYaml().load<Any?>(yamlContent) as? Map<*, *>
                ?: return SkillFrontmatter.Empty
            SkillFrontmatter(
                values.entries.mapNotNull { (key, value) ->
                    (key as? String)?.let { it to value }
                }.toMap()
            )
        }.getOrDefault(SkillFrontmatter.Empty)
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

    private fun createYaml(): Yaml {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 50
            nestingDepthLimit = 50
            codePointLimit = 1_000_000
        }
        return Yaml(SafeConstructor(options))
    }
}

class SkillFrontmatter internal constructor(
    private val values: Map<String, Any?>,
) {
    operator fun get(key: String): String? = values[key] as? String

    companion object {
        internal val Empty = SkillFrontmatter(emptyMap())
    }
}
