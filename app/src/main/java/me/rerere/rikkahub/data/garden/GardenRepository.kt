package me.rerere.rikkahub.data.garden

/**
 * 数字花园统一仓库，对外暴露的高层接口。
 * 组合 IndexService + SearchService，UI 层只依赖这一个类。
 */
class GardenRepository(
    val indexService: GardenIndexService,
    val searchService: GardenSearchService,
) {
    /**
     * 是否已初始化（有数据）
     */
    suspend fun isReady(): Boolean = searchService.isReady()

    /**
     * 获取统计信息
     */
    suspend fun getStats(): GardenIndexService.IndexStats = indexService.getStats()

    /**
     * 获取文件夹列表
     */
    suspend fun getFolders(): List<String> = searchService.getFolders()
}
