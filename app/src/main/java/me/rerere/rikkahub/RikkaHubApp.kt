package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import me.rerere.common.android.appTempFolder
import com.whl.quickjs.android.QuickJSLoader
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.di.knowledgeModule
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.service.ShizukuManager
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceManager
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"
const val KNOWLEDGE_IMPORT_NOTIFICATION_CHANNEL_ID = "knowledge_import"

class RikkaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule, knowledgeModule)
        }
        this.createNotificationChannel()

        // Restore any headless conversation IDs that survived a process kill; must run
        // before any cron worker fires so mark/unmark are consistent.
        HeadlessConversations.init(this)

        // Sweep orphan headless conversations created by workers that were killed mid-execute.
        sweepOrphanHeadlessConversations()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // Init QuickJS native library
        QuickJSLoader.init()

        // delete temp files
        deleteTempFiles()

        // cleanup stale tool output files
        cleanupToolOutputs()

        // cleanup workspace temp dirs (proot + rootfs /tmp)
        cleanupWorkspaceTempDirs()

        // check workspace integrity (mark workspaces with missing files as broken after backup restore)
        checkWorkspaceIntegrity()

        // sync upload files to DB
        syncManagedFiles()

        // Start WebServer if enabled in settings
        startWebServerIfEnabled()

        // Eagerly construct ChatService on the main thread. Its constructor calls
        // LifecycleRegistry.addObserver which throws if it runs off-main.
        eagerlyInitChatService()

        // Initialise the agent's `~` workspace at /data/data/<pkg>/files/workspace/.
        // Tools resolve `~` and `~/foo` paths to this dir, giving the LLM a stable
        // sandbox for `.learnings/`, scratch files, and skill state without scoped-
        // storage friction. Termux-style: private, persistent, OS-blessed.
        me.rerere.rikkahub.data.ai.tools.local.AgentWorkspace.init(this)

        // Copy any default skills bundled in assets/default-skills/* into the user's skills
        // dir on first launch. SkillManager guards via a per-skill .seeded sentinel so this
        // is a one-time install — user edits / deletes are respected on subsequent launches.
        seedDefaultSkillsIfNeeded()

        // Increment launch count
        incrementLaunchCount()

        // Initialise Shizuku — 特权级 Shell 执行引擎
        ShizukuManager.init(this)

        // Phase 12: kick off the workflow trigger registry. It subscribes to the workflows
        // table and reconciles broadcast receivers / geofences / time_cron schedules with
        // every change. With zero enabled workflows, no receivers are registered.
        startWorkflowRegistry()

        // Phase-17 stability — register a network-change monitor that evicts OkHttp's
        // connection pool on every default-network transition. Fixes the post-Termux-
        // interactive-session "Unable to resolve host …" bug: when the user opens
        // Termux's terminal for `htop` the app backgrounds, Android may flip the
        // network into a restricted state, and the JVM's negative DNS cache plus
        // OkHttp's idle sockets keep the failure sticky after return. Eviction on the
        // next onAvailable forces a fresh DNS lookup + new socket on the next request.
        startNetworkChangeMonitor()

        // Phase 24 — unified AgentRun ledger boot recovery. Walk the ledger once per
        // process start: any autonomous run (cron / workflow / sub-agent /
        // external automation) left in flight by a killed process is flipped to
        // `process_lost` and a single aggregate notification is fired. This is the
        // cross-pillar generalisation of the Phase 9.5 cron stranded-row sweep and is what
        // makes background sub-agents survivable across process death.
        runAgentRunBootRecovery()



        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }


    /**
     * Phase 24 — run the unified AgentRun ledger boot-recovery sweep once per process
     * start. Best-effort: a slow or failed sweep must never block app start, so it runs on
     * the IO dispatcher off [AppScope] and swallows its own failures.
     */
    private fun runAgentRunBootRecovery() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<me.rerere.rikkahub.data.agentrun.AgentRunBootRecovery>().runRecovery()
            }.onFailure {
                Log.w(TAG, "runAgentRunBootRecovery failed", it)
            }
        }
    }

    private fun startWorkflowRegistry() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val registry = get<me.rerere.rikkahub.workflow.trigger.TriggerRegistry>()
                val engine = get<me.rerere.rikkahub.workflow.execution.WorkflowEngine>()
                registry.setEngineCallback(engine.triggerCallback)
                registry.start()
            }.onFailure {
                Log.e(TAG, "startWorkflowRegistry failed", it)
            }
        }
    }

    private fun startNetworkChangeMonitor() {
        runCatching {
            val client = get<okhttp3.OkHttpClient>()
            me.rerere.rikkahub.utils.NetworkChangeMonitor.start(this, client)
        }.onFailure {
            Log.w(TAG, "startNetworkChangeMonitor failed", it)
        }
    }

    /**
     * Cleans up orphan conversations left by cron workers that were killed mid-execute.
     *
     * When a worker is killed between HeadlessConversations.mark() and unmark(), the
     * conversation ID remains in SharedPreferences. On the next app start we detect these
     * IDs and delete the corresponding "[Scheduled]" conversations from the DB so they
     * don't pollute the chat list.
     *
     * We clear the persisted set at the end regardless — if a conversation doesn't exist in
     * DB there's nothing to clean up, and stale IDs only confuse future sweeps.
     */
    private fun sweepOrphanHeadlessConversations() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val orphanIds = HeadlessConversations.activeIds()
                if (orphanIds.isEmpty()) return@runCatching
                Log.i(TAG, "sweepOrphanHeadlessConversations: found ${orphanIds.size} candidate(s)")
                val convRepo = get<me.rerere.rikkahub.data.repository.ConversationRepository>()
                for (id in orphanIds) {
                    runCatching {
                        val conv = convRepo.getConversationById(id)
                        if (conv != null && conv.title.startsWith("[Scheduled]")) {
                            Log.i(TAG, "sweepOrphanHeadlessConversations: deleting orphan conv $id")
                            convRepo.deleteConversation(conv)
                        }
                    }.onFailure { Log.w(TAG, "sweepOrphanHeadlessConversations: error for $id", it) }
                }
                HeadlessConversations.clearAll()
                Log.i(TAG, "sweepOrphanHeadlessConversations: sweep complete")
            }.onFailure {
                Log.e(TAG, "sweepOrphanHeadlessConversations failed", it)
            }
        }
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val store = get<SettingsStore>()
                val current = store.settingsFlowRaw.first()
                store.update(current.copy(launchCount = current.launchCount + 1))
                Log.i(TAG, "incrementLaunchCount: ${store.settingsFlowRaw.first().launchCount}")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun eagerlyInitChatService() {
        try {
            // Just resolving the singleton triggers Koin's factory; the side effect we care
            // about is the LifecycleRegistry.addObserver call inside ChatService.<init>,
            // which Android requires to happen on the main thread.
            get<me.rerere.rikkahub.service.ChatService>()
        } catch (t: Throwable) {
            Log.e(TAG, "eagerlyInitChatService failed", t)
        }
    }


    private fun cleanupWorkspaceTempDirs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }.onFailure {
                Log.e(TAG, "cleanupWorkspaceTempDirs failed", it)
            }
        }
    }

    private fun seedDefaultSkillsIfNeeded() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<me.rerere.rikkahub.data.files.SkillManager>().seedDefaultSkillsIfNeeded()
            }.onFailure {
                Log.e(TAG, "seedDefaultSkillsIfNeeded failed", it)
            }
        }
    }

    private fun checkWorkspaceIntegrity() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceRepository>().checkIntegrity()
            }.onFailure {
                Log.e(TAG, "checkWorkspaceIntegrity failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun cleanupToolOutputs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    // Android 17 (API 37) requires ACCESS_LOCAL_NETWORK to bind to LAN
                    // interfaces. localhost-only mode does not need it because traffic stays
                    // within the app's UID. Cherry-picked from upstream 80186f5d.
                    if (Build.VERSION.SDK_INT >= 37 &&
                        !settings.webServerLocalhostOnly &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.ACCESS_LOCAL_NETWORK
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: local network permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkaHubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val knowledgeImportChannel = NotificationChannelCompat
            .Builder(
                KNOWLEDGE_IMPORT_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.kb_import_notification_channel_name))
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(knowledgeImportChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
