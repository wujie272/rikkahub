package me.rerere.rikkahub.data.rag

/**
 * RAG 知识库统一仓库，对外暴露的高层接口。
 * 组合 IndexService + SearchService，UI 层只依赖这一个类。
 */
class RagRepository(
    val indexService: RagIndexService,
    val searchService: RagSearchService,
) {
    /**
     * embedding 模型是否已配置
     */
    suspend fun isEmbeddingConfigured(): Boolean = indexService.isEmbeddingConfigured()

    /**
     * 是否已初始化（有数据）
     */
    suspend fun isReady(): Boolean = searchService.isReady()

    /**
     * 获取统计信息
     */
    suspend fun getStats(): RagIndexService.IndexStats = indexService.getStats()

    /**
     * 获取文件夹列表
     */
    suspend fun getFolders(): List<String> = searchService.getFolders()
}
