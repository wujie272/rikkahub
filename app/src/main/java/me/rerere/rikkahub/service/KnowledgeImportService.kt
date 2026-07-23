package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.knowledge.KnowledgeService
import me.rerere.rikkahub.ui.components.settings.ImportProgressState
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import org.koin.java.KoinJavaComponent

const val KNOWLEDGE_IMPORT_NOTIFICATION_CHANNEL_ID = "knowledge_import"
private const val NOTIFICATION_ID = 9931

/**
 * 知识库导入前台服务。
 * 用户选择目录后启动 → 导入完成后自动停止。
 * 通过前台通知保活，切到后台一小时后回来还能看到进度。
 */
class KnowledgeImportService : android.app.Service() {

    private val appScope: AppScope by lazy {
        KoinJavaComponent.get(AppScope::class.java)
    }
    private val knowledgeService: KnowledgeService by lazy {
        KoinJavaComponent.get(KnowledgeService::class.java)
    }

    private var importJob: Job? = null

    /** 服务级进度，供 UI 页面 collect（如 Activity 重新绑定后） */
    private val _progress = MutableStateFlow(ImportProgressState())
    val progress: StateFlow<ImportProgressState> = _progress.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        observeImportProgress()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kbId = intent?.getStringExtra(EXTRA_KB_ID) ?: return START_NOT_STICKY
        val treeUri = intent?.getParcelableExtra<Uri>(EXTRA_TREE_URI) ?: return START_NOT_STICKY

        // 显示前台通知
        startForeground(NOTIFICATION_ID, buildNotification(
            title = getString(R.string.app_name),
            content = getString(R.string.kb_import_notification_starting),
            progress = 0,
            max = 0,
        ).build())

        // 启动导入（KnowledgeService 内部用 AppScope 跑在 IO 线程）
        knowledgeService.startImportDirectory(kbId, contentResolver, treeUri)

        // 如果进程没被杀，Service 会一直运行直到导入完成
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onDestroy() {
        importJob?.cancel()
        cancelNotification(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun observeImportProgress() {
        importJob = appScope.launch(Dispatchers.IO) {
            knowledgeService.importProgress.collect { state ->
                if (!isActive) return@collect
                _progress.value = state

                if (!state.active) {
                    // 导入完成 → 更新通知为"已完成"并停止服务
                    showCompletedNotification(state)
                    stopSelf()
                    return@collect
                }

                // 实时更新前台通知进度
                val notification = buildNotification(
                    title = getString(R.string.kb_import_notification_title),
                    content = if (state.currentFileName.isNotBlank()) {
                        "${state.completedFiles}/${state.totalFiles}  ${state.currentFileName}"
                    } else {
                        "${state.completedFiles}/${state.totalFiles}"
                    },
                    progress = state.completedFiles,
                    max = state.totalFiles.coerceAtLeast(1),
                )
                val nm = androidx.core.app.NotificationManagerCompat.from(this@KnowledgeImportService)
                nm.notify(NOTIFICATION_ID, notification.build())
            }
        }
    }

    private fun showCompletedNotification(state: ImportProgressState) {
        sendNotification(
            channelId = KNOWLEDGE_IMPORT_NOTIFICATION_CHANNEL_ID,
            notificationId = NOTIFICATION_ID,
        ) {
            title = getString(R.string.kb_import_notification_done)
            content = "已完成 ${state.completedFiles} 个文件导入"
            autoCancel = true
            contentIntent = getDetailPendingIntent()
        }
    }

    private fun getDetailPendingIntent(): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(
        title: String,
        content: String,
        progress: Int,
        max: Int,
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, KNOWLEDGE_IMPORT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, max == 0)
            .setContentIntent(getDetailPendingIntent())
    }

    companion object {
        private const val EXTRA_KB_ID = "kb_id"
        private const val EXTRA_TREE_URI = "tree_uri"

        fun start(context: Context, kbId: String, treeUri: Uri) {
            val intent = Intent(context, KnowledgeImportService::class.java).apply {
                putExtra(EXTRA_KB_ID, kbId)
                putExtra(EXTRA_TREE_URI, treeUri)
            }
            context.startForegroundService(intent)
        }
    }
}
