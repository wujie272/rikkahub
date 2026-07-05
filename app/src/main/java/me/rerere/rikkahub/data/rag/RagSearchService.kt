package me.rerere.rikkahub.data.rag

import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.ai.rag.VectorEngine
import kotlin.math.min

/**
 * RAG 知识库语义搜索服务。
 * 复用现有 EmbeddingService + VectorEngine。
 *
 * 改进：
 * 1. 混合搜索（BM25 + 语义向量），互补召回
 * 2. 段落上下文合并（命中 chunk 带前后相邻 chunk）
 * 3. 搜索结果去重（同一文件多个 chunk 合并）
 */
class RagSearchService(
    private val documentDAO: DocumentDAO,
    private val embeddingService: EmbeddingService,
) {
    /**
     * 搜索结果
     */
    data class SearchResult(
        val id: Int,
        val filePath: String,
        val chunkIndex: Int,
        val chunkText: String,
        val sourceFolder: String,
        val score: Float,
        /** 合并后的上下文文本（包含前后相邻 chunk） */
        val expandedContext: String = "",
        /** 语义相似度分数 */
        val semanticScore: Float = 0f,
        /** BM25 关键词分数 */
        val bm25Score: Float = 0f,
    )

    companion object {
        /** 语义分数的权重（0~1），BM25 权重 = 1 - SEMANTIC_WEIGHT */
        private const val SEMANTIC_WEIGHT = 0.7f
        /** 上下文展开：命中 chunk 前后各取 N 个 chunk */
        private const val CONTEXT_WINDOW = 1
        /** 展开上下文单 chunk 截断长度 */
        private const val CONTEXT_CHUNK_MAX = 600
    }

    /** 懒加载 BM25 索引（只在需要时构建） */
    private var bm25Index: Bm25Index? = null
    private var bm25Docs: List<String> = emptyList()

    /**
     * 语义搜索笔记库。
     *
     * @param query 搜索关键词
     * @param limit 最多返回条数
     * @param minScore 最低相似度阈值 (0~1)
     * @param folderFilter 可选文件夹过滤
     * @param enableHybrid 是否启用混合搜索（BM25 + 语义）
     * @param expandContext 是否展开上下文（合并相邻 chunk）
     */
    suspend fun search(
        query: String,
        limit: Int = 5,
        minScore: Float = 0.35f,
        folderFilter: String? = null,
        enableHybrid: Boolean = true,
        expandContext: Boolean = true,
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        // 1. 获取候选数据
        val candidates = if (folderFilter != null) {
            documentDAO.getByFolderWithEmbedding(folderFilter)
        } else {
            documentDAO.getAllWithEmbedding()
        }

        if (candidates.isEmpty()) return emptyList()

        // 2. 生成 query 向量（语义搜索）
        val queryVec = embeddingService.embed(query)
        if (queryVec.isEmpty()) return emptyList()

        // 3. 转为 VectorEngine 格式
        val memCandidates = candidates.map { entity ->
            VectorEngine.MemCandidate(
                id = entity.id,
                content = entity.chunkText,
                embedding = entity.embedding?.let { VectorEngine.jsonToFloats(it) },
            )
        }

        // 4. 语义搜索
        val semanticResults = VectorEngine.searchTopK(queryVec, memCandidates, candidates.size, minScore)
            .associateBy { it.id }

        // 5. BM25 混合搜索
        val bm25Scores = if (enableHybrid) {
            computeBm25Scores(query, candidates)
        } else {
            emptyMap()
        }

        // 6. 融合分数
        val merged = candidates.map { entity ->
            val semantic = semanticResults[entity.id]
            val semanticScore = semantic?.score ?: 0f
            val bm25Score = bm25Scores[entity.id] ?: 0f

            // 融合：语义×权重 + BM25×(1-权重)
            // 当语义分低于阈值时，BM25 权重自动提升（关键词精确匹配补位）
            val adaptiveWeight = if (semanticScore < minScore * 0.8f) {
                SEMANTIC_WEIGHT * 0.5f // 语义不可靠时，降低其权重
            } else {
                SEMANTIC_WEIGHT
            }
            val finalScore = semanticScore * adaptiveWeight + bm25Score * (1f - adaptiveWeight)

            val expanded = if (expandContext && entity.id > 0) {
                buildExpandedContext(entity, candidates)
            } else {
                ""
            }

            entity to SearchResult(
                id = entity.id,
                filePath = entity.filePath,
                chunkIndex = entity.chunkIndex,
                chunkText = entity.chunkText,
                sourceFolder = entity.sourceFolder,
                score = finalScore,
                expandedContext = expanded,
                semanticScore = semanticScore,
                bm25Score = bm25Score,
            )
        }
            .filter { it.second.score > 0f }
            .sortedByDescending { it.second.score }
            .take(limit)
            .map { it.second }

        // 7. 去重：同一文件只保留最高分 chunk
        return deduplicateByFile(merged)
    }

    /**
     * 计算 BM25 分数。
     * 延迟构建索引，只在首次搜索时初始化。
     */
    private suspend fun computeBm25Scores(
        query: String,
        candidates: List<DocumentEntity>,
    ): Map<Int, Float> {
        // 如果候选变了，重新构建索引
        val docsKey = candidates.joinToString("|") { it.chunkText.take(50) }
        if (bm25Index == null || bm25Docs.hashCode() != docsKey.hashCode()) {
            val texts = candidates.map { it.chunkText }
            bm25Index = Bm25Index(documents = texts).build()
            bm25Docs = texts
        }

        val bm25Results = bm25Index!!.search(query)
        if (bm25Results.isEmpty()) return emptyMap()

        // 归一化 BM25 分数到 0~1
        val maxScore = bm25Results.maxOfOrNull { it.second } ?: 1f
        return bm25Results.associate { (idx, score) ->
            val entityId = candidates.getOrNull(idx)?.id ?: return@associate idx to 0f
            entityId to (score / maxScore).coerceIn(0f, 1f)
        }
    }

    /**
     * 构建展开的上下文：命中 chunk 前后各取 CONTEXT_WINDOW 个相邻 chunk。
     * 跨文件不合并。
     */
    private fun buildExpandedContext(
        entity: DocumentEntity,
        allCandidates: List<DocumentEntity>,
    ): String {
        // 取同一文件的所有 chunk，按 chunkIndex 排序
        val fileChunks = allCandidates
            .filter { it.filePath == entity.filePath }
            .sortedBy { it.chunkIndex }

        val idx = fileChunks.indexOfFirst { it.id == entity.id }
        if (idx < 0) return ""

        val start = maxOf(0, idx - CONTEXT_WINDOW)
        val end = minOf(fileChunks.size, idx + CONTEXT_WINDOW + 1)
        val selected = fileChunks.subList(start, end)

        if (selected.size <= 1) return ""

        return buildString {
            selected.forEachIndexed { i, chunk ->
                val isHit = chunk.id == entity.id
                val text = chunk.chunkText.take(CONTEXT_CHUNK_MAX).trim()
                if (text.isNotEmpty()) {
                    if (isHit) {
                        appendLine("▸ $text")
                    } else {
                        appendLine(text)
                    }
                    if (i < selected.size - 1) {
                        appendLine("...")
                    }
                }
            }
        }
    }

    /**
     * 同一文件多个 chunk 命中时，只保留最高分的一个。
     */
    private fun deduplicateByFile(results: List<SearchResult>): List<SearchResult> {
        val seen = mutableSetOf<String>()
        return results.filter { result ->
            seen.add(result.filePath)
        }
    }

    /**
     * 获取索引状态（有没有数据可用）
     */
    suspend fun isReady(): Boolean {
        return documentDAO.count() > 0
    }

    /**
     * 获取已索引的文件夹列表
     */
    suspend fun getFolders(): List<String> {
        return documentDAO.getDistinctFolders()
    }
}
