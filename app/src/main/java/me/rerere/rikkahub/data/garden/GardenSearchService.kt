package me.rerere.rikkahub.data.garden

import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.ai.rag.VectorEngine

/**
 * 数字花园语义搜索服务。
 * 复用现有 EmbeddingService + VectorEngine。
 */
class GardenSearchService(
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
    )

    /**
     * 语义搜索笔记库。
     * @param query 搜索关键词
     * @param limit 最多返回条数
     * @param minScore 最低相似度阈值 (0~1)
     * @param folderFilter 可选文件夹过滤
     */
    suspend fun search(
        query: String,
        limit: Int = 5,
        minScore: Float = 0.35f,
        folderFilter: String? = null,
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        // 1. 生成 query 向量
        val queryVec = embeddingService.embed(query)
        if (queryVec.isEmpty()) return emptyList()

        // 2. 获取候选数据
        val candidates = if (folderFilter != null) {
            documentDAO.getByFolderWithEmbedding(folderFilter)
        } else {
            documentDAO.getAllWithEmbedding()
        }

        if (candidates.isEmpty()) return emptyList()

        // 3. 转为 VectorEngine 格式
        val memCandidates = candidates.map { entity ->
            VectorEngine.MemCandidate(
                id = entity.id,
                content = entity.chunkText,
                embedding = entity.embedding?.let { VectorEngine.jsonToFloats(it) },
            )
        }

        // 4. 搜索 top-K
        val results = VectorEngine.searchTopK(queryVec, memCandidates, limit, minScore)

        // 5. 映射回完整结果
        return results.map { r ->
            val entity = candidates.firstOrNull { it.id == r.id }
            SearchResult(
                id = r.id,
                filePath = entity?.filePath ?: "",
                chunkIndex = entity?.chunkIndex ?: 0,
                chunkText = r.content,
                sourceFolder = entity?.sourceFolder ?: "",
                score = r.score,
            )
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
