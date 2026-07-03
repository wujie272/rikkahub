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

            // 第一步：处理变更/新增文件
            val newChunks = mutableListOf<DocumentEntity>()
            val changedFiles = mutableListOf<File>()

            mdFiles.forEachIndexed { index, file ->
                val relPath = file.absolutePath
                val existingHash = documentDAO.getFileHash(relPath)
                val currentHash = file.sha256()

                if (existingHash == null) {
                    // 新文件
                    changedFiles.add(file)
                } else if (existingHash != currentHash) {
                    // 文件已修改 → 删除旧记录
                    documentDAO.deleteByFilePath(relPath)
                    changedFiles.add(file)
                } else {
                    // 文件没变 → 跳过
                    progress = progress.copy(
                        processedFiles = index + 1,
                        skippedFiles = progress.skippedFiles + 1,
                    )
                    progressCallback?.invoke(progress)
                }
            }

            // 第二步：切分变更文件
            for (file in changedFiles) {
                try {
                    val chunks = chunkFile(file)
                    val folder = file.parentFile?.name ?: ""
                    val now = System.currentTimeMillis()

                    chunks.forEachIndexed { i, chunkText ->
                        newChunks.add(
                            DocumentEntity(
                                filePath = file.absolutePath,
                                fileModifiedAt = file.lastModified(),
                                fileHash = file.sha256(),
                                chunkIndex = i,
                                chunkText = chunkText,
                                sourceFolder = folder,
                                createdAt = now,
                                updatedAt = now,
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to chunk file: ${file.absolutePath}", e)
                    progress = progress.copy(errors = progress.errors + 1)
                }
            }

            // 第三步：批量生成 embedding（一次最多 20 条）
            val modelVersion = embeddingService.currentModelId?.toString() ?: ""
            val batchSize = 20

            for (i in newChunks.indices.step(batchSize)) {
                val batch = newChunks.subList(i, minOf(i + batchSize, newChunks.size))
                val texts = batch.map { it.chunkText.take(2048) }

                val embeddings = try {
                    embeddingService.embedBatch(texts)
                } catch (e: Exception) {
                    Log.e(TAG, "Embedding batch failed at index $i", e)
                    List(texts.size) { emptyList<Float>() }
                }

                for (j in batch.indices) {
                    if (j < embeddings.size && embeddings[j].isNotEmpty()) {
                        batch[j] = batch[j].copy(
                            embedding = VectorEngine.floatsToJson(embeddings[j]),
                            embeddingModelId = modelVersion,
                        )
                    }
                }

                // 写入数据库
                documentDAO.insertAll(batch)

                progress = progress.copy(
                    processedFiles = progress.processedFiles + batch.size,
                    newChunks = progress.newChunks + batch.size,
                )
                progressCallback?.invoke(progress)
            }

            // 第四步：清理已删除的文件
            val currentPaths = mdFiles.map { it.absolutePath }.toSet()
            val stalePaths = indexedPaths.filter { it !in currentPaths }
            if (stalePaths.isNotEmpty()) {
                documentDAO.deleteByFilePaths(stalePaths)
                progress = progress.copy(deletedChunks = stalePaths.size)
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
        val content = file.readText(Charsets.UTF_8)
        if (content.isBlank()) return emptyList()

        // 按 ## 或 ### 标题分割
        val headingRegex = Regex("^#{2,3}\\s+.*$", RegexOption.MULTILINE)
        val matches = headingRegex.findAll(content).toList()

        if (matches.isEmpty()) {
            // 没有标题，直接按长度切
            return chunkByLength(content)
        }

        val chunks = mutableListOf<String>()
        for (i in matches.indices) {
            val start = matches[i].range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else content.length
            val section = content.substring(start, end).trim()

            if (section.length > CHUNK_MAX_CHARS) {
                // 长段落二次裁切
                chunks.addAll(chunkByLength(section))
            } else if (section.isNotEmpty()) {
                chunks.add(section)
            }
        }

        // 处理标题前的引言部分
        val preamble = content.substring(0, matches.firstOrNull()?.range?.first ?: 0).trim()
        if (preamble.isNotEmpty()) {
            if (preamble.length > CHUNK_MAX_CHARS) {
                chunks.addAll(0, chunkByLength(preamble))
            } else {
                chunks.add(0, preamble)
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
                    charArrayOf('。', '.', '
', '！', '？', '!', '?'),
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
