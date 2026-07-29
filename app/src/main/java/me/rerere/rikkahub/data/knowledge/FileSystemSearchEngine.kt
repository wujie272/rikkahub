package me.rerere.rikkahub.data.knowledge

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 文件系统搜索引擎 —— 直接在外部目录上搜索，零存储。
 *
 * 核心流程：
 * 1. SAF URI → POSIX 路径解析（copy from OpenMinis）
 * 2. Files.walk() 遍历目录树，收集文本文件
 * 3. 并行逐行搜索（不加载全文到内存）
 * 4. 返回命中结果
 *
 * 内存占用：~1MB，不随文件大小增长（每次只读一行）
 */
class FileSystemSearchEngine(
    private val context: Context,
) {
    /**
     * 一次搜索命中的结果
     */
    data class SearchHit(
        val filePath: String,
        val fileName: String,
        val fileSize: Long,
        val lineNumber: Int,
        val lineContent: String,
        val contextBefore: List<String>,
        val contextAfter: List<String>,
    )

    /**
     * 搜索配置
     */
    data class SearchConfig(
        /** 最大返回结果数 */
        val maxResults: Int = 30,
        /** 跳过超过此大小的文件 */
        val maxFileSize: Long = 50 * 1024 * 1024, // 50MB
        /** 最多遍历文件数 */
        val maxFileCount: Int = 2000,
        /** 匹配上下文行数 */
        val contextLines: Int = 2,
        /** 并行搜索文件数 */
        val parallelCount: Int = 4,
        /** 是否区分大小写 */
        val caseSensitive: Boolean = false,
        /** 总超时毫秒 */
        val timeoutMs: Long = 30_000,
        /** 是否允许搜索二进制文件（默认只搜文本） */
        val allowBinary: Boolean = false,
    )

    /**
     * 解析 SAF tree URI 为真实的 POSIX 路径
     *
     * 只支持 com.android.externalstorage.documents 类型的 URI，
     * 云盘（Drive/Dropbox/OneDrive）等返回 null。
     *
     * @return 如 /storage/emulated/0/Documents/财报/，或 null
     */
    fun resolveTreeUriToPosixPath(treeUri: Uri): String? {
        // 只接受设备存储，拒绝云盘
        if (treeUri.authority != "com.android.externalstorage.documents") return null

        val docId = runCatching {
            DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull() ?: return null

        val sep = docId.indexOf(':')
        val volume = if (sep < 0) docId else docId.substring(0, sep)
        val relPath = if (sep < 0) "" else docId.substring(sep + 1)

        val volumeRoot = resolveVolumeRoot(volume) ?: return null
        return if (relPath.isEmpty()) volumeRoot else "$volumeRoot/$relPath"
    }

    /**
     * 搜索目录，返回命中结果
     *
     * @param dirPath 已解析的 POSIX 目录路径
     * @param query 搜索关键词
     * @param config 搜索配置
     * @param onProgress 进度回调 (已扫描文件数, 已匹配文件数)
     */
    suspend fun search(
        dirPath: String,
        query: String,
        config: SearchConfig = SearchConfig(),
        onProgress: (scanned: Int, matched: Int) -> Unit = { _, _ -> },
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        val root = File(dirPath)
        if (!root.isDirectory) return@withContext emptyList()

        val pattern = if (config.caseSensitive) query else query.lowercase()

        // Phase 1: 遍历目录树，收集可搜索的文本文件
        val textFiles = mutableListOf<File>()
        var scanned = 0

        try {
            Files.walk(root.toPath()).use { stream ->
                stream.iterator().forEach { path ->
                    if (!isActive) return@forEach
                    if (scanned >= config.maxFileCount) return@forEach

                    val file = path.toFile()
                    if (!file.isFile) return@forEach
                    if (file.length() > config.maxFileSize) return@forEach
                    if (file.length() == 0L) return@forEach
                    if (!config.allowBinary && !isTextFile(file.name)) return@forEach

                    textFiles.add(file)
                    scanned++
                }
            }
        } catch (_: Exception) {
            // 权限不足或目录不可读，静默跳过
        }

        if (textFiles.isEmpty()) return@withContext emptyList()

        // Phase 2: 并行搜索文件内容
        val matchedFiles = mutableListOf<SearchHit>()
        val semaphore = Semaphore(config.parallelCount)

        coroutineScope {
            textFiles.map { file ->
                async(Dispatchers.IO) {
                    if (!isActive) return@async emptyList<SearchHit>()
                    semaphore.withPermit {
                        searchFile(file, pattern, config)
                    }
                }
            }.awaitAll().forEach { hits ->
                if (isActive) {
                    synchronized(matchedFiles) {
                        matchedFiles.addAll(hits)
                    }
                }
            }
        }

        matchedFiles.take(config.maxResults)
    }

    /**
     * 搜索单个文件：逐行读取，不加载全文到内存
     */
    private fun searchFile(
        file: File,
        pattern: String,
        config: SearchConfig,
    ): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        // 用环形缓冲区存最近 N 行，用于 context_before
        val recentLines = ArrayDeque<String>(config.contextLines + 1)

        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                var lineNumber = 0
                var line = reader.readLine()
                while (line != null) {
                    if (hits.size >= config.maxResults) break
                    lineNumber++
                    recentLines.addLast(line)
                    if (recentLines.size > config.contextLines + 1) {
                        recentLines.removeFirst()
                    }

                    val check = if (config.caseSensitive) line else line.lowercase()
                    if (check.contains(pattern)) {
                        // context_before = 最近行中去掉当前行
                        val before = recentLines
                            .take(recentLines.size - 1)
                            .toList()

                        hits.add(SearchHit(
                            filePath = file.absolutePath,
                            fileName = file.name,
                            fileSize = file.length(),
                            lineNumber = lineNumber,
                            lineContent = line.trim(),
                            contextBefore = before,
                            contextAfter = emptyList(), // 后面补
                        ))
                    }
                    line = reader.readLine()
                }
            }
        } catch (_: Exception) {
            // 编码问题或权限，静默跳过
        }

        // 补 context_after：对于每个命中，往后读 N 行
        if (hits.isNotEmpty() && config.contextLines > 0) {
            fillContextAfter(file, hits, config.contextLines)
        }

        return hits
    }

    /**
     * 第二次遍历文件，补 context_after 行
     */
    private fun fillContextAfter(
        file: File,
        hits: List<SearchHit>,
        contextLines: Int,
    ) {
        try {
            val hitLines = hits.map { it.lineNumber }.toSet()
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                var lineNumber = 0
                var line = reader.readLine()
                val pendingHits = mutableMapOf<Int, MutableList<String>>()

                while (line != null) {
                    lineNumber++
                    // 检查是否有命中刚好在前面，需要它的 context_after
                    for ((hitLine, buffer) in pendingHits) {
                        if (lineNumber - hitLine <= contextLines) {
                            buffer.add(line.trim())
                        }
                    }
                    // 清理过期条目
                    pendingHits.keys
                        .filter { lineNumber - it > contextLines }
                        .forEach { pendingHits.remove(it) }

                    // 如果是命中行，初始化它的 context_after buffer
                    if (lineNumber in hitLines) {
                        pendingHits[lineNumber] = mutableListOf()
                    }

                    line = reader.readLine()
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * 统计目录信息（文件数、总大小）
     */
    suspend fun scanDirectoryStats(dirPath: String): DirStats = withContext(Dispatchers.IO) {
        val root = File(dirPath)
        if (!root.isDirectory) return@withContext DirStats(0, 0)

        var fileCount = 0
        var totalSize = 0L

        try {
            Files.walk(root.toPath()).use { stream ->
                stream.iterator().forEach { path ->
                    val file = path.toFile()
                    if (file.isFile) {
                        fileCount++
                        totalSize += file.length()
                    }
                }
            }
        } catch (_: Exception) { }

        DirStats(fileCount, totalSize)
    }

    /**
     * 列出目录下所有文本文件
     */
    suspend fun listFiles(dirPath: String, maxResults: Int = 1000): List<FileItem> = withContext(Dispatchers.IO) {
        val root = File(dirPath)
        if (!root.isDirectory) return@withContext emptyList()

        val files = mutableListOf<FileItem>()
        try {
            Files.walk(root.toPath()).use { stream ->
                stream.iterator().forEach { path ->
                    if (files.size >= maxResults) return@forEach
                    val file = path.toFile()
                    if (file.isFile && file.length() > 0 && isTextFile(file.name)) {
                        files.add(FileItem(
                            filePath = file.absolutePath,
                            fileName = file.name,
                            fileSize = file.length(),
                        ))
                    }
                }
            }
        } catch (_: Exception) { }
        return@withContext files
    }

    data class DirStats(
        val fileCount: Int,
        val totalSizeBytes: Long,
    )

    /**
     * 单个文件信息
     */
    data class FileItem(
        val filePath: String,
        val fileName: String,
        val fileSize: Long,
    )

    companion object {
        /** 可搜索的文本文件扩展名 */
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "rst", "adoc", "asciidoc",
            "json", "xml", "yml", "yaml", "toml", "ini", "cfg", "conf",
            "csv", "tsv", "log",
            "properties", "env", "gradle", "lock",
            "sh", "bash", "zsh", "fish", "bat", "ps1",
            "py", "js", "ts", "jsx", "tsx", "kt", "kts", "java",
            "swift", "go", "rs", "rb", "php", "pl", "lua",
            "c", "h", "cpp", "hpp", "cc", "cxx", "hxx",
            "css", "scss", "less", "sass",
            "html", "htm", "xhtml",
            "sql", "r", "m", "mm",
            "tex", "bib",
            "gitignore", "dockerfile", "makefile",
            "svelte", "vue",
        )

        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico", "svg",
            "mp3", "wav", "aac", "flac", "ogg", "wma", "m4a",
            "mp4", "avi", "mkv", "mov", "wmv", "flv",
            "zip", "tar", "gz", "bz2", "7z", "rar",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "apk", "dex", "so", "dll", "exe",
            "ttf", "otf", "woff", "woff2",
            "db", "sqlite",
        )

        fun isTextFile(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return true // 无扩展名默认尝试搜索
            if (ext in BINARY_EXTENSIONS) return false
            return true // 未知扩展名也尝试搜索
        }

        /**
         * 判断文件是否可能是二进制文件（通过读取前 512 字节探测）
         * 比扩展名判断更准确，但需要读文件
         */
        fun isProbablyBinary(file: File): Boolean {
            if (file.length() < 4) return false
            try {
                val bytes = file.inputStream().use { it.readNBytes(512) }
                val nullCount = bytes.count { it == 0.toByte() }
                // 如果 null 字节占比 > 5%，判定为二进制
                return nullCount.toFloat() / bytes.size > 0.05f
            } catch (_: Exception) {
                return false
            }
        }
    }

    private fun resolveVolumeRoot(volume: String): String? {
        if (volume.equals("primary", ignoreCase = true)) {
            return Environment.getExternalStorageDirectory()?.absolutePath
        }
        // 尝试外部存储
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            sm?.storageVolumes?.firstOrNull {
                it.uuid?.equals(volume, ignoreCase = true) == true
            }?.directory?.absolutePath?.let { return it }
        }
        val fallback = File("/storage/$volume")
        return if (fallback.isDirectory) fallback.absolutePath else null
    }
}
