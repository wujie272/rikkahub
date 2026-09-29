package me.rerere.rikkahub.di


import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.data.ai.requestlog.AIRequestLogManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer
import me.rerere.rikkahub.data.ai.tools.local.CameraResultBuffer
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.ai.tools.local.InteractiveToolStreamer
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.notifications.NotificationListenerPreferences
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.CronJobScheduler
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single { CameraResultBuffer() }
    single { BiometricResultBuffer() }
    // Phase 25 — NFC reader-mode + SAF directory-picker Activity bridges, and the SAF
    // tree-grant store backing the ExternalStorage tools.
    single { me.rerere.rikkahub.data.ai.tools.local.SafPickerResultBuffer() }
    single { me.rerere.rikkahub.data.storage.StorageVolumeGrantStore(get()) }

    single { ScheduledJobRepository(get<me.rerere.rikkahub.data.db.AppDatabase>().scheduledJobDao()) }
    single { me.rerere.rikkahub.data.repository.ScheduledJobRunRepository(get<me.rerere.rikkahub.data.db.AppDatabase>().scheduledJobRunDao()) }
    single { me.rerere.rikkahub.service.DirectModeActionRunner(get()) }
    single { CronJobScheduler(get(), get()) }
    single { SshHostRepository(get<me.rerere.rikkahub.data.db.AppDatabase>().sshHostDao()) }


    single { me.rerere.rikkahub.browser.BrowserPreferences(get()) }
    single { me.rerere.rikkahub.data.preferences.TermuxPreferences(get()) }


    // Pass 3 — NoOp implementations for headless screenshot streaming. The real
    // Telegram-backed implementations were removed; these fallbacks silently no-op.
    single<me.rerere.rikkahub.browser.BrowserScreenshotStreamer> {
        me.rerere.rikkahub.browser.BrowserScreenshotStreamer.NoOp
    }
    single<InteractiveToolStreamer> {
        InteractiveToolStreamer.NoOp
    }
    single { me.rerere.rikkahub.data.preferences.ToolApprovalPreferences(get()) }
    single { NotificationListenerPreferences(get()) }

    // Phase 13: External Automation Intent API
    single { me.rerere.rikkahub.automation.ExternalAutomationConfig(get()) }
    single {

        me.rerere.rikkahub.automation.ExternalAutomationDispatcher(
            context = get(),
            config = get(),
            chatService = get(),
            conversationRepo = get(),
            settingsStore = get(),
            appScope = get(),
            // Phase 24 — unified AgentRun ledger writer.
            agentRunRepo = get(),
        )
    }

    // Phase 14: Reliability bundle
    single { me.rerere.rikkahub.reliability.GitHubReleaseChecker(get()) }
    single { me.rerere.rikkahub.reliability.BugReportBuilder(get()) }

    // Phase 11: Sub-agents
    single { me.rerere.rikkahub.subagent.SubAgentRegistry() }
    single {
        me.rerere.rikkahub.subagent.SubAgentEngine(
            registry = get(),
            // chatService is resolved lazily inside SubAgentEngine to break the
            // ChatService→LocalTools→SubAgentEngine→ChatService cycle. See SubAgentEngine kdoc.
            conversationRepo = get(),
            settingsStore = get(),
            appScope = get(),
            // Phase 24 — unified AgentRun ledger writer. No DI cycle: AgentRunRepository
            // depends only on its DAO.
            agentRunRepo = get(),
        )
    }

    // Phase 16: Skill URL-import
    single {
        me.rerere.rikkahub.skills.SkillUrlImporter(
            skillManager = get<me.rerere.rikkahub.data.files.SkillManager>(),
        )
    }

    // Phase 19B: Skill isolation tester. Eager construction is safe here — ChatService
    // doesn't reach back into SkillTestRunner anywhere, so no DI cycle.
    single {
        me.rerere.rikkahub.skills.SkillTestRunner(
            chatService = get(),
            skillManager = get(),
            conversationRepo = get(),
            settingsStore = get(),
        )
    }

    // Phase 18: JS skills (run_js + secrets store)
    single { me.rerere.rikkahub.skills.js.JsSkillRunner(get()) }
    single { me.rerere.rikkahub.skills.js.SkillSecretsStore(get()) }

    // Phase 12: Workflows
    single {
        me.rerere.rikkahub.workflow.repository.WorkflowRepository(
            workflowDao = get<me.rerere.rikkahub.data.db.AppDatabase>().workflowDao(),
            workflowRunDao = get<me.rerere.rikkahub.data.db.AppDatabase>().workflowRunDao(),
        )
    }
    single { me.rerere.rikkahub.workflow.condition.ContextProvider(get()) }
    single { me.rerere.rikkahub.workflow.execution.WorkflowActionRunner() }
    single {
        me.rerere.rikkahub.workflow.execution.WorkflowEngine(
            repository = get(),
            settingsStore = get(),
            contextProvider = get(),
            actionRunner = get(),
        ).also { engine ->
            // Bridge for the repo to notify the engine on delete so the engine's per-workflow
            // lock map doesn't leak. Lazy because both singletons have to exist first.
            get<me.rerere.rikkahub.workflow.repository.WorkflowRepository>().bindEngine(engine)
        }
    }
    single {
        me.rerere.rikkahub.workflow.trigger.TriggerRegistry(
            context = get(),
            appScope = get(),
            workflowRepository = get(),
        )
    }

    single { me.rerere.rikkahub.data.keyboard.KeyboardApiClient(get()) }

    single {
        LocalTools(
            context = get(),
            eventBus = get(),
            cameraResultBuffer = get(),
            biometricResultBuffer = get(),
            scheduledJobRepository = get(),
            scheduledJobRunRepository = get(),
            cronJobScheduler = get(),
            settingsStore = get(),
            sshHostRepository = get(),

            notificationListenerPreferences = get(),
            mcpManager = get(),
            externalAutomationConfig = get(),
            gitHubReleaseChecker = get(),
            bugReportBuilder = get(),
            subAgentEngine = get(),
            subAgentRegistry = get(),
            conversationRepo = get(),
            workflowRepository = get(),
            workflowEngine = get(),
            skillUrlImporter = get(),
            skillManager = get(),
            jsSkillRunner = get(),
            skillSecretsStore = get(),
            browserPreferences = get(),
            termuxPreferences = get(),
            interactiveToolStreamer = get(),
            safPickerResultBuffer = get(),
            storageVolumeGrantStore = get(),
            okHttpClient = get(),

            keyboardApiClient = get(),
            ttsManager = get(),
        )

    }

    single {
        UpdateChecker(
            client = get(),
            appScope = get(),
        )
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        WorkspaceTerminalSessionManager(get(), get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    single {
        AILoggingManager(get(), get(), get())
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            toolApprovalPreferences = get(),
            workspaceRepository = get(),
            folderRepository = get(),
            knowledgeService = get(),
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }

    single {
        me.rerere.rikkahub.ui.pages.setting.doctor.DoctorChecks(
            context = get(),
            settingsStore = get(),

            workflowRepository = get(),
            scheduledJobRepository = get(),
            scheduledJobRunRepository = get(),
            conversationRepository = get(),
            database = get(),
            // Pass 3: surface the browser write-tools-enabled INFO row + profile-dir AutoFix.
            browserPreferences = get(),
            // Phase 25: surface the SAF granted-directories live count.
            storageVolumeGrantStore = get(),
        )
    }
}
