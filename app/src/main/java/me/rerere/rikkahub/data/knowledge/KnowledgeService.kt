package me.rerere.rikkahub.data.knowledge

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.knowledge.FileSystemSearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.rikkahub.ui.components.settings.ImportProgressState
import me.rerere.rikkahub.ui.components.settings.ProcessingStage
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

import java.util.concurrent.ConcurrentLinkedQueue

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit


/**
 * 文件解析中间结果，用于批量嵌入
 */
private data class FileParseResult(
    val filePath: String,
    val fileName: String,
    val chunks: List<String>,
    val tags: List<String>,
)

/**
 * 知识库统一服务
 * 数据层对外唯一入口，组合 DAO + Embedding + Chunking
 */
class KnowledgeService(
    private val context: Context,
    private val knowledgeBaseDao: KnowledgeBaseDao,
    private val documentDao: KnowledgeDocumentDao,
    private val embeddingService: EmbeddingService,
    private val searchService: KnowledgeSearchService,
    private val appScope: AppScope,
    private val mountedDirDao: MountedKnowledgeDirDao,
    private val fileSystemSearchEngine: FileSystemSearchEngine,
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
        chunkStrategy: String = "markdown",
        // 默认使用 AST 解析的 Markdown 分块，比 fixed 更智能
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

    // ============ 后台导入（流式 + 进度） ============

    private val _importProgress = MutableStateFlow(ImportProgressState())
    val importProgress: SharedFlow<ImportProgressState> = _importProgress.asSharedFlow()

    /**
     * 流式导入目录：三阶段批量优化版
     *
     * Phase 1 — 并行扫描 + 读取 + 解析 + 分块（每个文件独立，Semaphore 3 并发）
     * Phase 2 — 批量嵌入：所有文件的分块合并为一次 API 调用
     * Phase 3 — 批量入库：一次性写入 Room
     *
     * 相比逐个文件串行嵌入，API 调用次数从 N 次降为 1 次，DB 写入从 N 次降为 1 次。
     * 在后台线程执行，通过 [importProgress] 发射进度。
     */
    fun startImportDirectory(
        kbId: String,
        contentResolver: ContentResolver,
        treeUri: Uri,
    ) {
        _importProgress.value = ImportProgressState(
            active = true,
            currentStage = ProcessingStage.SCANNING,
        )

        appScope.launch(Dispatchers.IO) {
            runImportDirectory(kbId, contentResolver, treeUri)
        }
    }

    private suspend fun runImportDirectory(
        kbId: String,
        contentResolver: ContentResolver,
        treeUri: Uri,
    ) {
        // ============ Phase 0: 扫描目录 + 去重 ============
        _importProgress.value = ImportProgressState(
            active = true,
            currentStage = ProcessingStage.SCANNING,
            currentFileName = "正在扫描目录...",
        )

        val root = DocumentFile.fromTreeUri(context, treeUri) ?: run {
            _importProgress.value = ImportProgressState()
            return
        }
        val uris = mutableListOf<Uri>()
        scanDirRecursive(root, uris)

        if (!currentCoroutineContext().isActive) return
        if (uris.isEmpty()) {
            _importProgress.value = ImportProgressState()
            return
        }

        val existingPaths = getExistingFilePaths(kbId)
        val newUris = uris.filter { it.toString() !in existingPaths }
        val skipped = uris.size - newUris.size

        if (newUris.isEmpty()) {
            _importProgress.value = ImportProgressState()
            return
        }

        _importProgress.value = _importProgress.value.copy(
            totalFiles = newUris.size,
            currentFileName = if (skipped > 0) "已跳过 $skipped 个重复文件" else "",
        )

        val kb = knowledgeBaseDao.getById(kbId) ?: run {
            _importProgress.value = ImportProgressState()
            return
        }

        // ============ Phase 1: 并行读取 + 解析 + 分块 ============
        val semaphore = Semaphore(3)
        val parseResults = ConcurrentLinkedQueue<FileParseResult>()
        var completed = 0

        for (uri in newUris) {
            if (!currentCoroutineContext().isActive) break

            val fileName = getFileNameFromUri(contentResolver, uri) ?: "unknown"
            _importProgress.value = _importProgress.value.copy(
                currentFileName = fileName,
                currentStage = ProcessingStage.READING,
                currentFileProgress = 0f,
            )

            semaphore.withPermit {
                if (!currentCoroutineContext().isActive) return@withPermit

                // 读取文件
                val mimeType = try {
                    contentResolver.getType(uri) ?: "text/plain"
                } catch (_: Exception) { "text/plain" }

                _importProgress.value = _importProgress.value.copy(
                    currentStage = ProcessingStage.PARSING,
                    currentFileProgress = 0.3f,
                )

                val content = readDocumentContent(contentResolver, uri, mimeType)
                if (content.isBlank()) {
                    completed++
                    _importProgress.value = _importProgress.value.copy(completedFiles = completed)
                    return@withPermit
                }

                // 处理 frontmatter
                val (processedContent, displayName, tags) = processMarkdownContent(content, fileName)

                // 分块
                _importProgress.value = _importProgress.value.copy(
                    currentStage = ProcessingStage.CHUNKING,
                    currentFileProgress = 0.6f,
                )

                val chunks = TextChunker.chunk(
                    text = processedContent,
                    chunkSize = kb.chunkSize,
                    chunkOverlap = kb.chunkOverlap,
                    strategy = kb.chunkStrategy,
                )

                if (chunks.isNotEmpty()) {
                    // 标签注入到第一个 chunk 增强搜索命中
                    val enrichedChunks = if (tags.isNotEmpty()) {
                        val tagLine = "[标签: ${tags.joinToString(", ")}]"
                        chunks.toMutableList().apply {
                            set(0, "$tagLine\n${this[0]}")
                        }
                    } else chunks

                    parseResults.add(FileParseResult(
                        filePath = uri.toString(),
                        fileName = displayName,
                        chunks = enrichedChunks,
                        tags = tags,
                    ))
                }

                completed++
                _importProgress.value = _importProgress.value.copy(
                    completedFiles = completed,
                    currentFileProgress = 0.8f,
                )
            }
        }

        if (!currentCoroutineContext().isActive) return
        if (parseResults.isEmpty()) {
            _importProgress.value = ImportProgressState()
            return
        }

        // ============ Phase 2: 批量嵌入（一次 API 调用） ============
        val allChunks = parseResults.flatMap { it.chunks }
        _importProgress.value = _importProgress.value.copy(
            currentStage = ProcessingStage.EMBEDDING,
            currentFileName = "正在批量生成向量 (${allChunks.size} 个分块)...",
            currentFileProgress = 0f,
        )

        val allVectors = try {
            embeddingService.embedBatch(allChunks, kb.modelId, kb.dimensions)
        } catch (e: Exception) {
            _importProgress.value = ImportProgressState()
            return
        }

        if (!currentCoroutineContext().isActive) return

        // ============ Phase 3: 批量入库 ============
        _importProgress.value = _importProgress.value.copy(
            currentStage = ProcessingStage.SAVING,
            currentFileName = "正在保存到数据库...",
            currentFileProgress = 0f,
        )

        var vectorIdx = 0
        val entities = mutableListOf<KnowledgeDocumentEntity>()
        for (result in parseResults) {
            for ((chunkIndex, chunkText) in result.chunks.withIndex()) {
                val vector = if (vectorIdx < allVectors.size) {
                    VectorUtils.vectorToJson(allVectors[vectorIdx])
                } else null
                vectorIdx++

                entities.add(KnowledgeDocumentEntity(
                    id = Uuid.random().toString(),
                    knowledgeBaseId = kbId,
                    filePath = result.filePath,
                    fileName = result.fileName,
                    chunkIndex = chunkIndex,
                    chunkText = chunkText,
                    tags = result.tags.joinToString(","),
                    vector = vector,
                    enabled = true,
                ))
            }
        }
        documentDao.insertAll(entities)

        _importProgress.value = ImportProgressState()
    }

    fun cancelImport() {
        _importProgress.value = ImportProgressState()
    }

    /** 递归扫描目录，跳过隐藏文件 */
    private fun scanDirRecursive(
        dir: DocumentFile,
        result: MutableList<Uri>,
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            when {
                child.isDirectory -> scanDirRecursive(child, result)
                child.isFile && isSupportedExtension(name) -> result.add(child.uri)
            }
        }
    }

    private fun isSupportedExtension(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in listOf("txt", "md", "markdown", "pdf", "docx", "pptx", "epub", "csv", "json", "xml", "yaml", "yml")
    }

    private fun getFileNameFromUri(cr: ContentResolver, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            cr.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = it.getString(idx)
                }
            }
        }
        if (name == null) name = uri.lastPathSegment
        if (name != null && !name.contains(".")) name = "$name.md"
        return name
    }

    private fun readDocumentContent(
        cr: ContentResolver,
        uri: Uri,
        mimeType: String,
    ): String {
        // 直接读取，不经过 runBlocking
        val cacheDir = File(context.cacheDir, "kb_import").apply { mkdirs() }
        val ext = when {
            mimeType.contains("pdf") -> ".pdf"
            mimeType.contains("word") || mimeType.contains("document") -> ".docx"
            mimeType.contains("presentation") -> ".pptx"
            mimeType.contains("epub") -> ".epub"
            mimeType.contains("markdown") || mimeType.contains("md") -> ".md"
            else -> ".txt"
        }
        val tmp = File.createTempFile("import_", ext, cacheDir)
        try {
            cr.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            return when {
                mimeType == "application/pdf" || mimeType.contains("pdf") ->
                    PdfParser.parserPdf(tmp)
                mimeType.contains("word") || tmp.name.endsWith(".docx") ->
                    DocxParser.parse(tmp)
                mimeType.contains("presentation") || tmp.name.endsWith(".pptx") ->
                    PptxParser.parse(tmp)
                mimeType == "application/epub+zip" || tmp.name.endsWith(".epub") ->
                    EpubParser.parse(tmp)
                else -> tmp.readText()
            }
        } catch (_: Exception) {
            return tmp.readText()
        } finally {
            tmp.delete()
        }
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

        // 1. 分块
        val chunks = TextChunker.chunk(
            text = processedContent,
            chunkSize = kb.chunkSize,
            chunkOverlap = kb.chunkOverlap,
            strategy = kb.chunkStrategy,
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
            embeddingService.embedBatch(enrichedChunks, kb.modelId, kb.dimensions)
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

            // 分块
            val chunks = TextChunker.chunk(
                text = processedContent,
                chunkSize = kb.chunkSize,
                chunkOverlap = kb.chunkOverlap,
                strategy = kb.chunkStrategy,
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
                embeddingService.embedBatch(enrichedChunks, kb.modelId, kb.dimensions)
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
            embeddingService.embed(newText, kb.modelId, kb.dimensions)
        } catch (_: Exception) { return false }
        documentDao.updateVector(chunkId, VectorUtils.vectorToJson(vector))
        return true
    }

    suspend fun setChunkEnabled(chunkId: String, enabled: Boolean) =
        documentDao.setEnabled(chunkId, enabled)

    suspend fun deleteChunk(chunkId: String) = documentDao.softDeleteById(chunkId)

    suspend fun renameFile(kbId: String, filePath: String, newName: String) =
        documentDao.renameFile(kbId, filePath, newName)

    suspend fun deleteFile(kbId: String, filePath: String) =
        documentDao.softDeleteByFilePath(kbId, filePath)

    // ============ 回收站 ============

    /** 获取已删除文件的列表 */
    suspend fun getDeletedFiles(kbId: String): List<FilePathAndName> =
        documentDao.getDeletedFiles(kbId)

    /** 获取已删除文件的分块详情 */
    suspend fun getDeletedChunks(kbId: String, filePath: String): List<KnowledgeDocumentEntity> =
        documentDao.getDeleted(kbId).filter { it.filePath == filePath }

    /** 恢复文件 */
    suspend fun restoreFile(kbId: String, filePath: String) =
        documentDao.restoreByFilePath(kbId, filePath)

    /** 恢复单个分块 */
    suspend fun restoreChunk(chunkId: String) =
        documentDao.restoreById(chunkId)

    /** 永久删除文件 */
    suspend fun permanentlyDeleteFile(kbId: String, filePath: String) =
        documentDao.permanentlyDeleteByFilePath(kbId, filePath)

    /** 永久删除单个分块 */
    suspend fun permanentlyDeleteChunk(chunkId: String) =
        documentDao.permanentlyDeleteById(chunkId)

    /** 清空回收站 */
    suspend fun emptyTrash(kbId: String) =
        documentDao.permanentlyDeleteAllDeleted(kbId)

    /** 获取回收站条目数 */
    suspend fun getTrashCount(kbId: String): Int =
        documentDao.countDeleted(kbId)

    // ============ 搜索 ============

    // ============ 重建索引 ============

    /**
     * 重建索引：重新计算所有启用分块的嵌入向量。
     *
     * 场景：用户切换了 embedding 模型后，需要让所有已有分块用新模型重新生成向量。
     * 注意：如果只是改了分块参数（chunkSize/chunkOverlap/chunkStrategy），
     * 需要重新导入文档，因为原始全文未持久化。
     *
     * @param kbId 知识库 ID
     * @param onProgress 进度回调 (current, total)
     * @return Result 包含成功重新向量化的分块数量
     */
    suspend fun rebuildIndex(
        kbId: String,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<Int> {
        val kb = knowledgeBaseDao.getById(kbId) ?: return Result.failure(Exception("知识库不存在"))
        val allDocuments = documentDao.getByKnowledgeBase(kbId)
        if (allDocuments.isEmpty()) return Result.success(0)

        // 只重新嵌入启用的分块
        val chunksToEmbed = allDocuments.filter { it.enabled }
        if (chunksToEmbed.isEmpty()) return Result.success(0)

        val texts = chunksToEmbed.map { it.chunkText }

        // 批量重新嵌入
        val vectors = try {
            embeddingService.embedBatch(texts, kb.modelId, kb.dimensions)
        } catch (e: Exception) {
            return Result.failure(Exception("嵌入向量计算失败: ${e.message}"))
        }

        // 逐条更新向量
        var updated = 0
        for ((index, doc) in chunksToEmbed.withIndex()) {
            if (index < vectors.size) {
                documentDao.updateVector(doc.id, VectorUtils.vectorToJson(vectors[index]))
                updated++
                onProgress(updated, chunksToEmbed.size)
            }
        }

        return Result.success(updated)
    }

    // ============ 挂载目录管理 ============

    /**
     * 挂载一个外部目录到知识库
     * 只存元数据（URI + 路径），不存文件内容
     */
    suspend fun mountDirectory(
        kbId: String,
        treeUri: Uri,
        name: String,
    ): Result<MountedKnowledgeDir> = withContext(Dispatchers.IO) {
        val posixPath = fileSystemSearchEngine.resolveTreeUriToPosixPath(treeUri)
        if (posixPath == null) {
            return@withContext Result.failure(Exception("无法解析目录路径，仅支持设备本地存储"))
        }
        // 检查是否已挂载过
        val existing = mountedDirDao.getByKbId(kbId)
        if (existing.any { it.treeUri == treeUri.toString() }) {
            return@withContext Result.failure(Exception("该目录已挂载"))
        }

        // 扫描目录统计信息
        val stats = fileSystemSearchEngine.scanDirectoryStats(posixPath)

        val dir = MountedKnowledgeDir(
            id = Uuid.random().toString(),
            kbId = kbId,
            name = name.trim(),
            treeUri = treeUri.toString(),
            posixPath = posixPath,
            fileCount = stats.fileCount,
            totalSizeBytes = stats.totalSizeBytes,
            lastSyncAt = System.currentTimeMillis(),
        )
        mountedDirDao.insert(dir)
        return@withContext Result.success(dir)
    }

    /**
     * 卸载挂载目录（只删元数据）
     */
    suspend fun unmountDirectory(mountId: String) = withContext(Dispatchers.IO) {
        mountedDirDao.deleteById(mountId)
    }

    /**
     * 获取知识库的所有挂载目录
     */
    suspend fun getMountedDirectories(kbId: String): List<MountedKnowledgeDir> {
        return withContext(Dispatchers.IO) {
            mountedDirDao.getByKbId(kbId)
        }
    }

    /**
     * 获取挂载目录下的所有文件列表
     */
    suspend fun getMountedDirectoryFiles(kbId: String): List<MountedFileItem> = withContext(Dispatchers.IO) {
        val mounts = mountedDirDao.getByKbId(kbId)
        val results = mutableListOf<MountedFileItem>()
        for (mount in mounts) {
            val posixPath = mount.posixPath ?: continue
            val files = fileSystemSearchEngine.listFiles(posixPath)
            results.addAll(files.map { file ->
                MountedFileItem(
                    mountId = mount.id,
                    mountName = mount.name,
                    filePath = file.filePath,
                    fileName = file.fileName,
                    fileSize = file.fileSize,
                )
            })
        }
        return@withContext results
    }

    /**
     * 观察知识库的所有挂载目录（Flow）
     */
    fun observeMountedDirectories(kbId: String): Flow<List<MountedKnowledgeDir>> {
        return mountedDirDao.observeByKbId(kbId)
    }

    /**
     * 刷新挂载目录的统计信息
     */
    suspend fun refreshMountStats(mountId: String): Result<MountedKnowledgeDir> = withContext(Dispatchers.IO) {
        val mount = mountedDirDao.getById(mountId) ?: return@withContext Result.failure(Exception("挂载目录不存在"))
        val posixPath = mount.posixPath ?: return@withContext Result.failure(Exception("路径未解析"))
        val stats = fileSystemSearchEngine.scanDirectoryStats(posixPath)
        mountedDirDao.updateSyncStats(mountId, stats.fileCount, stats.totalSizeBytes, System.currentTimeMillis())
        val updated = mountedDirDao.getById(mountId)!!
        return@withContext Result.success(updated)
    }

    /**
     * 搜索挂载目录（直接在文件系统上搜，零存储）
     */
    suspend fun searchMountedDirectories(
        kbId: String,
        query: String,
        limit: Int = 10,
    ): List<MountedSearchResult> = withContext(Dispatchers.IO) {
        val mounts = mountedDirDao.getByKbId(kbId)
        val results = mutableListOf<MountedSearchResult>()

        for (mount in mounts) {
            val posixPath = mount.posixPath ?: continue
            val hits = fileSystemSearchEngine.search(
                dirPath = posixPath,
                query = query,
                config = FileSystemSearchEngine.SearchConfig(maxResults = limit),
            )
            results.addAll(hits.map { hit ->
                MountedSearchResult(
                    mountId = mount.id,
                    mountName = mount.name,
                    filePath = hit.filePath,
                    fileName = hit.fileName,
                    fileSize = hit.fileSize,
                    lineNumber = hit.lineNumber,
                    lineContent = hit.lineContent,
                    contextBefore = hit.contextBefore,
                    contextAfter = hit.contextAfter,
                )
            })
        }

        return@withContext results
    }

    /**
     * 统一搜索：同时搜内部知识库 + 挂载目录
     */
    suspend fun unifiedSearch(
        kbId: String,
        query: String,
        limit: Int = 6,
    ): List<UnifiedSearchResult> {
        val results = mutableListOf<UnifiedSearchResult>()

        // 1. 搜内部 KB（现有语义搜索）
        val kbHits = search(kbId, query, limit)
        results.addAll(kbHits.map { UnifiedSearchResult.Knowledge(it) })

        // 2. 搜挂载目录（直接搜文件系统）
        val mountHits = searchMountedDirectories(kbId, query, limit)
        results.addAll(mountHits.map { UnifiedSearchResult.Mounted(it) })

        // 3. 按分数排序
        return results.sortedByDescending { it.score }.take(limit)
    }

    // ============ 挂载目录向量化 ============

    /** 挂载目录向量化进度 */
    private val _vectorizeProgress = MutableStateFlow<VectorizeProgress>(VectorizeProgress())
    val vectorizeProgress: StateFlow<VectorizeProgress> = _vectorizeProgress.asStateFlow()

    /**
     * 一键向量化：将挂载目录的全部文本文件导入到知识库
     * 复用 addDocument 的分块 + 向量化 + 入库逻辑
     */
    suspend fun vectorizeMountDirectory(
        mountId: String,
        kbId: String,
    ) = withContext(Dispatchers.IO) {
        val mount = mountedDirDao.getById(mountId) ?: run {
            _vectorizeProgress.value = VectorizeProgress(error = "挂载目录不存在")
            return@withContext
        }
        val posixPath = mount.posixPath ?: run {
            _vectorizeProgress.value = VectorizeProgress(error = "路径未解析")
            return@withContext
        }

        val root = File(posixPath)
        if (!root.isDirectory) {
            _vectorizeProgress.value = VectorizeProgress(error = "目录不可访问")
            return@withContext
        }

        val kb = knowledgeBaseDao.getById(kbId) ?: run {
            _vectorizeProgress.value = VectorizeProgress(error = "知识库不存在")
            return@withContext
        }

        // Phase 1: 扫描目录，收集文本文件
        _vectorizeProgress.value = VectorizeProgress(
            active = true,
            stage = "扫描目录",
            currentFile = "正在扫描...",
        )

        val textFiles = mutableListOf<File>()
        try {
            Files.walk(root.toPath()).use { stream ->
                stream.iterator().forEach { path ->
                    val file = path.toFile()
                    if (file.isFile && file.length() > 0 && file.length() <= MAX_VECTORIZE_FILE_SIZE) {
                        if (FileSystemSearchEngine.isTextFile(file.name)) {
                            textFiles.add(file)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            _vectorizeProgress.value = VectorizeProgress(error = "扫描失败: ${e.message}")
            return@withContext
        }

        if (textFiles.isEmpty()) {
            _vectorizeProgress.value = VectorizeProgress(error = "未找到可向量化的文本文件")
            return@withContext
        }

        // 去重：只处理未导入过的文件
        val existingPaths = getExistingFilePaths(kbId)
        val newFiles = textFiles.filter { it.absolutePath !in existingPaths }
        val totalFiles = newFiles.size
        var completed = 0

        _vectorizeProgress.value = VectorizeProgress(
            active = true,
            stage = "处理文件",
            totalFiles = totalFiles,
            currentFile = "准备就绪，共 $totalFiles 个文件",
        )

        // Phase 2: 逐文件读取 → 分块 → 向量化 → 入库
        val semaphore = Semaphore(2) // 控制并发
        for (file in newFiles) {
            if (!currentCoroutineContext().isActive) break

            _vectorizeProgress.value = _vectorizeProgress.value.copy(
                currentFile = file.name,
                completedFiles = completed,
                stage = "读取中",
            )

            semaphore.withPermit {
                if (!currentCoroutineContext().isActive) return@withPermit

                try {
                    val content = withContext(Dispatchers.IO) {
                        file.readText(Charsets.UTF_8)
                    }
                    if (content.isBlank()) return@withPermit

                    // 复用 addDocument 的分块 → 向量化 → 入库流程
                    val result = addDocument(
                        kbId = kbId,
                        content = content,
                        filePath = file.absolutePath,
                        fileName = file.name,
                    )
                    result.onSuccess { chunks ->
                        completed++
                        _vectorizeProgress.value = _vectorizeProgress.value.copy(
                            completedFiles = completed,
                            stage = "已完成",
                            currentFile = "${file.name} (${chunks} 分块)",
                        )
                    }.onFailure { e ->
                        // 单个文件失败不中断整体
                        completed++
                    }
                } catch (e: Exception) {
                    completed++
                }
            }
        }

        _vectorizeProgress.value = VectorizeProgress(
            active = false,
            completed = true,
            totalFiles = totalFiles,
            completedFiles = completed,
            stage = "向量化完成",
        )

        // 清理挂载目录的统计
        val stats = fileSystemSearchEngine.scanDirectoryStats(posixPath)
        mountedDirDao.updateSyncStats(mountId, stats.fileCount, stats.totalSizeBytes, System.currentTimeMillis())
    }

    fun resetVectorizeProgress() {
        _vectorizeProgress.value = VectorizeProgress()
    }

    data class VectorizeProgress(
        val active: Boolean = false,
        val completed: Boolean = false,
        val totalFiles: Int = 0,
        val completedFiles: Int = 0,
        val stage: String = "",
        val currentFile: String = "",
        val error: String? = null,
    ) {
        val progress: Float
            get() = if (totalFiles > 0) completedFiles.toFloat() / totalFiles.toFloat() else 0f
    }

    companion object {
        private const val MAX_VECTORIZE_FILE_SIZE = 50 * 1024 * 1024L // 50MB

        private val webClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        /** 简单 HTML 标签去除，提取纯文本 */
        private fun stripHtml(html: String): String {
            return html
                .replace(Regex("(?s)<script[^>]*>.*?</script>"), "")  // 移除 script
                .replace(Regex("(?s)<style[^>]*>.*?</style>"), "")   // 移除 style
                .replace(Regex("(?s)<!--.*?-->"), "")                 // 移除注释
                .replace(Regex("<[^>]+>"), "")                       // 移除 HTML 标签
                .replace(Regex("&nbsp;"), " ")
                .replace(Regex("&[a-zA-Z]+;"), "")                   // 移除 HTML 实体
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    /**
     * 从 URL 导入网页内容到知识库。
     * 使用 OkHttp 获取网页，提取纯文本后导入。
     */
    suspend fun importFromUrl(
        kbId: String,
        url: String,
    ): Result<Int> {
        val kb = knowledgeBaseDao.getById(kbId) ?: return Result.failure(Exception("知识库不存在"))

        // 0. 检查是否已导入过该 URL
        val existingPaths = getExistingFilePaths(kbId)
        if (url in existingPaths) {
            return Result.failure(Exception("该网址已导入过"))
        }

        // 1. 获取网页内容
        val html: String
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) RikkaHub/1.0")
                .build()
            val response = kotlinx.coroutines.withContext(Dispatchers.IO) {
                webClient.newCall(request).execute()
            }
            if (!response.isSuccessful) {
                return Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
            html = response.body?.string() ?: return Result.failure(Exception("响应内容为空"))
        } catch (e: Exception) {
            return Result.failure(Exception("网页请求失败: ${e.message}"))
        }

        // 2. 提取纯文本
        val text = stripHtml(html)
        if (text.length < 20) {
            return Result.failure(Exception("网页内容太少（${text.length} 字符），可能无法正常解析"))
        }

        // 3. 从 URL 提取文件名
        val fileName = url.removePrefix("https://").removePrefix("http://")
            .substringBefore("?").substringBefore("#")
            .trimEnd('/')
            .let { it.substringAfterLast('/') }
            .ifBlank { url.hashCode().toString() } + ".md"

        // 4. 导入文档
        return addDocument(
            kbId = kbId,
            content = text,
            filePath = url,
            fileName = "[网页] $fileName",
        )
    }

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

/**
 * 挂载目录搜索结果
 */

/**
 * 挂载目录下的文件
 */
data class MountedFileItem(
    val mountId: String,
    val mountName: String,
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
)

/**
 * 挂载目录搜索结果
 */
data class MountedSearchResult(
    val mountId: String,
    val mountName: String,
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val lineNumber: Int,
    val lineContent: String,
    val contextBefore: List<String> = emptyList(),
    val contextAfter: List<String> = emptyList(),
)

/**
 * 统一搜索结果（内部 KB + 挂载目录）
 */
sealed class UnifiedSearchResult(
    open val score: Float,
) {
    data class Knowledge(val result: SearchResult) : UnifiedSearchResult(
        score = result.score
    )
    data class Mounted(val result: MountedSearchResult) : UnifiedSearchResult(
        score = 1.0f // 精确匹配，满分
    )
}

