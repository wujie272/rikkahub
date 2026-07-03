package me.rerere.rikkahub.data.garden

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.ai.rag.VectorEngine
import java.io.File
import java.security.MessageDigest

/**
 * 数字花园索引服务。
 * 遍历笔记库、切 chunk、算 embedding、增量更新。
 */
class GardenIndexService(
    private val documentDAO: DocumentDAO,
    private val embeddingService: EmbeddingService,
) {
    companion object {
        private const val TAG = "GardenIndex"
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
        progressCallback: ((IndexProgress) -> Unit)? = null,
    ) {
        withContext(Dispatchers.IO) {
            val vaultDir = File(vaultPath)
            if (!vaultDir.exists() || !vaultDir.isDirectory) {
                Log.w(TAG, "Vault path does not exist: $vaultPath")
                return@withContext
            }

            // 收集所有 .md 文件
            val mdFiles = vaultDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() == "md" }
                .toList()

            val totalFiles = mdFiles.size
            if (totalFiles == 0) {
                Log.i(TAG, "No .md files found in $vaultPath")
                return@withContext
            }

            // 获取已索引的文件路径集合
            val indexedPaths = documentDAO.getAll()
                .map { it.filePath }
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
            Log.i(TAG, "Index complete: $totalFiles files, ${newChunks.size} new chunks")
        }
    }

    /**
     * 将单个 .md 文件按标题切分为多个 chunk。
     * 策略：按 ## 或 ### 标题切分，长段落二次裁切。
     */
    private fun chunkFile(file: File): List<String> {
        // 流式读取，避免大文件 OOM
        if (!file.exists() || file.length() == 0L) return emptyList()
        // 跳过超过 50MB 的文件
        if (file.length() > 50 * 1024 * 1024) {
            Log.w(TAG, "Skipping oversized file: ${file.absolutePath} (${file.length() / 1024 / 1024}MB)")
            return emptyList()
        }

        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()
        var headingCount = 0

        file.bufferedReader(Charsets.UTF_8).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                // 检测 ## 或 ### 标题
                if ((l.startsWith("## ") || l.startsWith("### ")) && currentChunk.isNotEmpty()) {
                    // 保存上一个 chunk
                    val chunk = currentChunk.toString().trim()
                    if (chunk.isNotEmpty()) {
                        if (chunk.length > CHUNK_MAX_CHARS) {
                            chunks.addAll(chunkByLength(chunk))
                        } else {
                            chunks.add(chunk)
                        }
                    }
                    currentChunk.clear()
                    headingCount++
                }
                currentChunk.appendLine(l)
            }
        }

        // 最后一个 chunk
        val lastChunk = currentChunk.toString().trim()
        if (lastChunk.isNotEmpty()) {
            if (lastChunk.length > CHUNK_MAX_CHARS) {
                chunks.addAll(chunkByLength(lastChunk))
            } else {
                chunks.add(lastChunk)
            }
        }

        // 完全没有标题时，按长度切分整个文件
        if (headingCount == 0 && chunks.isEmpty()) {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                val content = reader.readText()
                if (content.isNotBlank()) {
                    return chunkByLength(content)
                }
            }
        }

        return chunks
    }

    /**
     * 按固定长度切分文本，带重叠。
     */
    private fun chunkByLength(text: String): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + CHUNK_MAX_CHARS, text.length)
            val cutEnd = if (end < text.length) {
                val searchStart = maxOf(start, end - 200)
                val segment = text.substring(searchStart, end)
                val localIndex = segment.lastIndexOfAny(
                    charArrayOf('。', '.', '\n', '！', '？', '!', '?'),
                )
                val lastPeriod = if (localIndex >= 0) searchStart + localIndex else -1
                if (lastPeriod > start) lastPeriod + 1 else end
            } else {
                end
            }
            chunks.add(text.substring(start, cutEnd).trim())
            start = cutEnd - CHUNK_OVERLAP_CHARS
            if (start >= text.length) break
        }
        return chunks.filter { it.isNotEmpty() }
    }

    /**
     * 获取索引统计信息
     */
    suspend fun getStats(): IndexStats {
        val total = documentDAO.count()
        val files = documentDAO.countFiles()
        val folders = documentDAO.getDistinctFolders()
        val recent = documentDAO.getRecent(5)
        return IndexStats(
            totalChunks = total,
            totalFiles = files,
            folderCount = folders.size,
            recentFiles = recent.map { it.filePath },
            lastUpdated = recent.maxOfOrNull { it.updatedAt } ?: 0,
        )
    }

    data class IndexStats(
        val totalChunks: Int,
        val totalFiles: Int,
        val folderCount: Int,
        val recentFiles: List<String>,
        val lastUpdated: Long,
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
