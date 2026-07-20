package me.rerere.rikkahub.data.knowledge

import kotlin.math.min

/**
 * 增强的混合搜索服务
 *
 * 对标 AetherLink 的 EnhancedRAGService，但集成到你的 RikkaHub 架构：
 *
 * 1. 向量语义搜索 + BM25 关键词搜索 → 混合融合
 * 2. 自适应权重：语义分低时 BM25 权重自动提升（关键词精确匹配补位）
 * 3. 上下文展开：命中 chunk 带前后相邻 chunk
 * 4. 跨文件去重：同一文件只保留最高分 chunk
 * 5. 查询扩展：同义词扩展 + 查询分解（轻量版）
 */
class KnowledgeSearchService(
    private val documentDao: KnowledgeDocumentDao,
    private val embeddingService: EmbeddingService,
) {
    data class SearchResult(
        val documentId: String,
        val knowledgeBaseId: String,
        val filePath: String,
        val fileName: String,
        val chunkIndex: Int,
        val content: String,
        /** 融合后的最终分数 */
        val score: Float,
        /** 语义相似度分数（0~1） */
        val semanticScore: Float = 0f,
        /** BM25 关键词分数（0~1，归一化后） */
        val bm25Score: Float = 0f,
        /** 合并后的上下文文本（包含前后相邻 chunk） */
        val expandedContext: String = "",
        /** 标签 */
        val tags: String = "",
    )

    companion object {
        /** 语义分数的权重（0~1），BM25 权重 = 1 - SEMANTIC_WEIGHT */
        private const val SEMANTIC_WEIGHT = 0.7f
        /** 上下文展开：命中 chunk 前后各取 N 个 chunk */
        private const val CONTEXT_WINDOW = 1
        /** 展开上下文单 chunk 截断长度 */
        private const val CONTEXT_CHUNK_MAX = 600
        /** Reranking：关键词匹配加分上限 */
        private const val RERANK_MAX_BONUS = 0.2f
    }

    /** BM25 索引缓存（懒加载） */
    private var bm25Index: Bm25Index? = null
    private var bm25CacheKey: String = ""

    /**
     * 增强搜索主入口
     *
     * @param kbId 知识库 ID
     * @param query 搜索关键词
     * @param limit 最多返回条数
     * @param minScore 最低相似度阈值 (0~1)
     * @param enableHybrid 是否启用混合搜索（BM25 + 语义）
     * @param expandContext 是否展开上下文（合并相邻 chunk）
     * @param enableRerank 是否启用 reranking（关键词命中加分）
     * @param tagFilter 可选标签过滤
     */
    suspend fun search(
        kbId: String,
        modelId: String,
        query: String,
        limit: Int = 6,
        minScore: Float = 0.35f,
        enableHybrid: Boolean = true,
        expandContext: Boolean = true,
        enableQueryExpansion: Boolean = true,
        enableRerank: Boolean = true,
        tagFilter: String? = null,
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        // 1. 获取候选数据（支持标签过滤）
        val candidates = if (tagFilter != null && tagFilter.isNotBlank()) {
            documentDao.getSearchableByTag(kbId, tagFilter)
        } else {
            documentDao.getSearchable(kbId)
        }
        if (candidates.isEmpty()) return emptyList()

        // 2. 查询扩展（生成多个搜索变体）
        val queries = if (enableQueryExpansion) {
            expandQuery(query)
        } else {
            listOf(query)
        }

        // 3. 生成所有 query 的向量
        val queryVectors = queries.map { q ->
            q to (try { embeddingService.embed(q, modelId) } catch (_: Exception) { emptyList() })
        }.filter { it.second.isNotEmpty() }

        if (queryVectors.isEmpty()) return emptyList()

        // 4. 语义搜索（用所有扩展 query 搜索，取最高分）
        val semanticScores = mutableMapOf<String, Float>()
        for ((q, qVec) in queryVectors) {
            for (doc in candidates) {
                val docVec = doc.vector?.let { VectorUtils.jsonToVector(it) } ?: continue
                val score = VectorUtils.cosineSimilarity(qVec, docVec)
                val existing = semanticScores[doc.id] ?: 0f
                if (score > existing) {
                    semanticScores[doc.id] = score
                }
            }
        }

        // 5. BM25 混合搜索
        val bm25Scores = if (enableHybrid) {
            computeBm25Scores(query, candidates)
        } else {
            emptyMap()
        }

        // 6. 融合分数
        val effectiveThreshold = minScore
        val merged = candidates.mapNotNull { doc ->
            val semanticScore = semanticScores[doc.id] ?: 0f
            val bm25Score = bm25Scores[doc.id] ?: 0f

            // 自适应融合权重
            val adaptiveWeight = if (semanticScore < effectiveThreshold * 0.8f) {
                SEMANTIC_WEIGHT * 0.5f // 语义不可靠时，BM25 补位
            } else {
                SEMANTIC_WEIGHT
            }
            val finalScore = semanticScore * adaptiveWeight + bm25Score * (1f - adaptiveWeight)

            if (finalScore < effectiveThreshold) return@mapNotNull null

            val expanded = if (expandContext) {
                buildExpandedContext(doc, candidates)
            } else ""

            SearchResult(
                documentId = doc.id,
                knowledgeBaseId = doc.knowledgeBaseId,
                filePath = doc.filePath,
                fileName = doc.fileName,
                chunkIndex = doc.chunkIndex,
                content = doc.chunkText,
                score = finalScore,
                semanticScore = semanticScore,
                bm25Score = bm25Score,
                expandedContext = expanded,
                tags = doc.tags,
            )
        }
            .sortedByDescending { it.score }
            .take(limit * 2) // 多取一些用于去重

        // 7. 去重：同一文件只保留最高分 chunk
        val deduped = deduplicateByFile(merged).take(limit * 2)

        // 8. Reranking：用原 query 对展开上下文做关键词匹配加分
        if (enableRerank && deduped.size > 1) {
            val queryTerms = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }.toSet()
            if (queryTerms.isNotEmpty()) {
                return deduped.map { result ->
                    val context = (result.expandedContext + " " + result.content).lowercase()
                    val matchCount = queryTerms.count { term -> context.contains(term) }
                    val bonus = (matchCount.toFloat() / queryTerms.size) * RERANK_MAX_BONUS
                    result.copy(score = result.score + bonus)
                }.sortedByDescending { it.score }.take(limit)
            }
        }

        return deduped.take(limit)
    }

    /**
     * 轻量级查询扩展
     */
    private fun expandQuery(query: String): List<String> {
        val expanded = mutableListOf(query)
        val trimmed = query.trim()

        // 如果 query 包含空格，也尝试不分词的完整匹配
        if (trimmed.contains(" ")) {
            expanded.add(trimmed.replace(" ", ""))
        }

        // 英文 query 也尝试小写
        if (trimmed.any { it.isLetter() && it.isLowerCase() } ||
            trimmed.any { it.isLetter() && it.isUpperCase() }) {
            expanded.add(trimmed.lowercase())
        }

        // 中文长句也尝试按关键词拆开搜索
        if (trimmed.length > 6 && trimmed.any { it in '\u4e00'..'\u9fff' }) {
            // 提取可能的关键词（2-4字中文词组）
            val chars = trimmed.filter { it in '\u4e00'..'\u9fff' }
            if (chars.length >= 4) {
                // 取前4个字作为补充搜索
                expanded.add(chars.take(4))
            }
        }

        return expanded.distinct()
    }

    /**
     * 计算 BM25 分数（延迟构建索引）
     */
    private suspend fun computeBm25Scores(
        query: String,
        candidates: List<KnowledgeDocumentEntity>,
    ): Map<String, Float> {
        val cacheKey = candidates.joinToString("|") { it.id }
        if (bm25Index == null || bm25CacheKey != cacheKey) {
            val texts = candidates.map { it.chunkText }
            bm25Index = Bm25Index(documents = texts).build()
            bm25CacheKey = cacheKey
        }

        val bm25Results = bm25Index!!.search(query)
        if (bm25Results.isEmpty()) return emptyMap()

        // 归一化 BM25 分数到 0~1
        val maxScore = bm25Results.maxOfOrNull { it.second } ?: 1f
        return bm25Results.associate { (idx, score) ->
            val docId = candidates.getOrNull(idx)?.id ?: return@associate idx.toString() to 0f
            docId to (score / maxScore).coerceIn(0f, 1f)
        }
    }

    /**
     * 构建展开上下文
     */
    private fun buildExpandedContext(
        doc: KnowledgeDocumentEntity,
        allCandidates: List<KnowledgeDocumentEntity>,
    ): String {
        val fileChunks = allCandidates
            .filter { it.filePath == doc.filePath }
            .sortedBy { it.chunkIndex }

        val idx = fileChunks.indexOfFirst { it.id == doc.id }
        if (idx < 0) return ""

        val start = maxOf(0, idx - CONTEXT_WINDOW)
        val end = minOf(fileChunks.size, idx + CONTEXT_WINDOW + 1)
        val selected = fileChunks.subList(start, end)
        if (selected.size <= 1) return ""

        return buildString {
            selected.forEachIndexed { i, chunk ->
                val text = chunk.chunkText.take(CONTEXT_CHUNK_MAX).trim()
                if (text.isNotEmpty()) {
                    if (chunk.id == doc.id) {
                        appendLine("▸ $text")
                    } else {
                        appendLine(text)
                    }
                    if (i < selected.size - 1) appendLine("...")
                }
            }
        }
    }

    /**
     * 同一文件去重
     */
    private fun deduplicateByFile(results: List<SearchResult>): List<SearchResult> {
        val seen = mutableSetOf<String>()
        return results.filter { seen.add(it.filePath) }
    }

    // 辅助：获取知识库的模型 ID
}
