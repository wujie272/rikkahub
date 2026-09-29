package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata

// 与 Agent Skills 规范的 description 上限一致
private const val MAX_SKILL_DESCRIPTION_LENGTH = 1024

// 与 Agent Skills 规范的 description 上限一致
private const val MAX_SKILL_DESCRIPTION_LENGTH = 1024

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    skillManager: SkillManager,
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    if (available.isEmpty()) return emptyList()

    return listOf(
        // Phase 16 audit fix — read-only accessor so the LLM can show a skill's content
        // without re-installing it. Sits under the same skills surface as use_skill.
        skillGetContentTool(
            enabledSkills = enabledSkills,
            allSkills = allSkills,
            contentReader = skillManager::getContent,
        ),
        Tool(
            name = "use_skill",
            description = """
                Load and apply a skill to get specialized instructions or capabilities.
                Call this tool when the user's request matches one of the available skills.
            """.trimIndent(),
            systemPrompt = { _, _ ->
                buildString {
                    appendLine("**Skills**")
                    appendLine("You have access to the following skills. Use the `use_skill` tool to load a skill's instructions when the user's request matches.")
                    appendLine("<available_skills>")
                    available.forEach { skill ->
                        appendLine("  <skill>")
                        // 技能可能来自第三方导入，转义并限长，防止 name/description 闭合标签注入任意系统提示
                        appendLine("    <name>${skill.name.escapeXml()}</name>")
                        appendLine("    <description>${skill.description.take(MAX_SKILL_DESCRIPTION_LENGTH).escapeXml()}</description>")
                        appendLine("  </skill>")
                    }
                }
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The name of the skill to use")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                // Return structured error envelopes instead of throwing.
                // small models hit `use_skill` with `{}` (no name) regularly; before
                // this fix the LLM saw a 20-frame Java stack trace and gave up. The
                // recovery hint + available_skills list lets the model self-correct
                // on its next call.
                fun err(code: String, detail: String): List<UIMessagePart> = listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", code)
                            put("detail", detail)
                            put("recovery", "Re-call use_skill with one of the listed skill names in `name`.")
                            put(
                                "available_skills",
                                kotlinx.serialization.json.buildJsonArray {
                                    enabledSkills.forEach {
                                        add(kotlinx.serialization.json.JsonPrimitive(it))
                                    }
                                },
                            )
                        }.toString()
                    )
                )
                // Refuse oversized skill files before reading them whole. SkillManager
                // enforces the same cap on its cached reads (readCached); this is the
                // model-facing envelope so the LLM gets a clean error instead of a
                // failed/empty read.
                fun tooLargeErr(file: java.io.File): List<UIMessagePart> = listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "skill_file_too_large")
                            put("max_bytes", SkillManager.MAX_SKILL_FILE_BYTES)
                            put("size_bytes", file.length())
                        }.toString()
                    )
                )
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")
                // 模型可能照抄系统提示中转义后的名称，两种形式都接受
                val skill = available.firstOrNull { skill -> skill.name == name || skill.name.escapeXml() == name }
                    ?: error("Skill '$name' is not available. Available skills: ${available.joinToString { it.name }}")
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                val content = if (path.isNullOrBlank()) {
                    require(skill.skillFile.exists()) { "Skill '$name' not found" }
                    SkillFrontmatterParser.extractBody(skill.skillFile.readText())
                } else {
                    val target = SkillPaths.resolveSkillFile(skill.skillDir, path)
                        ?: error("Path '$path' is outside the skill directory")
                    require(target.exists()) { "File '$path' not found in skill '$name'" }
                    target.readText()
                }
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                if (path.isNullOrBlank()) {
                    val skillMd = skillManager.getSkillDir(name)?.resolve("SKILL.md")
                    if (skillMd != null && skillMd.length() > SkillManager.MAX_SKILL_FILE_BYTES) {
                        return@Tool tooLargeErr(skillMd)
                    }
                    val content = skillManager.readSkillBody(name)
                        ?: return@Tool err(
                            "skill_body_not_found",
                            "Skill '$name' is enabled but its SKILL.md body could not be read on disk.",
                        )
                    return@Tool listOf(UIMessagePart.Text(content))
                }
                val target = skillManager.resolveSkillFile(name, path)
                    ?: return@Tool err(
                        "path_outside_skill",
                        "Path '$path' resolves outside the '$name' skill directory.",
                    )
                if (!target.exists()) {
                    return@Tool err(
                        "skill_file_not_found",
                        "File '$path' does not exist in skill '$name'. Use only paths from Markdown links inside SKILL.md.",
                    )
                }
                if (target.length() > SkillManager.MAX_SKILL_FILE_BYTES) {
                    return@Tool tooLargeErr(target)
                }
                listOf(UIMessagePart.Text(target.readText()))
            }
        )
    )
}

private fun String.escapeXml(): String = buildString(length) {
    for (c in this@escapeXml) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }
}
