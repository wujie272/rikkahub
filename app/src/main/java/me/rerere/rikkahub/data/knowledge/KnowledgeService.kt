package me.rerere.rikkahub.data.knowledge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid

/**
 * 知识库统一服务
 * 数据层对外唯一入口，组合 DAO + Embedding + Chunking
 */
class KnowledgeService(
    private val knowledgeBaseDao: KnowledgeBaseDao,
    private val documentDao: KnowledgeDocumentDao,
    private val embeddingService: EmbeddingService,
    private val searchService: KnowledgeSearchService,
) {

    /** 可观察的知识库列表（Room Flow 自动响应增删改） */
    fun observeAllKnowledgeBases(): Flow<List<KnowledgeBaseEntity>> = knowledgeBaseDao.getAllFlow()
    // ============ 知识库 CRUD ============

    suspend fun getAllKnowledgeBases(): List<KnowledgeBaseEntity> = knowledgeBaseDao.getAll()

    suspend fun getKnowledgeBase(id: String): KnowledgeBaseEntity? = knowledgeBaseDao.getById(id)

    suspend fun createKnowledgeBase(
        name: String,
        description: String,
        modelId: String,
        dimensions: Int = 1536,
        documentCount: Int = 6,
        chunkSize: Int = 1000,
        chunkOverlap: Int = 200,
        chunkStrategy: String = "fixed",
        threshold: Float = 0.35f,
    ): KnowledgeBaseEntity {
        val now = System.currentTimeMillis()
        val entity = KnowledgeBaseEntity(
            id = Uuid.random().toString(),
            name = name.trim(),
            description = description.trim(),
            modelId = modelId,
            dimensions = dimensions,
            documentCount = documentCount,
            chunkSize = chunkSize,
            chunkOverlap = chunkOverlap,
            chunkStrategy = chunkStrategy,
            threshold = threshold,
            createdAt = now,
            updatedAt = now,
        )
        knowledgeBaseDao.insert(entity)
        return entity
    }

    suspend fun updateKnowledgeBase(id: String, name: String, description: String, modelId: String,
                                    dimensions: Int, documentCount: Int, chunkSize: Int,
                                    chunkOverlap: Int, chunkStrategy: String, threshold: Float): Boolean {
        val existing = knowledgeBaseDao.getById(id) ?: return false
        knowledgeBaseDao.update(existing.copy(
            name = name.trim(),
            description = description.trim(),
            modelId = modelId,
            dimensions = dimensions,
            documentCount = documentCount,
            chunkSize = chunkSize,
            chunkOverlap = chunkOverlap,
            chunkStrategy = chunkStrategy,
            threshold = threshold,
            updatedAt = System.currentTimeMillis(),
        ))
        return true
    }

    suspend fun deleteKnowledgeBase(id: String) {
        knowledgeBaseDao.deleteById(id)
        // 级联删除 document（由外键 CASCADE 自动处理）
    }

    // ============ 文档管理 ============

    suspend fun getDocumentsByKnowledgeBase(kbId: String): List<KnowledgeDocumentEntity> =
        documentDao.getByKnowledgeBase(kbId)

    suspend fun getDistinctFiles(kbId: String): List<FilePathAndName> =
        documentDao.getDistinctFiles(kbId)

    suspend fun getChunksByFile(kbId: String, filePath: String): List<KnowledgeDocumentEntity> =
        documentDao.getByFilePath(kbId, filePath)

    suspend fun getChunk(id: String): KnowledgeDocumentEntity? = documentDao.getById(id)

    suspend fun getStats(kbId: String): KnowledgeStats {
        val totalChunks = documentDao.countByKnowledgeBase(kbId)
        val totalFiles = documentDao.countFilesByKnowledgeBase(kbId)
        return KnowledgeStats(totalFiles, totalChunks)
    }

    /**
     * 添加文档：分块 → 嵌入 → 入库
     * 对 markdown 文件自动解析 frontmatter（标题、标签）并注入搜索上下文
     */
    suspend fun addDocument(
        kbId: String,
        content: String,
        filePath: String,
        fileName: String,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): Result<Int> {
        val kb = knowledgeBaseDao.getById(kbId) ?: return Result.failure(Exception("知识库不存在"))

        // 处理 markdown frontmatter
        val (processedContent, displayName, tags) = processMarkdownContent(content, fileName)

        // 1. 分块（语义分块需传入 embedder）
        val chunks = TextChunker.chunk(
            text = processedContent,
            chunkSize = kb.chunkSize,
            chunkOverlap = kb.chunkOverlap,
            strategy = kb.chunkStrategy,
            semanticEmbedder = if (kb.chunkStrategy == "semantic") {
                { text -> runBlocking { embeddingService.embed(text, kb.modelId) } }
            } else null,
        )
        if (chunks.isEmpty()) return Result.success(0)

        // 如果有关联标签，注入到第一个 chunk 增强搜索命中
        val enrichedChunks = if (tags.isNotEmpty()) {
            val tagLine = "[标签: ${tags.joinToString(", ")}]"
            chunks.toMutableList().apply {
                if (isNotEmpty()) {
                    set(0, "$tagLine\n${this[0]}")
                }
            }
        } else chunks

        // 2. 批量生成向量
        val vectors = try {
            embeddingService.embedBatch(enrichedChunks, kb.modelId)
        } catch (e: Exception) {
            return Result.failure(Exception("嵌入向量计算失败: ${e.message}"))
        }

        // 3. 入库
        val entities = enrichedChunks.mapIndexed { index, chunkText ->
            KnowledgeDocumentEntity(
                id = Uuid.random().toString(),
                knowledgeBaseId = kbId,
                filePath = filePath,
                fileName = displayName,
                chunkIndex = index,
                chunkText = chunkText,
                tags = tags.joinToString(","),
                vector = if (index < vectors.size) VectorUtils.vectorToJson(vectors[index]) else null,
                enabled = true,
            )
        }
        documentDao.insertAll(entities)
        onProgress?.invoke(entities.size, entities.size)
        return Result.success(entities.size)
    }

    /**
     * 并发导入多个文档，带进度回调
     */
    suspend fun addDocumentsConcurrent(
        kbId: String,
        files: List<Triple<String, String, String>>, // (content, filePath, fileName)
        onProgress: (completed: Int, total: Int, currentFile: String) -> Unit = { _, _, _ -> },
    ): Result<Int> {
        val kb = knowledgeBaseDao.getById(kbId) ?: return Result.failure(Exception("知识库不存在"))

        var totalChunks = 0
        var completed = 0

        // 每个文件串行处理（避免并发打爆 embedding API rate limit），但批量写入
        for ((content, filePath, fileName) in files) {
            onProgress(completed, files.size, fileName)

            // 处理 markdown frontmatter
            val (processedContent, displayName, tags) = processMarkdownContent(content, fileName)

            // 分块（语义分块需传入 embedder）
            val chunks = TextChunker.chunk(
                text = processedContent,
                chunkSize = kb.chunkSize,
                chunkOverlap = kb.chunkOverlap,
                strategy = kb.chunkStrategy,
                semanticEmbedder = if (kb.chunkStrategy == "semantic") {
                    { text -> runBlocking { embeddingService.embed(text, kb.modelId) } }
                } else null,
            )
            if (chunks.isEmpty()) continue

            // 如果有关联标签，注入到第一个 chunk 增强搜索命中
            val enrichedChunks = if (tags.isNotEmpty()) {
                val tagLine = "[标签: ${tags.joinToString(", ")}]"
                chunks.toMutableList().apply {
                    if (isNotEmpty()) set(0, "$tagLine\n${this[0]}")
                }
            } else chunks

            // 批量向量化
            val vectors = try {
                embeddingService.embedBatch(enrichedChunks, kb.modelId)
            } catch (_: Exception) {
                continue
            }

            // 入库
            val entities = enrichedChunks.mapIndexed { index, chunkText ->
                KnowledgeDocumentEntity(
                    id = Uuid.random().toString(),
                    knowledgeBaseId = kbId,
                    filePath = filePath,
                    fileName = displayName,
                    chunkIndex = index,
                    chunkText = chunkText,
                    tags = tags.joinToString(","),
                    vector = if (index < vectors.size) VectorUtils.vectorToJson(vectors[index]) else null,
                    enabled = true,
                )
            }
            documentDao.insertAll(entities)
            totalChunks += entities.size
            completed++
        }

        onProgress(completed, files.size, "")
        return Result.success(totalChunks)
    }

    /**
     * 更新块内容后重新向量化
     */
    suspend fun updateChunkContent(chunkId: String, newText: String): Boolean {
        val doc = documentDao.getById(chunkId) ?: return false
        // 先更新文本，清空向量
        documentDao.updateContent(chunkId, newText)
        // 重新计算向量
        val kb = knowledgeBaseDao.getById(doc.knowledgeBaseId) ?: return false
        val vector = try {
            embeddingService.embed(newText, kb.modelId)
        } catch (_: Exception) { return false }
        documentDao.updateVector(chunkId, VectorUtils.vectorToJson(vector))
        return true
    }

    suspend fun setChunkEnabled(chunkId: String, enabled: Boolean) =
        documentDao.setEnabled(chunkId, enabled)

    suspend fun deleteChunk(chunkId: String) = documentDao.deleteById(chunkId)

    suspend fun renameFile(kbId: String, filePath: String, newName: String) =
        documentDao.renameFile(kbId, filePath, newName)

    suspend fun deleteFile(kbId: String, filePath: String) =
        documentDao.deleteByFilePath(kbId, filePath)

    // ============ 搜索 ============

    /**
     * 语义搜索知识库
     */
    suspend fun search(
        kbId: String,
        query: String,
        limit: Int = 6,
        minScore: Float? = null,
        tagFilter: String? = null,
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val kb = knowledgeBaseDao.getById(kbId) ?: return emptyList()
        val effectiveMinScore = minScore ?: kb.threshold

        // 使用增强的混合搜索服务
        val enhancedResults = searchService.search(
            kbId = kbId,
            modelId = kb.modelId,
            query = query,
            limit = limit,
            minScore = effectiveMinScore,
            enableHybrid = true,
            expandContext = true,
            enableQueryExpansion = true,
            tagFilter = tagFilter,
        )

        // 映射回原来的 SearchResult 类型
        return enhancedResults.map { r ->
            SearchResult(
                documentId = r.documentId,
                knowledgeBaseId = r.knowledgeBaseId,
                filePath = r.filePath,
                fileName = r.fileName,
                chunkIndex = r.chunkIndex,
                content = r.content,
                score = r.score,
                semanticScore = r.semanticScore,
                bm25Score = r.bm25Score,
                expandedContext = r.expandedContext,
                tags = r.tags,
            )
        }
    }

    suspend fun isModelConfigured(modelId: String): Boolean =
        embeddingService.isConfigured(modelId)

    /** 获取知识库中已存在的文件路径集合（用于去重） */
    suspend fun getExistingFilePaths(kbId: String): Set<String> {
        return documentDao.getDistinctFiles(kbId).map { it.file_path }.toSet()
    }

    /**
     * 处理 markdown 内容：解析 frontmatter、提取标题标签、返回净化后的正文
     * @return (处理后的正文, 显示名称, 标签列表)
     */
    private fun processMarkdownContent(content: String, originalFileName: String): Triple<String, String, List<String>> {
        // 只对可能是 markdown 的内容做 frontmatter 解析
        if (!content.contains("---") && !content.contains("# ")) {
            return Triple(content, originalFileName, emptyList())
        }

        val (frontmatter, body) = MarkdownUtils.parseFrontmatter(content)

        // 清理 Obsidian 双链和 Markdown 链接，减少语义噪声
        val cleanedBody = MarkdownUtils.cleanObsidianLinks(body)

        // 确定显示名称：frontmatter 标题 > 正文第一个 # 标题 > 原文件名
        val title = frontmatter.title.ifBlank {
            MarkdownUtils.extractFirstTitle(cleanedBody) ?: originalFileName
        }

        // 合并标签：frontmatter 标签 + 正文内联 #tag
        val inlineTags = MarkdownUtils.extractInlineTags(cleanedBody)
        val allTags = (frontmatter.tags + inlineTags).distinct()

        return Triple(cleanedBody, title, allTags)
    }
}

data class KnowledgeStats(
    val fileCount: Int,
    val chunkCount: Int,
)

data class SearchResult(
    val documentId: String,
    val knowledgeBaseId: String,
    val filePath: String,
    val fileName: String,
    val chunkIndex: Int,
    val content: String,
    val score: Float,
    val semanticScore: Float = 0f,
    val bm25Score: Float = 0f,
    val expandedContext: String = "",
    val tags: String = "",
)
