package me.rerere.rikkahub.data.grove

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.ai.rag.VectorEngine
import java.io.File
import java.security.MessageDigest

/**
 * Grove索引服务。
 * 遍历笔记库、切 chunk、算 embedding、增量更新。
 */
class GroveIndexService(
    private val documentDAO: DocumentDAO,
    private val embeddingService: EmbeddingService,
) {
    companion object {
        private const val TAG = "GroveIndex"
        private const val CHUNK_MAX_CHARS = 1500 // 单 chunk 最大字符数
        private const val CHUNK_OVERLAP_CHARS = 100 // 相邻 chunk 重叠字符数
    }

    /**
     * 索引状态
     */
    data class IndexProgress(
        val totalFiles: Int = 0,
        val processedFiles: Int = 0,
        val newChunks: Int = 0,
        val updatedChunks: Int = 0,
        val deletedChunks: Int = 0,
        val skippedFiles: Int = 0,
        val errors: Int = 0,
        val isRunning: Boolean = false,
    )

    /**
     * 全量/增量索引指定目录下的所有 .md 文件。
     * @param vaultPath 笔记库根目录
     * @param progressCallback 进度回调，用于 UI 展示
     */
    suspend fun index(
        vaultPath: String,
        ignoreFolders: String = "",
        progressCallback: ((IndexProgress) -> Unit)? = null,
    ) {
        withContext(Dispatchers.IO) {
            val vaultDir = File(vaultPath)
            if (!vaultDir.exists() || !vaultDir.isDirectory) {
                Log.w(TAG, "Vault path does not exist: $vaultPath")
                return@withContext
            }

            // 收集所有 .md 文件
            val ignoreSet = ignoreFolders.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            val mdFiles = vaultDir.walkTopDown()
                .filter { file ->
                    // 跳过忽略目录中的文件
                    if (ignoreSet.isNotEmpty()) {
                        val parentName = file.parentFile?.name ?: ""
                        !ignoreSet.contains(parentName)
                    } else true
                }
                .filter { it.isFile && it.extension.lowercase() == "md" }
                .toList()

            val totalFiles = mdFiles.size
            if (totalFiles == 0) {
                Log.i(TAG, "No .md files found in $vaultPath")
                return@withContext
            }

            // 获取已索引的文件路径集合（轻量查询，只拉路径，不拉大字段）
            val indexedPaths = documentDAO.getAllFilePaths()
                .toSet()

            var progress = IndexProgress(totalFiles = totalFiles, isRunning = true)
            progressCallback?.invoke(progress)

            val modelVersion = embeddingService.currentModelId?.toString() ?: ""
            val batchSize = 5
            val changedFiles = mutableListOf<File>()

            // 第一步：找出变更文件
            mdFiles.forEachIndexed { index, file ->
                val relPath = file.absolutePath
                val existingHash = documentDAO.getFileHash(relPath)
                val currentHash = file.sha256()

                if (existingHash == null) {
                    changedFiles.add(file)
                } else if (existingHash != currentHash) {
                    documentDAO.deleteByFilePath(relPath)
                    changedFiles.add(file)
                } else {
                    progress = progress.copy(
                        processedFiles = index + 1,
                        skippedFiles = progress.skippedFiles + 1,
                    )
                    progressCallback?.invoke(progress)
                }
            }

            if (changedFiles.isEmpty() && indexedPaths.isNotEmpty()) {
                // 只有已删除文件需要清理
                val currentPaths = mdFiles.map { it.absolutePath }.toSet()
                val stalePaths = indexedPaths.filter { it !in currentPaths }
                if (stalePaths.isNotEmpty()) {
                    documentDAO.deleteByFilePaths(stalePaths)
                    progress = progress.copy(deletedChunks = stalePaths.size)
                }
            }

            // 第二步：边切分边 embedding 边入库（流式，避免 OOM）
            var processedChunks = 0
            val pendingBatch = mutableListOf<DocumentEntity>()

            for (file in changedFiles) {
                try {
                    val chunks = chunkFile(file)
                    val folder = file.parentFile?.name ?: ""
                    val fileHash = file.sha256()
                    val fileModifiedAt = file.lastModified()
                    val now = System.currentTimeMillis()

                    for ((i, chunkText) in chunks.withIndex()) {
                        pendingBatch.add(
                            DocumentEntity(
                                filePath = file.absolutePath,
                                fileModifiedAt = fileModifiedAt,
                                fileHash = fileHash,
                                chunkIndex = i,
                                chunkText = chunkText,
                                sourceFolder = folder,
                                createdAt = now,
                                updatedAt = now,
                            )
                        )

                        // 凑够一批就 embed + 入库
                        if (pendingBatch.size >= batchSize) {
                            processBatch(pendingBatch, modelVersion)
                            processedChunks += pendingBatch.size
                            pendingBatch.clear()

                            // 给 GC 喘口气
                            if (processedChunks % 50 == 0) {
                                System.gc()
                            }
                        }

                        progress = progress.copy(
                            processedFiles = progress.processedFiles + 1,
                        )
                        progressCallback?.invoke(progress)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process file: ${file.absolutePath}", e)
                    progress = progress.copy(errors = progress.errors + 1)
                }
            }

            // 处理尾部批次
            if (pendingBatch.isNotEmpty()) {
                processBatch(pendingBatch, modelVersion)
                processedChunks += pendingBatch.size
                pendingBatch.clear()
            }

            // 清理已删除文件
            if (changedFiles.isNotEmpty()) {
                val currentPaths = mdFiles.map { it.absolutePath }.toSet()
                val stalePaths = indexedPaths.filter { it !in currentPaths }
                if (stalePaths.isNotEmpty()) {
                    documentDAO.deleteByFilePaths(stalePaths)
                    progress = progress.copy(deletedChunks = stalePaths.size)
                }
            }

            progressCallback?.invoke(progress.copy(isRunning = false))
            Log.i(TAG, "Index complete: $totalFiles files, ${progress.newChunks} new chunks")
        }
    }

    /**
     * 将单个 .md 文件按标题切分为多个 chunk。
     * 策略：按 ## 或 ### 标题切分，长段落二次裁切。
     */
    private fun chunkFile(file: File): List<String> {
        // 流式读取，永远不 readText() 整个文件
        if (!file.exists() || file.length() == 0L) return emptyList()
        if (file.length() > 50 * 1024 * 1024) {
            Log.w(TAG, "Skipping oversized file: ${file.absolutePath} (${file.length() / 1024 / 1024}MB)")
            return emptyList()
        }

        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()
        var hasAnyHeading = false

        file.bufferedReader(Charsets.UTF_8).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                val isHeading = l.startsWith("## ") || l.startsWith("### ")

                if (isHeading) {
                    hasAnyHeading = true
                    flushChunkBuilder(currentChunk, chunks)
                }

                // 单行超长 → 读一点就 flush，不积累
                if (l.length > CHUNK_MAX_CHARS) {
                    if (currentChunk.isNotEmpty()) {
                        flushChunkBuilder(currentChunk, chunks)
                    }
                    // 直接用原字符串的字符数组遍历，避免 substring 反复复制
                    var pos = 0
                    while (pos < l.length) {
                        val end = minOf(pos + CHUNK_MAX_CHARS, l.length)
                        currentChunk.append(l, pos, end)
                        flushChunkBuilder(currentChunk, chunks)
                        pos = end
                    }
                } else {
                    currentChunk.appendLine(l)
                }

                // 无论有无标题，超过长度都要截断
                if (currentChunk.length >= CHUNK_MAX_CHARS) {
                    flushChunkBuilder(currentChunk, chunks)
                }
            }
        }

        // 最后一个 chunk
        flushChunkBuilder(currentChunk, chunks)

        return chunks
    }

    /**
     * 将 StringBuilder 中的内容刷出为一个 chunk，然后清空 builder。
     * 如果内容超过 CHUNK_MAX_CHARS，会在内部按长度二次切分（也是流式，不复制大字符串）。
     */
    private fun flushChunkBuilder(builder: StringBuilder, output: MutableList<String>) {
        if (builder.isEmpty()) return

        // 安全阀：builder 超过 50KB 直接丢弃（不太可能是有效文本）
        if (builder.length > 50 * 1024) {
            Log.w(TAG, "Builder too large (${builder.length} chars), discarding")
            builder.clear()
            return
        }

        val text = builder.toString().trim()
        builder.clear()
        if (text.isEmpty()) return

        if (text.length <= CHUNK_MAX_CHARS) {
            output.add(text)
            return
        }

        // 长文本：原地二次切分，不用 substring 复制整串
        var pos = 0
        while (pos < text.length) {
            val end = minOf(pos + CHUNK_MAX_CHARS, text.length)
            // 尽量在句尾断开
            val cutPos = if (end < text.length) {
                val searchStart = maxOf(pos, end - 200.coerceAtMost(end - pos))
                val segment = text.substring(searchStart, end)
                val localIdx = segment.lastIndexOfAny(
                    charArrayOf('\u3002', '.', '\n', '\uff01', '\uff1f', '!', '?'),
                )
                if (localIdx >= 0) searchStart + localIdx + 1 else end
            } else {
                end
            }
            val chunk = if (cutPos > pos) text.substring(pos, cutPos).trim() else ""
            if (chunk.isNotEmpty()) output.add(chunk)
            pos = cutPos.coerceAtLeast(pos + 1)
            if (pos >= text.length) break
            pos -= CHUNK_OVERLAP_CHARS
        }
    }

    /**
     * 获取索引统计信息
     */
    suspend fun getStats(): IndexStats {
        val total = documentDAO.count()
        val files = documentDAO.countFiles()
        val folders = documentDAO.getDistinctFolders()
        val recent = documentDAO.getRecent(5)
        val folderStats = documentDAO.getFolderFileCounts()
        return IndexStats(
            totalChunks = total,
            totalFiles = files,
            folderCount = folders.size,
            recentFiles = recent.map { it.filePath },
            lastUpdated = recent.maxOfOrNull { it.updatedAt } ?: 0,
            folderFileCounts = folderStats,
        )
    }

    data class IndexStats(
        val totalChunks: Int,
        val totalFiles: Int,
        val folderCount: Int,
        val recentFiles: List<String>,
        val lastUpdated: Long,
        val folderFileCounts: List<FolderStat> = emptyList(),
    )

    /**
     * 处理一批 DocumentEntity：生成 embedding 并写入数据库。
     */
    private suspend fun processBatch(
        batch: List<DocumentEntity>,
        modelVersion: String,
    ) {
        val texts = batch.map { it.chunkText.take(2048) }
        val embeddings = try {
            embeddingService.embedBatch(texts)
        } catch (e: Exception) {
            Log.e(TAG, "Embedding batch failed", e)
            List(texts.size) { emptyList<Float>() }
        }

        val entities = batch.mapIndexed { j, entity ->
            if (j < embeddings.size && embeddings[j].isNotEmpty()) {
                entity.copy(
                    embedding = VectorEngine.floatsToJson(embeddings[j]),
                    embeddingModelId = modelVersion,
                )
            } else {
                entity
            }
        }

        documentDAO.insertAll(entities)
    }

}

/**
 * 计算文件的 SHA-256 哈希
 */
fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    inputStream().use { stream ->
        var bytesRead: Int
        while (stream.read(buffer).also { bytesRead = it } >= 0) {
            digest.update(buffer, 0, bytesRead)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
