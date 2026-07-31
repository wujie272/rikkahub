package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.knowledge.KnowledgeService
import kotlinx.coroutines.runBlocking

fun createKnowledgeBaseTools(
    knowledgeService: KnowledgeService,
): List<Tool> = listOf(
    Tool(
        name = "knowledge_search",
        systemPrompt = { _, _ ->
            runBlocking {
                val bases = knowledgeService.getAllKnowledgeBases()
                if (bases.isEmpty()) return@runBlocking ""
                buildString {
                    appendLine("\n<available_knowledge_bases>")
                    bases.forEach { kb ->
                        appendLine("  <knowledge_base>")
                        appendLine("    <id>${kb.id}</id>")
                        appendLine("    <name>${kb.name}</name>")
                        if (kb.description.isNotBlank()) {
                            appendLine("    <description>${kb.description}</description>")
                        }
                        appendLine("  </knowledge_base>")
                    }
                    appendLine("</available_knowledge_bases>")
                    appendLine("Search them with knowledge_search(query, kb_id?). Omit kb_id to search all.")
                }
            }
        },
        description = """
            Search the user's knowledge bases for relevant information.
            Use this when the user asks about specific topics that may be stored in their knowledge bases.
            Performs semantic + keyword hybrid search and returns relevant document chunks with relevance scores.
            Optionally specify a knowledge base ID to search a specific base, or omit to search all available bases.
            The results include expanded context (neighbouring chunks) for better understanding.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Search query to find relevant knowledge")
                    })
                    put("kb_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional: specific knowledge base ID to search. Use knowledge_list_bases to discover available IDs.")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum number of results to return (default: 5, max: 20)")
                    })
                    put("min_score", buildJsonObject {
                        put("type", "number")
                        put("description", "Minimum similarity score threshold 0.0~1.0 (default: use knowledge base's configured threshold)")
                    })
                    put("tag_filter", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional: filter results by tag")
                    })
                },
                required = listOf("query")
            )
        },
        execute = {
            val params = it.jsonObject
            val query = params["query"]?.jsonPrimitive?.contentOrNull ?: error("query is required")
            val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 20)
            val minScore = params["min_score"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
            val tagFilter = params["tag_filter"]?.jsonPrimitive?.contentOrNull
            val kbId = params["kb_id"]?.jsonPrimitive?.contentOrNull

            val results = if (kbId != null) {
                knowledgeService.search(kbId, query, limit, minScore, tagFilter)
            } else {
                val allBases = knowledgeService.getAllKnowledgeBases()
                if (allBases.isEmpty()) {
                    emptyList()
                } else {
                    val perBaseLimit = (limit / allBases.size).coerceAtLeast(1)
                    allBases.flatMap { kb ->
                        knowledgeService.search(kb.id, query, perBaseLimit, minScore, tagFilter)
                    }.sortedByDescending { it.score }.take(limit)
                }
            }

            if (results.isEmpty() && kbId == null) {
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("notice", "No knowledge bases found. Create one in Settings first.")
                    put("results", buildJsonArray { })
                }.toString()))
            } else {
                val payload = buildJsonObject {
                    put("query", query)
                    put("total_results", results.size)
                    put("results", buildJsonArray {
                        results.forEach { r ->
                            add(buildJsonObject {
                                put("document_id", r.documentId)
                                put("knowledge_base_id", r.knowledgeBaseId)
                                put("file_name", r.fileName)
                                put("file_path", r.filePath)
                                put("chunk_index", r.chunkIndex)
                                put("content", r.content.take(2000))
                                put("score", r.score)
                                put("semantic_score", r.semanticScore)
                                put("bm25_score", r.bm25Score)
                                put("tags", r.tags)
                                if (r.expandedContext.isNotBlank()) {
                                    put("expanded_context", r.expandedContext.take(3000))
                                }
                            })
                        }
                    })
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        }
    ),
    Tool(
        name = "knowledge_list_bases",
        description = "List all available knowledge bases with their metadata. Use this first to discover knowledge base IDs before searching.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { })
        },
        execute = {
            val bases = knowledgeService.getAllKnowledgeBases()
            val payload = buildJsonObject {
                put("total", bases.size)
                put("knowledge_bases", buildJsonArray {
                    bases.forEach { kb ->
                        add(buildJsonObject {
                            put("id", kb.id)
                            put("name", kb.name)
                            put("description", kb.description)
                            put("chunk_strategy", kb.chunkStrategy)
                            put("chunk_size", kb.chunkSize)
                            put("threshold", kb.threshold)
                            put("document_count", kb.documentCount)
                        })
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
)
