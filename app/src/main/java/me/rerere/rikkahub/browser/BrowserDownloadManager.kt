package me.rerere.rikkahub.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 下载管理器 —— 管理下载任务列表和进度状态。
 *
 * 从 [BrowserController] 中拆出，职责单一：跟踪下载任务的生命周期。
 * 实际的下载 IO 由 [BrowserController] 的 DownloadListener 触发，
 * 进度更新通过此管理器存入状态流。
 */
class BrowserDownloadManager {

    data class DownloadState(
        val url: String,
        val filename: String,
        val progress: Float = 0f,       // 0..1, -1 = indeterminate
        val completed: Boolean = false,
        val formattedSize: String = "",
        val bytesDone: Long = 0L,
        val totalBytes: Long = 0L,
    )

    private val _downloads = MutableStateFlow<List<DownloadState>>(emptyList())
    val downloadsFlow: StateFlow<List<DownloadState>> = _downloads.asStateFlow()

    /**
     * 注册一个新下载任务。
     */
    fun start(url: String, filename: String, totalBytes: Long = -1L) {
        val entry = DownloadState(
            url = url,
            filename = filename,
            progress = if (totalBytes > 0) 0f else -1f,
            totalBytes = totalBytes,
        )
        _downloads.value = listOf(entry) + _downloads.value
    }

    /**
     * 更新下载进度。
     */
    fun updateProgress(url: String, bytesDone: Long, totalBytes: Long) {
        _downloads.value = _downloads.value.map {
            if (it.url == url) {
                val progress = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f) else -1f
                it.copy(progress = progress, bytesDone = bytesDone, totalBytes = totalBytes)
            } else it
        }
    }

    /**
     * 标记下载完成。
     */
    fun finish(url: String, formattedSize: String) {
        _downloads.value = _downloads.value.map {
            if (it.url == url) it.copy(completed = true, progress = 1f, formattedSize = formattedSize)
            else it
        }
    }

    /** 获取未读下载数量（用于 badge）。 */
    fun unreadCount(): Int = _downloads.value.count { !it.completed }

    /** 清除已完成下载。 */
    fun clearCompleted() {
        _downloads.value = _downloads.value.filter { !it.completed }
    }
}
