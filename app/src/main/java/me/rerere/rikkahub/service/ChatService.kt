package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createKnowledgeBaseTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation

import me.rerere.rikkahub.service.buildHiddenContinuePrompt
import me.rerere.rikkahub.service.applyContinuationDedupe
import me.rerere.rikkahub.service.ContinuationDedupeConfig
import me.rerere.rikkahub.service.HiddenContinueRequestTransformer
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.GroupChatTemplate
import me.rerere.rikkahub.data.model.GroupChatSeat
import me.rerere.rikkahub.data.model.buildSeatDisplayNames
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"
/** 群聊上下文保留的最大轮数（每轮 = 所有座位各发言一次）。超过此轮数的历史消息会被裁剪。 */
// MAX_GROUP_CHAT_ROUNDS moved to template.contextRounds

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val toolApprovalPreferences: me.rerere.rikkahub.data.preferences.ToolApprovalPreferences,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val knowledgeService: me.rerere.rikkahub.data.knowledge.KnowledgeService,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)


    // ── 群聊对话映射：conversationId → templateId
    // Conversation.groupChatTemplateId 只存在于内存中，Room 不持久化它
    // 所以用这个映射单独持久化，避免 Room migration
    private val groupChatTemplateIds = ConcurrentHashMap<kotlin.uuid.Uuid, kotlin.uuid.Uuid>()
    private val groupChatPrefs = context.getSharedPreferences("group_chat_map", Context.MODE_PRIVATE)

    private fun loadGroupChatMappings() {
        groupChatTemplateIds.clear()
        val raw = groupChatPrefs.getString("mappings", null) ?: return
        try {
            val pairs = kotlinx.serialization.json.Json.decodeFromString<List<List<String>>>(raw)
            pairs.forEach { (convId, tmplId) ->
                runCatching {
                    groupChatTemplateIds[kotlin.uuid.Uuid.parse(convId)] = kotlin.uuid.Uuid.parse(tmplId)
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveGroupChatMappings() {
        val pairs = groupChatTemplateIds.entries.map { (k, v) -> listOf(k.toString(), v.toString()) }
        val raw = kotlinx.serialization.json.Json.encodeToString(pairs)
        groupChatPrefs.edit().putString("mappings", raw).apply()
    }

    fun getGroupChatTemplateId(conversationId: kotlin.uuid.Uuid): kotlin.uuid.Uuid? {
        return groupChatTemplateIds[conversationId]
    }

    private fun setGroupChatTemplateId(conversationId: kotlin.uuid.Uuid, templateId: kotlin.uuid.Uuid) {
        groupChatTemplateIds[conversationId] = templateId
        saveGroupChatMappings()
    }

    private fun removeGroupChatTemplateId(conversationId: kotlin.uuid.Uuid) {
        groupChatTemplateIds.remove(conversationId)
        saveGroupChatMappings()
    }


    init {
        loadGroupChatMappings()
    }

    // 群聊执行引擎（将在后续步骤中集成到 ChatService 内部）
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    /**
     * Per-conversation mutex serialising state-mutating operations: handleToolApproval,
     * stopGeneration, the chunk-handling save path, and explicit DB writes. Without this
     * the audit reports identified multiple write races where a fresh approval mutation
     * gets clobbered by a concurrent write from a stale snapshot. Generation chunks
     * themselves are NOT held under this mutex — only the persist boundaries.
     */
    private val sessionMutexes = ConcurrentHashMap<Uuid, Mutex>()

    /** 自动继续失败次数记录（仅用于限制死循环，不是重试次数） */
    private val continueAttempts = ConcurrentHashMap<Uuid, Int>()

    private fun mutexFor(conversationId: Uuid): Mutex =
        sessionMutexes.getOrPut(conversationId) { Mutex() }

    /**
     * Hydrate the in-memory session for [conversationId] from disk if it's currently
     * blank. Used by entry points (callback handlers, approval handlers) that may be hit
     * after a process restart with an empty session map — without this they read an
     * empty Conversation, mutate it, and `saveConversation` then OVERWRITES the persisted
     * state with empty content (silent data loss). Idempotent and cheap when the session
     * is already populated.
     */
    suspend fun ensureHydrated(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (session.state.value.messageNodes.isEmpty()) {
            val fromDb = conversationRepo.getConversationById(conversationId) ?: return
            if (fromDb.messageNodes.isNotEmpty()) {
                session.state.value = fromDb
            }
        }
    }

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update { it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution) }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
        sessionMutexes.clear()
    }.onFailure {
        // Don't let a teardown hiccup escape, but don't swallow it silently either —
        // a failure here can leave the lifecycle observer registered (slow leak).
        Log.w(TAG, "cleanup failed", it)
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            // Evict the per-conversation mutex so it doesn't accumulate forever.
            // dropSession() already removes it; removeSession() (idle eviction path)
            // was previously missing this cleanup, causing a slow leak on heavy-use
            // sessions where many conversations cycle in and out of memory.
            sessionMutexes.remove(conversationId)
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    /**
     * Force-drop the in-memory session for [conversationId] regardless of refcount /
     * generation status. Used by /new to make sure a straggler
     * coroutine writing back to the session can't resurrect the conversation after the
     * user reset it. Safe to call when no session exists — no-op.
     */
    fun dropSession(conversationId: Uuid) {
        val session = sessions.remove(conversationId) ?: return
        session.cleanup()
        sessionMutexes.remove(conversationId)
        _sessionsVersion.value++
        Log.i(TAG, "dropSession: $conversationId (remaining: ${sessions.size})")
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        groupChatSpeakerSeatIdsOverride: List<Uuid>? = null,
    ) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                // Resolve the assistant from the conversation's own assistantId, not the
                // global current-assistant pointer — otherwise switching assistants mid-
                // generation makes one conversation preprocess input with another's config.
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val withUser = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, withUser)

                // Phase 16 — fast-path router.
                val routedHandled = if (answer)
                    tryFastPathRoute(conversationId, processedContent, withUser, assistant)
                else false

                // ── Group chat check ──
                // If this conversation is linked to a group chat template, handle it
                // via the group chat engine instead of the normal single-assistant path.
                val gcTemplateId = groupChatTemplateIds[conversationId]
                val groupChatHandled = if (answer && !routedHandled && gcTemplateId != null) {
                    val settings = settingsStore.settingsFlow.first()
                    val template = settings.groupChatTemplates.find { it.id == gcTemplateId }
                    if (template != null && template.seats.isNotEmpty()) {
                        handleGroupChatMessageComplete(
                            conversationId = conversationId,
                            settings = settings,
                            conversation = withUser,
                            template = template,
                            forcedSpeakerSeatIds = groupChatSpeakerSeatIdsOverride,
                            baseMessages = withUser.messageNodes.map { it.currentMessage },
                        )
                        true
                    } else false
                } else false

                // 开始补全 — only if router didn't handle the turn and not group chat
                if (answer && !routedHandled && !groupChatHandled) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    /**
     * Phase 16 — fast-path router entry. Returns `true` if the router successfully handled
     * the turn (synthesised an assistant message and stored it) so the caller knows to skip
     * the normal LLM dispatch. Returns `false` to fall through.
     */
    private suspend fun tryFastPathRoute(
        conversationId: Uuid,
        userParts: List<UIMessagePart>,
        afterUserSave: me.rerere.rikkahub.data.model.Conversation,
        assistant: Assistant,
    ): Boolean {
        // Headless paths (cron / sub-agent / external-automation / workflow) must always go
        // through the LLM — the fast-path is a per-user-turn optimisation, not a system-flow.
        if (me.rerere.rikkahub.data.ai.tools.HeadlessConversations.isHeadless(conversationId)) return false

        // assistant is resolved from the conversation's own assistantId by the caller — do NOT
        // re-read the global getCurrentAssistant() here or a mid-turn assistant switch makes the
        // router read fastPathRouterEnabled / localTools off the wrong assistant.
        if (!assistant.fastPathRouterEnabled) return false

        val userText = userParts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }.trim()
        if (userText.isBlank()) return false

        val match = me.rerere.rikkahub.skills.FastPathRouter.route(userText) ?: return false

        // Tool list construction is non-trivial on assistants with many enabled categories
        // (allocates a fresh List<Tool> each call). Defer until AFTER a router match so the
        // common no-match path stays at a single regex scan + an early return.
        // Fast-path is gated on !isHeadless above; pass the caller context so any tools the
        // router fires inherit the right assistant id (workflows / sub-agents / etc).
        val tools = localTools.getTools(
            assistant.localTools,
            me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                callerAssistantId = assistant.id.toString(),
                callerConversationId = conversationId.toString(),
                isHeadless = false,  // gated above
            ),
        )
        val tool = tools.firstOrNull { it.name == match.toolName } ?: run {
            android.util.Log.d("FastPathRouter", "matched intent=${match.intent} but tool=${match.toolName} not registered for assistant; falling through")
            return false
        }

        // Defence-in-depth — even though v1's intent set is read-only, run HARDLINE here so
        // that adding a side-effecting intent later (e.g. "set brightness 50%") can't bypass
        // the floor by routing around the LLM-tool-call path that normally enforces it.
        val hardlineReason = me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
            .checkTool(match.toolName, match.args.toString())
        if (hardlineReason != null) {
            android.util.Log.w("FastPathRouter", "hardline-blocked intent=${match.intent} tool=${match.toolName}: $hardlineReason; falling through to LLM")
            return false
        }

        val rendered: String = try {
            val out = tool.execute(match.args)
            val rawText = out.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            val parsed = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(rawText).jsonObject
            }.getOrNull()
            val formatted = if (match.format != null && parsed != null) {
                runCatching { match.format.invoke(parsed) }
                    .onFailure { Log.w("FastPathRouter", "formatter for intent=${match.intent} threw; falling back to raw text", it) }
                    .getOrNull()
            } else null
            // Fall back to raw text if formatter throws or produces nothing.
            formatted?.takeIf { it.isNotBlank() } ?: rawText
        } catch (t: Throwable) {
            android.util.Log.w("FastPathRouter", "tool ${match.toolName} threw, falling back to LLM", t)
            me.rerere.rikkahub.skills.FastPathRouterLog.record(
                me.rerere.rikkahub.skills.FastPathRouterLog.Entry(
                    whenMs = System.currentTimeMillis(),
                    intent = match.intent,
                    toolName = match.toolName,
                    userText = userText.take(120),
                    resultPreview = "tool threw: ${t.message?.take(80)}",
                    skippedLlm = false,
                )
            )
            return false
        }

        // Inject synthetic assistant message into the conversation.
        val withAssistant = afterUserSave.copy(
            messageNodes = afterUserSave.messageNodes + UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text(rendered)),
            ).toMessageNode(),
        )
        saveConversation(conversationId, withAssistant)
        me.rerere.rikkahub.skills.FastPathRouterLog.record(
            me.rerere.rikkahub.skills.FastPathRouterLog.Entry(
                whenMs = System.currentTimeMillis(),
                intent = match.intent,
                toolName = match.toolName,
                userText = userText.take(120),
                resultPreview = rendered.take(200),
                skippedLlm = true,
            )
        )
        return true
    }

    private fun preprocessUserInputParts(
        parts: List<UIMessagePart>,
        assistant: Assistant,
    ): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value

                // Locate the message's node up front. indexOf returns -1 when the node is no
                // longer in the conversation (e.g. it was edited or removed between the tap and
                // here). Both branches index off this: the USER branch would subList(0, 0) and
                // silently wipe the conversation, and the regenerate branch builds `0..<-1`,
                // whose endInclusive is -2, which handleMessageComplete turns into
                // subList(0, -1) and crashes ("fromIndex(0) > toIndex(-1)"). Bail on not-found.
                val node = conversation.getMessageNodeByMessage(message)
                val indexAt = conversation.messageNodes.indexOf(node)
                if (indexAt < 0) {
                    Log.w(TAG, "regenerateAtMessage: node for message ${message.id} not in conversation; skipping")
                    return@launch
                }
                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        handleMessageComplete(conversationId, messageRange = 0..<indexAt)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    /** Scope of an "approve" decision. Once = this single tool call only. ChatScope =
     *  every future call of the same tool name in this conversation (until /new). Always =
     *  every future call of this tool name across the whole app, persisted to disk. */
    enum class ApprovalScope { Once, ChatScope, Always }

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
        scope: ApprovalScope = ApprovalScope.Once,
        toolName: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        val convMutex = mutexFor(conversationId)

        // Snapshot the prior generation job BEFORE the appScope.launch below replaces it
        // via setJob. session.setJob runs synchronously after launch returns; the launched
        // body is dispatched and runs LATER (Dispatchers.Main posts to the looper). So
        // calling session.getJob() inside the body would return THIS very job — and
        // cancelAndJoin would self-cancel the resume coroutine: saveConversation's first
        // suspend then throws CancellationException, the tool stays Pending, and the
        // generation never resumes. The YOLO toggle masked this because auto-approval
        // skips the Pending → handleToolApproval path entirely.
        val priorGenerationJob = session.getJob()

        // Commit the broader-scope grant on a NonCancellable scope BEFORE the cancellable
        // mutation block. Previous design ran grantAlways() inside the cancellable
        // appScope.launch — a rapid second tap would cancel the first job and silently
        // drop the persisted Always-Allow grant; the user thinks they granted it, the next
        // prompt reappears. NonCancellable + before-launch-completion guarantees the write.
        if (approved && toolName != null && scope != ApprovalScope.Once) {
            appScope.launch(NonCancellable) {
                runCatching {
                    // Smart-cast on the surrounding `if` excluded Once already, so only
                    // ChatScope and Always remain — the when is exhaustive without else.
                    when (scope) {
                        ApprovalScope.ChatScope -> me.rerere.rikkahub.data.ai.tools
                            .ToolApprovalAllowList.grantForChat(conversationId, toolName)
                        ApprovalScope.Always -> toolApprovalPreferences.grantAlways(toolName)
                        ApprovalScope.Once -> Unit
                    }
                }.onFailure { Log.w(TAG, "approval grant write failed", it) }
            }
        }

        val job = appScope.launch {
            try {
                convMutex.withLock {
                    // Hydrate from disk if the in-memory session is empty (post-restart
                    // path). Without this, the snapshot read below sees an empty
                    // Conversation and the saveConversation downstream OVERWRITES the
                    // persisted Pending tool with empty content — silent data loss.
                    ensureHydrated(conversationId)

                    // Wait for any prior generation job to actually finish writing before
                    // we read state. cancelAndJoin (vs bare cancel) closes the race where
                    // the prior coroutine emits one last chunk into `messages` between
                    // our cancel call and our state.value read. Use the SNAPSHOT taken
                    // before launch — see the comment on priorGenerationJob above.
                    priorGenerationJob?.let { runCatching { it.cancelAndJoin() } }

                    val conversation = session.state.value
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }

                    // Update the tool approval state, but only on the SPECIFIC tool that
                    // was approved AND only if it's still actually Pending. A racing
                    // /stop or a concurrent decision could have already flipped it to
                    // Denied(cancelled); we don't want to overwrite that with Approved.
                    var foundActivePending = false
                    val updatedNodes = conversation.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                                            if (part.isPending) {
                                                foundActivePending = true
                                                part.copy(approvalState = newApprovalState)
                                            } else part
                                        } else part
                                    }
                                )
                            }
                        )
                    }
                    if (!foundActivePending) {
                        // Tool was already resolved (concurrent stop / dual-surface tap /
                        // restart that hydrated a non-pending state). No-op the mutation.
                        return@withLock
                    }
                    val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                    saveConversation(conversationId, updatedConversation)

                    // Check if there are still pending tools across the conversation
                    val hasPendingTools = updatedNodes.any { node ->
                        node.currentMessage.parts.any { part ->
                            part is UIMessagePart.Tool && part.isPending
                        }
                    }

                    // Only continue generation when all pending tools are handled. Run
                    // OUTSIDE the mutex (handleMessageComplete is a long-running flow
                    // collect; holding the mutex through generation would block every
                    // subsequent state mutation for the whole turn).
                    if (!hasPendingTools) {
                        // Release the mutex via early-returning from the withLock block,
                        // then start generation. We can't `return@withLock` and then call
                        // handleMessageComplete in the same coroutine without losing the
                        // try/catch, so use a flag.
                    }
                }
                // Outside the mutex: kick off the resume generation if no tools remain pending.
                val pendingNow = session.state.value.messageNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }
                if (!pendingNow) {
                    handleMessageComplete(conversationId)
                }
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        modelOverride: Model? = null,
        continueAddendum: String? = null,
        autoContinueAttemptsRemaining: Int = 1,
        continuationDedupeConfig: ContinuationDedupeConfig? = null,
    ) {
        val settings = settingsStore.settingsFlow.first()
        // Resolve the assistant from this conversation's own assistantId — the global
        // current-assistant pointer can have moved if the user switched assistants while
        // this generation was queued (multi-assistant crosstalk). Everything downstream
        // (model, memories, tools, sender name) keys off this resolved assistant.
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = modelOverride ?: settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: throw IllegalStateException(
                "No chat model selected. Pick one in Settings → Default models, or send /model in Telegram."
            )
        // Defence against an upstream-Settings bug where disabling all providers can leave
        // the assistant's chatModelId pointing at a model whose provider has enabled=false:
        // the model lookup walks every provider regardless of state, so without this gate
        // inference fires (and bills) against the "disabled" provider's API key. Surface
        // the disabled state clearly instead of silently spending tokens.
        val resolvedProvider = model.findProvider(settings.providers)
        if (resolvedProvider == null) {
            throw IllegalStateException(
                "Selected model '${model.displayName.ifBlank { model.modelId }}' has no matching provider. " +
                    "Pick a different model in Settings or with /model."
            )
        }
        if (!resolvedProvider.enabled) {
            throw IllegalStateException(
                "Provider '${resolvedProvider.name}' is disabled — refusing to send. " +
                    "Re-enable it in Settings → Providers, or pick a different model with /model."
            )
        }

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        var latestFinishReasons: Set<String> = emptySet()

        runCatching {
            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (assistant.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // start generating
            val session = getOrCreateSession(conversationId)

            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                // Read once per call so the surface that wrote the addendum (Telegram bot,
                // anything else) gets its runtime context into the system prompt without
                // having to plumb a parameter all the way through sendMessage. Returns null
                // for in-app conversations that didn't register one.
                systemAddendum = buildString {
                    val existing = me.rerere.rikkahub.data.ai.tools
                        .ConversationSystemAddendum.get(conversationId)
                    if (!existing.isNullOrBlank()) append(existing).appendLine()

                    // 自动继续指令已由 HiddenContinueRequestTransformer 处理
                }.takeIf { it.isNotBlank() },
                isToolAutoApproved = { toolName ->
                    // YOLO mode ("I AM STUPID" toggle in Settings → Tool approvals): every
                    // tool auto-approves. User opted into this explicitly. HARDLINE still
                    // blocks rm -rf / et al — that check runs BEFORE auto-approval in
                    // GenerationHandler, so YOLO can't smuggle one through.
                    //
                    // Headless conversations (cron-driven) also auto-approve EVERY tool;
                    // the user pre-authorised the schedule itself at job-creation time
                    // and there's no UI surface to prompt at fire time.
                    //
                    // Otherwise: "Allow for this chat" (in-memory, per-conversation) OR
                    // "Always Allow" (DataStore-backed, across the whole app). The
                    // Once-grant lives in the message itself as
                    // ToolApprovalState.Approved, so it's already handled by the regular
                    // Pending → Approved transition.
                    //
                    // ask_user is a human-input request, NOT a permission gate. It must pause
                    // for the user whenever there's a surface to ask on (the in-app question card
                    // or the Telegram clarify flow), so it ignores YOLO and the allow-lists —
                    // otherwise it auto-executes its placeholder body and returns
                    // ask_user_unavailable. In a headless run (cron / sub-agent) there's nobody to
                    // answer, so it still auto-approves there and falls through to that graceful
                    // envelope instead of hanging the turn.
                    if (toolName == "ask_user") {
                        me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                            .shouldAutoApprove(conversationId)
                    } else {
                        toolApprovalPreferences.currentYolo() ||
                            me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                                .shouldAutoApprove(conversationId) ||
                            me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList
                                .isAllowedForChat(conversationId, toolName) ||
                            toolApprovalPreferences.current().contains(toolName)
                    }
                },
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                },
                assistant = assistant,
                maxSteps = 32,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                    if (continueAddendum != null) {
                        add(HiddenContinueRequestTransformer(continueAddendum))
                    }
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (assistant.enableWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    // Pass the caller context so context-aware tools (subagent_dispatch
                    // recursion guard, workflow_create authoring-id) can read the
                    // calling conversation + assistant. isHeadless is read from
                    // HeadlessConversations — true iff this is a cron / sub-agent /
                    // workflow / external-automation flow.
                    val invocationCtx = me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                        callerAssistantId = assistant.id.toString(),
                        callerConversationId = conversationId.toString(),
                        isHeadless = me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                            .isHeadless(conversationId),
                        // show_image keys its result envelope off this — a text-only model
                        // gets told it cannot see the image instead of confabulating one.
                        modelCanSeeImages = Modality.IMAGE in model.inputModalities,
                    )
                    addAll(localTools.getTools(assistant.localTools, invocationCtx))
                    if (assistant.enableRecentChatsReference) {
                        addAll(createConversationTools(conversationRepo, assistant.id))
                    }
                    addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
                    if (assistant.enabledKnowledgeBaseIds.isNotEmpty()) {
                        addAll(createKnowledgeBaseTools(knowledgeService))
                    }
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                                skillManager = skillManager,
                            )
                        )
                    }
                    mcpManager.getAllAvailableTools().also { allTools ->
                        // Upstream name validation: a server name that isn't pure
                        // English+digits would produce an invalid `mcp__<name>__tool`
                        // surface, so surface it as an error rather than emit a tool the
                        // model can't address.
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
                        // Namespace MCP tools by a server-id slug so two enabled servers that
                        // each expose a tool of the same name don't collide (which would 400 or
                        // mis-route to whichever server registered last). Keep the `mcp__` prefix
                        // intact: HardlineCommandGuard and ToolApprovalDefaults both branch on
                        // `startsWith("mcp__")`. The slug is the first 8 hex chars of the id with
                        // dashes stripped; the validated server name follows for human-readable
                        // disambiguation, keeping the name within the 64-char /
                        // ^[a-zA-Z0-9_-]+$ limit. The execute lambda below still calls callTool
                        // with the REAL tool.name, since the namespacing exists only on the
                        // model-facing surface.
                        val serverSlug = serverId.toString().take(8).replace("-", "")
                        val mcpToolName = "mcp__" + serverSlug + "_" + serverName + "__" + tool.name
                        add(
                            Tool(
                                name = mcpToolName,
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                // MCP tools default to NO approval — the per-tool `needsApproval`
                                // flag (settable in Settings → MCP → Tools tab, defaults to false)
                                // is the single source of truth. The user can flip individual MCP
                                // tools to require approval when they're known to be destructive.
                                // HARDLINE still applies via HardlineCommandGuard's `mcp__*` branch,
                                // which scans every string arg for shell-content patterns
                                // (rm -rf /, mkfs, shutdown, encoded payloads).
                                needsApproval = {
                                    me.rerere.rikkahub.data.ai.tools
                                        .ToolApprovalDefaults.requiresApproval(mcpToolName) ||
                                        tool.needsApproval
                                },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                },
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
        }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        if (chunk.finishReasons.isNotEmpty()) {
                            latestFinishReasons = chunk.finishReasons
                        }

                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)

                        // Persist immediately when a tool transitions to "execution
                        // started but no output yet" — this writes the executionStartedAt
                        // breadcrumb to disk so a process kill mid-execute leaves a clear
                        // signal for the next replay (see GenerationHandler.kt's replay
                        // safety pass: Approved + executionStartedAt + empty → Denied
                        // interrupted_unknown_outcome). Without this, the marker stays in
                        // memory only and replay can't distinguish "freshly approved,
                        // never tried" from "interrupted mid-execute" → silent re-run.
                        val needsImmediatePersist = chunk.messages.lastOrNull()?.parts?.any { p ->
                            p is UIMessagePart.Tool &&
                                p.executionStartedAt != null &&
                                p.output.isEmpty() &&
                                p.approvalState is ToolApprovalState.Approved
                        } ?: false
                        if (needsImmediatePersist) {
                            saveConversation(conversationId, updatedConversation)
                        }

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure { error ->
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

            // Persist the in-memory snapshot so the Auto/Pending → Denied transitions
            // GenerationHandler did inside its try/catch (the "generation_failed" recovery
            // path) survive a process restart. Without this, the failure path only
            // updates memory and the persisted DB row keeps the stale Pending state
            // forever — replay would re-run the loop against unrecoverable shape.
            runCatching {
                val final = getConversationFlow(conversationId).value
                saveConversation(conversationId, final)
            }.onFailure { saveErr ->
                Log.w(TAG, "handleMessageComplete: failure-path save failed", saveErr)
            }

            // ═══ 自动继续逻辑（FLIT 风格：隐藏指令 + 去重） ═══
            // 保留失败的回复，注入隐藏 continue 指令让 LLM 从断点继续
            if (assistant.autoContinueOnError) {
                val attemptCount = continueAttempts.getOrDefault(conversationId, 0)
                if (attemptCount < assistant.maxContinueCount) {
                    continueAttempts[conversationId] = attemptCount + 1
                    Log.i(TAG, "autoContinue (error): attempt ${attemptCount + 1}/${assistant.maxContinueCount} for $conversationId")

                    // 解析继续模型（如果指定了）
                    val continueModel = if (assistant.continueModelId != null) {
                        settings.findModelById(assistant.continueModelId)
                    } else null

                    // 取最后一条 assistant 消息的文本，作为继续的上下文
                    val lastAssistantText = getConversationFlow(conversationId).value
                        .currentMessages
                        .lastOrNull { it.role == MessageRole.ASSISTANT }
                        ?.toText()
                        ?.trim()
                        .orEmpty()

                    val continuePrompt = if (lastAssistantText.isNotBlank()) {
                        buildHiddenContinuePrompt(
                            previousAssistantText = lastAssistantText,
                        )
                    } else {
                        """
                        |Continue from where you left off.
                        |Do NOT repeat what you already wrote.
                        |Do NOT re-execute tools that have already completed successfully.
                        |Error: ${error.message?.take(200) ?: error.javaClass.simpleName}
                        """.trimMargin()
                    }

                    // 保留失败的回复，注入隐藏继续指令
                    handleMessageComplete(
                        conversationId,
                        modelOverride = continueModel,
                        continueAddendum = continuePrompt,
                        autoContinueAttemptsRemaining = 0,
                        continuationDedupeConfig = if (lastAssistantText.isNotBlank()) {
                            val lastMsg = getConversationFlow(conversationId).value
                                .currentMessages
                                .lastOrNull { it.role == MessageRole.ASSISTANT }
                            if (lastMsg != null) {
                                ContinuationDedupeConfig(
                                    targetMessageId = lastMsg.id,
                                    originalText = lastAssistantText,
                                )
                            } else null
                        } else null,
                    )
                    return@onFailure
                }
            }

            // 自动继续未开启或死循环防护触发 → 正常报错
            continueAttempts.remove(conversationId)
            error.printStackTrace()
            addError(error, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $error")
            Logging.log(TAG, error.stackTraceToString())
        }.onSuccess {
            // 生成成功，清除继续计数器
            continueAttempts.remove(conversationId)

            var finalConversation = getConversationFlow(conversationId).value

            // 去重：如果这是自动继续的结果，移除 LLM 可能重复的原文开头
            val dedupeConfig = continuationDedupeConfig
            if (dedupeConfig != null) {
                val deduped = applyContinuationDedupe(finalConversation, dedupeConfig)
                if (deduped != finalConversation) {
                    finalConversation = deduped
                    updateConversation(conversationId, deduped)
                }
            }

            saveConversation(conversationId, finalConversation)

            // 检测截断并自动继续（基于 finishReasons，来自 FLIT 逻辑）
            val shouldAutoContinue = autoContinueAttemptsRemaining > 0 &&
                assistant.autoContinueOnError &&
                latestFinishReasons.any { reason ->
                    when (reason.trim().lowercase(java.util.Locale.US)) {
                        "length", "max_tokens", "max_output_tokens",
                        "max_tokens_exceeded", "token_limit_reached" -> true
                        else -> false
                    }
                }
            if (shouldAutoContinue) {
                val lastMessage = finalConversation.currentMessages.lastOrNull()
                if (lastMessage != null && lastMessage.role == MessageRole.ASSISTANT) {
                    val text = lastMessage.toText().trim()
                    if (text.isNotBlank()) {
                        val attemptCount = continueAttempts.getOrDefault(conversationId, 0)
                        if (attemptCount < assistant.maxContinueCount) {
                            continueAttempts[conversationId] = attemptCount + 1
                            Log.i(TAG, "autoContinue (truncation): attempt ${attemptCount + 1}/${assistant.maxContinueCount} for $conversationId, reasons=$latestFinishReasons")

                            val continueModel = if (assistant.continueModelId != null) {
                                settings.findModelById(assistant.continueModelId)
                            } else null

                            val continuePrompt = buildHiddenContinuePrompt(
                                previousAssistantText = text,
                            )

                            handleMessageComplete(
                                conversationId,
                                modelOverride = continueModel,
                                autoContinueAttemptsRemaining = autoContinueAttemptsRemaining - 1,
                                continuationDedupeConfig = ContinuationDedupeConfig(
                                    targetMessageId = lastMessage.id,
                                    originalText = text,
                                ),
                            )
                            return@onSuccess
                        }
                    }
                }
            }

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }

        // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId) ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching
            // Same defence as handleLlmTurn: don't burn tokens on a disabled provider.
            if (!provider.enabled) return@runCatching

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.message.toText().trim())
                )
            }
        }.onFailure {
            // Title generation is auxiliary — a failure here doesn't block the chat
            // and surfaces visibly as a blank conversation title in the list. Don't
            // push it onto the user-facing error stream: when the title model 429s,
            // the next message sees title.isBlank()==true, tries again, 429s again,
            // and the user gets a popup per message until they switch models. Match
            // the generateSuggestion pattern (log only) to keep the surface quiet.
            Log.w(TAG, "generateTitle failed", it)
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return@runCatching
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching
            // Same defence as handleLlmTurn: don't burn tokens on a disabled provider.
            if (!provider.enabled) return@runCatching

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            // Suggestion generation is auxiliary — log only, don't push onto the
            // user-facing error stream (mirrors the generateTitle failure handling).
            Log.w(TAG, "generateSuggestion failed", it)
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")
        // Same defence as handleLlmTurn — refuse to compress against a disabled provider.
        if (!provider.enabled) {
            throw IllegalStateException(
                "Provider '${provider.name}' is disabled — cannot compress. " +
                    "Re-enable it in Settings → Providers, or set a different compression model."
            )
        }

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )

            return result.message.toText().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Create new conversation with compressed history as multiple user messages + kept messages
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }



    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        // Atomic compare-and-set via StateFlow.update so two concurrent writers can't
        // race on read-modify-write (each reading the SAME pre-state and overwriting
        // each other). Also routes through checkFilesDelete so attached files keep
        // being garbage-collected when removed from the conversation.
        val session = getOrCreateSession(conversationId)
        session.state.update { current ->
            val next = update(current)
            if (next.id != conversationId) current
            else {
                checkFilesDelete(next, current)
                next
            }
        }
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }
        // Refuse to overwrite a non-empty stored row with an empty in-memory snapshot.
        // This is the silent-data-loss guard: handleToolApproval / stopGeneration / etc.
        // could be called against an unhydrated session (post-restart), build an empty
        // updatedConversation, and call saveConversation. Without this guard we'd wipe
        // the Pending tool the user was trying to approve.
        if (exists && conversation.messageNodes.isEmpty()) {
            val storedHasContent = runCatching {
                conversationRepo.getConversationById(conversation.id)?.messageNodes?.isNotEmpty() == true
            }.getOrDefault(false)
            if (storedHasContent) {
                Log.w(TAG, "saveConversation: refusing to overwrite non-empty $conversationId with empty snapshot — likely an unhydrated session")
                return
            }
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
        )

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    /**
     * 创建并启动群聊对话
     *
     * 创建一个新的 Conversation，关联到指定群聊模板，并自动触发首轮发言
     *
     * @param templateId 群聊模板 ID
     * @param userMessage 用户发送的第一条消息
     * @return 新创建的对话 ID
     */
    suspend fun startGroupChatConversation(
        templateId: kotlin.uuid.Uuid,
        userMessage: List<UIMessagePart> = emptyList(),
    ): kotlin.uuid.Uuid {
        val settings = settingsStore.settingsFlow.first()
        val template = settings.groupChatTemplates.find { it.id == templateId }
            ?: throw IllegalArgumentException("Group chat template not found: $templateId")

        val conversationId = kotlin.uuid.Uuid.random()
        val firstAssistantId = template.seats.firstOrNull()?.assistantId
            ?: settings.getCurrentAssistant().id

        // 将 templateId 存入独立映射（Room 不持久化 groupChatTemplateId，避免 migration）
        setGroupChatTemplateId(conversationId, templateId)

        // 创建对话
        val conversation = Conversation(
            id = conversationId,
            assistantId = firstAssistantId,
            title = "",
            groupChatTemplateId = templateId,
            messageNodes = emptyList(),
        )
        saveConversation(conversationId, conversation)

        // 生成标题
        launchWithConversationReference(conversationId) {
            generateTitle(conversationId, conversation)
        }

        return conversationId
    }


    // ════════════════════════════════════════════════════════════════
    // 群聊执行引擎（移植自 FLIT，增强版：支持工具/记忆/互怼/座位覆写）
    // ════════════════════════════════════════════════════════════════

    /**
     * 将座位覆写应用到助手配置上。
     * 支持：chatModelId、reasoningLevel、maxTokens、searchMode、mcpServers、
     * memoryEnabled、systemPrompt 等覆写。
     */
    private fun applySeatOverrides(
        assistant: me.rerere.rikkahub.data.model.Assistant,
        overrides: me.rerere.rikkahub.data.model.GroupChatSeatOverrides,
        systemPromptSuffix: String? = null,
    ): me.rerere.rikkahub.data.model.Assistant {
        val basePrompt = overrides.systemPrompt ?: assistant.systemPrompt
        val updatedPrompt = systemPromptSuffix?.let { suffix ->
            if (suffix.isBlank()) basePrompt else basePrompt + suffix
        } ?: basePrompt

        return assistant.copy(
            chatModelId = overrides.chatModelId ?: assistant.chatModelId,
            reasoningLevel = overrides.reasoningLevel ?: assistant.reasoningLevel,
            maxTokens = overrides.maxTokens ?: assistant.maxTokens,
            enableWebSearch = overrides.searchEnabled && (overrides.searchMode != me.rerere.rikkahub.data.model.AssistantSearchMode.Off),
            mcpServers = overrides.mcpServerIds,
            enableMemory = overrides.memoryEnabled && assistant.enableMemory,
            systemPrompt = updatedPrompt,
        )
    }

    /**
     * 群聊消息完成处理（增强版：支持工具调用、记忆注入、座位覆写、互怼）
     *
     * 当用户向群聊对话发送消息时，由 sendMessage 检测到 gcTemplateId 后调用此方法。
     * 确定发言人 → 逐一生成回复（带工具支持）→ 座位间互怼 → 添加到对话中。
     */
    private suspend fun handleGroupChatMessageComplete(
        conversationId: Uuid,
        settings: Settings,
        conversation: Conversation,
        template: GroupChatTemplate,
        forcedSpeakerSeatIds: List<Uuid>? = null,
        baseMessages: List<UIMessage>,
    ) {
        if (template.seats.isEmpty()) return
        val seatsById = template.seats.associateBy { it.id }

        val lastUserText = baseMessages
            .lastOrNull { it.role == MessageRole.USER }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            ?.trim()
            .orEmpty()

        val recentAssistantMessages = run {
            val lastUserIndex = baseMessages.indexOfLast { it.role == MessageRole.USER }
            if (lastUserIndex <= 0) return@run emptyList<UIMessage>()
            baseMessages
                .take(lastUserIndex)
                .asReversed()
                .filter { message -> message.role == MessageRole.ASSISTANT }
                .take(2)
                .reversed()
        }

        val mentionedSeatIds = resolveMentionedSeatIds(
            text = lastUserText,
            settings = settings,
            template = template,
        )

        val forcedSeatIds = forcedSpeakerSeatIds
            ?.filter { seatId -> seatsById.containsKey(seatId) }
            ?.distinct()
        val hasExplicitSpeakerOrder = !forcedSeatIds.isNullOrEmpty() || mentionedSeatIds.isNotEmpty()
        val speakerSeatIds = when {
            !forcedSeatIds.isNullOrEmpty() -> forcedSeatIds
            mentionedSeatIds.isNotEmpty() -> mentionedSeatIds
            else -> routeGroupChatSpeakers(
                settings = settings,
                template = template,
                userText = lastUserText,
                recentAssistantMessages = recentAssistantMessages,
            )
        }

        if (speakerSeatIds.isEmpty()) return

        val resolvedSpeakers = speakerSeatIds
            .asSequence()
            .distinct()
            .mapNotNull { seatId -> seatsById[seatId] }
            .toList()
            .let { seats ->
                if (hasExplicitSpeakerOrder) seats else seats.shuffled()
            }

        if (resolvedSpeakers.isEmpty()) return

        var runningMessages = baseMessages
        val baseMessageCount = baseMessages.size
        val speakersGenerated = mutableListOf<GroupChatSeat>()

        // ── 阶段 1：为每个座位生成回复 ──
        for (seat in resolvedSpeakers) {
            val assistant = settings.assistants.firstOrNull { it.id == seat.assistantId } ?: continue
            val model = settings.findModelById(
                seat.overrides.chatModelId ?: assistant.chatModelId ?: settings.chatModelId
            ) ?: continue
            val provider = model.findProvider(settings.providers) ?: continue
            if (!provider.enabled) continue

            // 应用座位覆写
            val groupContextSuffix = buildGroupChatContextSystemPromptSuffix(
                settings = settings,
                template = template,
                seat = seat,
                assistant = assistant,
            )
            val seatAssistant = applySeatOverrides(assistant, seat.overrides, groupContextSuffix)

            // 构建座位级记忆
            val seatMemories = if (seatAssistant.enableMemory) {
                if (seatAssistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(seatAssistant.id.toString())
                }
            } else {
                null
            }

            val contextForSeat = if (runningMessages.size > baseMessageCount + template.contextRounds * template.seats.size) {
                val seatDisplayNames = template.buildSeatDisplayNames(
                    assistantsById = settings.assistants.associateBy { it.id },
                    defaultName = "Assistant",
                )
                val trimmedHistory = trimGroupChatContextForSeat(
                    messages = baseMessages,
                    seatId = seat.id,
                    template = template,
                    seatDisplayNames = seatDisplayNames,
                    maxRounds = template.contextRounds,
                )
                val currentRoundResponses = runningMessages.drop(baseMessageCount)
                trimmedHistory + currentRoundResponses
            } else {
                runningMessages
            }

            val promptMessages = buildGroupChatPromptMessagesForSeat(
                messages = contextForSeat,
                settings = settings,
                template = template,
                seatId = seat.id,
                selfAssistantId = assistant.id,
            )

            val displayName = buildSeatDisplayName(template, seat, settings)

            // 构建座位级工具列表
            val seatTools = buildList {
                if (seatAssistant.enableWebSearch) {
                    addAll(createSearchTools(settings))
                }
                // 工作区工具
                addAll(createWorkspaceToolsIfReady(seatAssistant.workspaceId?.toString(), null))
                // 技能工具
                if (seatAssistant.enabledSkills.isNotEmpty()) {
                    addAll(
                        createSkillTools(
                            enabledSkills = seatAssistant.enabledSkills,
                            allSkills = skillManager.listSkills(),
                            skillManager = skillManager,
                        )
                    )
                }
                // MCP 工具（按座位助手过滤）
                if (seatAssistant.mcpServers.isNotEmpty()) {
                    mcpManager.getAllAvailableTools().forEach { (serverId, serverName, tool) ->
                        val serverSlug = serverId.toString().take(8).replace("-", "")
                        val mcpToolName = "mcp__" + serverSlug + "_" + serverName + "__" + tool.name
                        add(
                            Tool(
                                name = mcpToolName,
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                needsApproval = { tool.needsApproval },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                }
            }

            // 创建空消息占位，后续流式更新
            val msgId = Uuid.random()
            updateConversationState(conversationId) { conv ->
                conv.copy(
                    messageNodes = conv.messageNodes + UIMessage(
                        id = msgId,
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("")),
                    ).toMessageNode().copy(senderName = displayName)
                )
            }

            try {
                // 使用 generationHandler.generateText（支持工具调用、记忆注入、流式输出）
                generationHandler.generateText(
                    settings = settings,
                    model = model,
                    messages = promptMessages,
                    assistant = seatAssistant,
                    memories = seatMemories,
                    tools = seatTools,
                    maxSteps = if (seatTools.isNotEmpty()) 32 else 1,
                    inputTransformers = buildList {
                        addAll(inputTransformers)
                        add(templateTransformer)
                    },
                    outputTransformers = outputTransformers,
                    // 群聊中自动批准所有工具（无 UI 交互）
                    isToolAutoApproved = { true },
                ).collect { chunk ->
                    when (chunk) {
                        is GenerationChunk.Messages -> {
                            // 提取新增的 assistant 消息文本
                            val appendedMessages = chunk.messages.drop(promptMessages.size)
                            val text = appendedMessages
                                .filter { it.role == MessageRole.ASSISTANT }
                                .joinToString("\n") { it.toText() }
                                .trim()

                            if (text.isNotEmpty()) {
                                updateConversationState(conversationId) { conv ->
                                    conv.copy(
                                        messageNodes = conv.messageNodes.map { node ->
                                            if (node.currentMessage.id == msgId) {
                                                node.copy(
                                                    messages = node.messages.map { msg ->
                                                        if (msg.id == msgId) {
                                                            msg.copy(parts = listOf(UIMessagePart.Text(text)))
                                                        } else msg
                                                    }
                                                )
                                            } else node
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 生成完成，获取最终文本
                val finalText = getConversationFlow(conversationId).value
                    .messageNodes
                    .lastOrNull { it.currentMessage.id == msgId }
                    ?.currentMessage
                    ?.toText()
                    ?.trim()
                    .orEmpty()

                if (finalText.isNotBlank() && finalText != "[生成失败]") {
                    // 更新 runningMessages 供后续座位参考（嵌入发言人信息）
                    runningMessages = runningMessages + UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text(
                            "[$displayName]\n$finalText"
                        )),
                    )
                    speakersGenerated.add(seat)
                }
            } catch (e: CancellationException) {
                // 清理空消息
                updateConversationState(conversationId) { conv ->
                    conv.copy(
                        messageNodes = conv.messageNodes.filterNot { it.currentMessage.id == msgId }
                    )
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Group chat seat ${displayName} failed", e)
                // 标记失败并继续
                updateConversationState(conversationId) { conv ->
                    conv.copy(
                        messageNodes = conv.messageNodes.map { node ->
                            if (node.currentMessage.id == msgId) {
                                node.copy(
                                    messages = node.messages.map { msg ->
                                        if (msg.id == msgId) {
                                            msg.copy(parts = listOf(UIMessagePart.Text("[生成失败]")))
                                        } else msg
                                    }
                                )
                            } else node
                        }
                    )
                }
            }
        }

        // ── 阶段 2：座位间互怼 ──
        // 仅当有 2 个以上座位生成过回复时才触发互怼
        if (speakersGenerated.size >= 2) {
            val speakerIndexBySeatId = speakersGenerated
                .mapIndexed { index, seat -> seat.id to index }
                .toMap()

            val speakerPrimaryTextBySeatId = speakersGenerated.associate { seat ->
                val primaryMessage = runningMessages.lastOrNull { message ->
                    message.role == MessageRole.USER && message.toText().contains("[${buildSeatDisplayName(template, seat, settings)}]")
                }
                seat.id to (primaryMessage?.toText().orEmpty())
            }

            val disagreementMarkers = listOf(
                "我不同意", "不同意", "不认同", "反对", "有误", "不对", "错误", "不准确",
                "i disagree", "disagree with", "that's wrong", "that's incorrect",
                "incorrect", "not correct",
            )
            val otherAssistantReferenceMarkers = listOf(
                "上面", "前面", "上一位", "前一个", "刚才", "其他助手", "另一位助手",
                "another assistant", "other assistant", "previous assistant", "above",
            )

            fun hasExplicitDisagreement(text: String): Boolean {
                val normalized = text.lowercase(java.util.Locale.ROOT)
                return disagreementMarkers.any { marker -> normalized.contains(marker) }
            }

            fun shouldInterReplyToPreviousSpeaker(
                text: String,
                previousSeat: GroupChatSeat,
                mentionedSeatIds: Set<Uuid>,
            ): Boolean {
                if (!hasExplicitDisagreement(text)) return false
                if (previousSeat.id in mentionedSeatIds) return true
                val previousName = settings.getAssistantById(previousSeat.assistantId)?.name?.trim().orEmpty()
                val normalized = text.lowercase(java.util.Locale.ROOT)
                if (previousName.isNotBlank() && normalized.contains(previousName.lowercase(java.util.Locale.ROOT))) return true
                if (otherAssistantReferenceMarkers.any { marker -> normalized.contains(marker) }) return true
                return false
            }

            val interReplyPairs = buildList {
                val usedPairKeys = mutableSetOf<Pair<Uuid, Uuid>>()
                val usedReplySpeakerSeatIds = mutableSetOf<Uuid>()

                // 1) @Name 提及：如果某个助手显式 @ 了另一个，被提及者回复
                for (index in speakersGenerated.indices) {
                    if (size >= 3) break
                    val replyToSeat = speakersGenerated[index]
                    val replyToText = speakerPrimaryTextBySeatId[replyToSeat.id].orEmpty()
                    if (replyToText.isBlank()) continue

                    val mentionedSeatIds = resolveMentionedSeatIds(
                        text = replyToText,
                        settings = settings,
                        template = template,
                    ).filter { seatId -> seatId != replyToSeat.id && seatsById.containsKey(seatId) }
                        .distinct()

                    mentionedSeatIds.forEach { mentionedSeatId ->
                        if (size >= 3) return@forEach
                        val speakerSeat = seatsById[mentionedSeatId] ?: return@forEach
                        val key = speakerSeat.id to replyToSeat.id
                        if (key in usedPairKeys) return@forEach
                        if (speakerSeat.id in usedReplySpeakerSeatIds) return@forEach
                        add(speakerSeat to replyToSeat)
                        usedPairKeys.add(key)
                        usedReplySpeakerSeatIds.add(speakerSeat.id)
                    }
                }

                // 2) 明确分歧：前一个发言被后一个反驳时，前一个回复
                for (index in 1 until speakersGenerated.size) {
                    if (size >= 3) break
                    val currentSeat = speakersGenerated[index]
                    val previousSeat = speakersGenerated[index - 1]
                    val currentText = speakerPrimaryTextBySeatId[currentSeat.id].orEmpty()
                    if (currentText.isBlank()) continue

                    val mentionedSeatIds = resolveMentionedSeatIds(
                        text = currentText,
                        settings = settings,
                        template = template,
                    ).toSet()

                    if (!shouldInterReplyToPreviousSpeaker(currentText, previousSeat, mentionedSeatIds)) continue

                    val speakerSeat = seatsById[previousSeat.id] ?: continue
                    val key = speakerSeat.id to currentSeat.id
                    if (key in usedPairKeys) continue
                    if (speakerSeat.id in usedReplySpeakerSeatIds) continue
                    add(speakerSeat to currentSeat)
                    usedPairKeys.add(key)
                    usedReplySpeakerSeatIds.add(speakerSeat.id)
                }
            }

            var remainingInterReplies = 3
            for ((speaker, replyTo) in interReplyPairs) {
                if (remainingInterReplies <= 0) break

                val speakerAssistant = settings.assistants.firstOrNull { it.id == speaker.assistantId } ?: continue
                val replyToAssistant = settings.assistants.firstOrNull { it.id == replyTo.assistantId }

                val speakerModel = settings.findModelById(
                    speaker.overrides.chatModelId ?: speakerAssistant.chatModelId ?: settings.chatModelId
                ) ?: continue

                val replyToName = replyToAssistant?.name?.ifBlank { "another assistant" } ?: "another assistant"
                val systemPromptSuffix = buildString {
                    append("\n\n")
                    append("You are now replying to ")
                    append(replyToName)
                    append(". Do not address the user. Keep it concise.")
                }

                val interGroupContextSuffix = buildGroupChatContextSystemPromptSuffix(
                    settings = settings,
                    template = template,
                    seat = speaker,
                    assistant = speakerAssistant,
                )
                val interSeatAssistant = applySeatOverrides(speakerAssistant, speaker.overrides, interGroupContextSuffix + systemPromptSuffix)

                val interMemories = if (interSeatAssistant.enableMemory) {
                    if (interSeatAssistant.useGlobalMemory) {
                        memoryRepository.getGlobalMemories()
                    } else {
                        memoryRepository.getMemoriesOfAssistant(interSeatAssistant.id.toString())
                    }
                } else {
                    null
                }

                val interContextForSeat = if (runningMessages.size > baseMessageCount + template.contextRounds * template.seats.size) {
                    val seatDisplayNames = template.buildSeatDisplayNames(
                        assistantsById = settings.assistants.associateBy { it.id },
                        defaultName = "Assistant",
                    )
                    val trimmedHistory = trimGroupChatContextForSeat(
                        messages = baseMessages,
                        seatId = speaker.id,
                        template = template,
                        seatDisplayNames = seatDisplayNames,
                        maxRounds = template.contextRounds,
                    )
                    val currentRoundResponses = runningMessages.drop(baseMessageCount)
                    trimmedHistory + currentRoundResponses
                } else {
                    runningMessages
                }

                val interPromptMessages = buildGroupChatPromptMessagesForSeat(
                    messages = interContextForSeat,
                    settings = settings,
                    template = template,
                    seatId = speaker.id,
                    selfAssistantId = speakerAssistant.id,
                )

                val interDisplayName = buildSeatDisplayName(template, speaker, settings)

                val interMsgId = Uuid.random()
                updateConversationState(conversationId) { conv ->
                    conv.copy(
                        messageNodes = conv.messageNodes + UIMessage(
                            id = interMsgId,
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Text("")),
                        ).toMessageNode().copy(senderName = interDisplayName)
                    )
                }

                try {
                    generationHandler.generateText(
                        settings = settings,
                        model = speakerModel,
                        messages = interPromptMessages,
                        assistant = interSeatAssistant,
                        memories = interMemories,
                        tools = emptyList(),
                        maxSteps = 1,
                        inputTransformers = buildList {
                            addAll(inputTransformers)
                            add(templateTransformer)
                        },
                        outputTransformers = outputTransformers,
                        isToolAutoApproved = { true },
                    ).collect { chunk ->
                        when (chunk) {
                            is GenerationChunk.Messages -> {
                                val appendedMessages = chunk.messages.drop(interPromptMessages.size)
                                val text = appendedMessages
                                    .filter { it.role == MessageRole.ASSISTANT }
                                    .joinToString("\n") { it.toText() }
                                    .trim()
                                if (text.isNotEmpty()) {
                                    updateConversationState(conversationId) { conv ->
                                        conv.copy(
                                            messageNodes = conv.messageNodes.map { node ->
                                                if (node.currentMessage.id == interMsgId) {
                                                    node.copy(
                                                        messages = node.messages.map { msg ->
                                                            if (msg.id == interMsgId) {
                                                                msg.copy(parts = listOf(UIMessagePart.Text(text)))
                                                            } else msg
                                                        }
                                                    )
                                                } else node
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val interFinalText = getConversationFlow(conversationId).value
                        .messageNodes
                        .lastOrNull { it.currentMessage.id == interMsgId }
                        ?.currentMessage
                        ?.toText()
                        ?.trim()
                        .orEmpty()

                    if (interFinalText.isNotBlank()) {
                        runningMessages = runningMessages + UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(UIMessagePart.Text(
                                "[$interDisplayName]\n$interFinalText"
                            )),
                        )
                        remainingInterReplies -= 1
                    }
                } catch (e: CancellationException) {
                    updateConversationState(conversationId) { conv ->
                        conv.copy(
                            messageNodes = conv.messageNodes.filterNot { it.currentMessage.id == interMsgId }
                        )
                    }
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Group chat inter-reply ${interDisplayName} failed", e)
                }
            }
        }

        // 保存对话
        saveConversation(conversationId, getConversationFlow(conversationId).value)
        // 生成标题和推荐（与普通对话的 .onSuccess 逻辑一致）
        launchWithConversationReference(conversationId) {
            generateTitle(conversationId, getConversationFlow(conversationId).value)
        }
        launchWithConversationReference(conversationId) {
            generateSuggestion(conversationId, getConversationFlow(conversationId).value)
        }
    }

    /**
     * 构建群聊上下文的系统提示后缀
     */
    private fun buildGroupChatContextSystemPromptSuffix(
        settings: Settings,
        template: GroupChatTemplate,
        seat: GroupChatSeat,
        assistant: me.rerere.rikkahub.data.model.Assistant,
    ): String {
        val templateName = template.name.trim().ifBlank { "Group Chat" }
        val assistantsById = settings.assistants.associateBy { it.id }
        val seatDisplayNames = template.buildSeatDisplayNames(
            assistantsById = assistantsById,
            defaultName = "Assistant",
        )
        val memberNames = template.seats.mapNotNull { memberSeat ->
            seatDisplayNames[memberSeat.id]?.trim()?.takeIf { it.isNotBlank() }
        }

        val membersLine = when {
            memberNames.isEmpty() -> "unknown"
            else -> memberNames.joinToString(", ")
        }

        val selfName = seatDisplayNames[seat.id]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: assistant.name.trim().ifBlank { "Assistant" }
        val seatIndex = template.seats.indexOfFirst { it.id == seat.id }.takeIf { it >= 0 }?.plus(1)
        val seatLabel = seatIndex?.let { index -> "Seat $index" } ?: "Seat"

        return buildString {
            append("\n\n")
            appendLine("You are in a group chat.")
            appendLine("Group: $templateName")
            template.intro.trim()
                .takeIf { it.isNotBlank() }
                ?.let { intro ->
                    appendLine("Group intro: $intro")
                }
            appendLine("Members: $membersLine")
            appendLine("You are $selfName ($seatLabel).")
            appendLine("Keep your own style/persona; do not imitate other assistants.")
            appendLine("You can call out other assistants with @Name or @Name#2 when truly needed (no # means #1), but do it sparingly.")
            appendLine("Messages from the human user are provided as USER messages prefixed with [Message from ... (user)].")
            appendLine("Messages from other assistants may be provided as USER messages prefixed with [Message from ... (assistant)]. They are NOT from the human user; treat them as context only.")
            appendLine("When generating a normal reply, address the human user (unless later instructions explicitly tell you to reply to another assistant).")
        }
    }

    /**
     * 构建群聊座位的提示消息列表
     */
    private fun buildGroupChatPromptMessagesForSeat(
        messages: List<UIMessage>,
        settings: Settings,
        template: GroupChatTemplate,
        seatId: Uuid,
        selfAssistantId: Uuid,
    ): List<UIMessage> {
        val assistantsById = settings.assistants.associateBy { it.id }
        val seatDisplayNames = template.buildSeatDisplayNames(
            assistantsById = assistantsById,
            defaultName = "Assistant",
        )

        return messages.map { message ->
            when (message.role) {
                MessageRole.USER -> {
                    // 检查是否是群聊中其他助手的消息
                    val speakerName = resolveGroupChatMessageSpeakerName(
                        message = message,
                        seatDisplayNames = seatDisplayNames,
                        assistantsById = assistantsById,
                    )
                    if (speakerName != null) {
                        UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(UIMessagePart.Text(
                                "[Message from $speakerName (assistant)]\n${message.toText()}"
                            )),
                        )
                    } else {
                        message
                    }
                }
                MessageRole.ASSISTANT -> message
                else -> message
            }
        }
    }

    /**
     * 解析消息中的发言人名称
     */
    private fun resolveGroupChatMessageSpeakerName(
        message: UIMessage,
        seatDisplayNames: Map<Uuid, String>,
        assistantsById: Map<Uuid, me.rerere.rikkahub.data.model.Assistant>,
    ): String? {
        val text = message.toText()
        return seatDisplayNames.entries.firstOrNull { (_, name) ->
            text.contains("[$name]")
        }?.value
    }

    /**
     * 构建座位显示名称
     */
    private fun buildSeatDisplayName(
        template: GroupChatTemplate,
        seat: GroupChatSeat,
        settings: Settings,
    ): String {
        val assistant = settings.assistants.firstOrNull { it.id == seat.assistantId }
        val baseName = assistant?.name?.ifBlank { null } ?: "Assistant"
        return if (seat.instanceNumber > 1) "$baseName#${seat.instanceNumber}" else baseName
    }

    /**
     * 路由群聊发言人
     * 使用路由模型决定哪几个座位应该发言
     */
    private suspend fun routeGroupChatSpeakers(
        settings: Settings,
        template: GroupChatTemplate,
        userText: String,
        recentAssistantMessages: List<UIMessage>,
    ): List<Uuid> {
        val enabledSeats = template.seats.filter { it.defaultEnabled }
        if (enabledSeats.isEmpty()) return emptyList()

        // 没有路由模型则返回前 3 个座位
        val hostModelId = template.hostModelId ?: return enabledSeats.take(3).map { it.id }
        val hostModel = settings.findModelById(hostModelId) ?: return enabledSeats.take(3).map { it.id }
        val hostProvider = hostModel.findProvider(settings.providers) ?: return enabledSeats.take(3).map { it.id }
        if (!hostProvider.enabled) return enabledSeats.take(3).map { it.id }

        val assistantsById = settings.assistants.associateBy { it.id }
        val seatDisplayNames = template.buildSeatDisplayNames(
            assistantsById = assistantsById,
            defaultName = "Assistant",
        )

        val seatLines = enabledSeats.mapNotNull { seat ->
            val assistant = assistantsById[seat.assistantId] ?: return@mapNotNull null
            val name = seatDisplayNames[seat.id]?.trim().orEmpty()
                .ifBlank { assistant.name.ifBlank { "Assistant" } }
            "- ${seat.id}: $name"
        }

        val recentHistory = recentAssistantMessages
            .map { it.toText().take(200) }
            .joinToString("\n")

        val routerPrompt = buildString {
            appendLine("You are the group chat router. You ONLY output JSON, do not reply to the user.")
            appendLine()
            appendLine("Rules:")
            appendLine("- Select 1 to 3 most relevant speakers from the seat list")
            appendLine("- Avoid selecting the same person repeatedly")
            appendLine("""Output format: {"speakers":["<seatId>", ...]}""")
            appendLine()
            appendLine("Available seats:")
            seatLines.forEach { appendLine(it) }
            appendLine()
            if (recentHistory.isNotBlank()) {
                appendLine("Recent messages:")
                appendLine(recentHistory)
                appendLine()
            }
            appendLine("User message:")
            appendLine(userText.take(2000))
        }

        return try {
            val providerHandler = providerManager.getProviderByType(hostProvider)
            val result = providerHandler.generateText(
                providerSetting = hostProvider,
                messages = listOf(UIMessage.user(routerPrompt)),
                params = TextGenerationParams(model = hostModel),
            )

            val text = result.choices.firstOrNull()?.message?.toText()?.trim() ?: ""
            if (text.isBlank()) return enabledSeats.take(3).map { it.id }

            val jsonStart = text.indexOf('{')
            val jsonEnd = text.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd < 0) return enabledSeats.take(3).map { it.id }

            val jsonStr = text.substring(jsonStart, jsonEnd + 1)
            val json = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr).jsonObject
            val speakerIdStrings = json["speakers"]?.jsonArray?.map { element ->
                element.jsonPrimitive.content
            }.orEmpty()
            val speakerIds = speakerIdStrings.mapNotNull { raw ->
                runCatching { Uuid.parse(raw) }.getOrNull()
            }.filter { id ->
                enabledSeats.any { it.id == id }
            }.takeIf { it.isNotEmpty() } ?: enabledSeats.take(3).map { it.id }

            speakerIds.take(3)
        } catch (e: Exception) {
            Log.w(TAG, "routeGroupChatSpeakers failed", e)
            enabledSeats.take(3).map { it.id }
        }
    }

    /**
     * 解析文本中的 @Name 提及
     */
    private fun resolveMentionedSeatIds(
        text: String,
        settings: Settings,
        template: GroupChatTemplate,
    ): List<Uuid> {
        if (text.isBlank() || !text.contains('@')) return emptyList()

        val assistantsById = settings.assistants.associateBy { it.id }
        val seatDisplayNames = template.buildSeatDisplayNames(
            assistantsById = assistantsById,
            defaultName = "Assistant",
        )

        val keyToSeatIds = mutableMapOf<String, MutableList<Uuid>>()
        template.seats.forEach { seat ->
            val key = seatDisplayNames[seat.id]?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            val normalized = key.lowercase(java.util.Locale.ROOT)
            keyToSeatIds.getOrPut(normalized) { mutableListOf() }.add(seat.id)
        }

        if (keyToSeatIds.isEmpty()) return emptyList()

        val sortedKeys = keyToSeatIds.keys.sortedByDescending { it.length }
        val result = mutableListOf<Uuid>()
        val lowerText = text.lowercase(java.util.Locale.ROOT)

        var cursor = 0
        while (true) {
            val atIndex = lowerText.indexOf('@', startIndex = cursor)
            if (atIndex < 0) break

            val after = lowerText.substring(atIndex + 1)
            val matchedKey = sortedKeys.firstOrNull { after.startsWith(it) }
            if (matchedKey != null) {
                keyToSeatIds[matchedKey]?.forEach { seatId ->
                    if (seatId !in result) result.add(seatId)
                }
                cursor = atIndex + 1 + matchedKey.length
            } else {
                cursor = atIndex + 1
            }
        }

        return result
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        // 群聊的停止逻辑将在 Step 3 中集成到 session 管理中

        val convMutex = mutexFor(conversationId)
        // cancelAndJoin BEFORE the mutex so the cancelled coroutine can drain its own
        // writes (which may try to acquire the same mutex via their save path).
        sessions[conversationId]?.getJob()?.cancelAndJoin()

        convMutex.withLock {
            // Hydrate from disk so we mark Pending tools cancelled even when the user
            // hits /stop after a process restart (sessions map is empty post-restart;
            // the old code returned early on the !sessions[id]?.getJob() check, leaving
            // the persisted Pending tool stranded forever).
            ensureHydrated(conversationId)

            val currentConversation = getConversationFlow(conversationId).value
            // Walk EVERY node, not just the last — Pending tools can appear on a non-last
            // node after branching / regenerate. finishPendingTools is now scoped to
            // tools that are NOT already in a terminal state, so a hardline-blocked
            // Denied tool keeps its original reason rather than being relabeled as
            // "cancelled by user".
            var changed = false
            val updatedNodes = currentConversation.messageNodes.map { node ->
                node.copy(
                    messages = node.messages.map { msg ->
                        val updated = msg.finishPendingTools(::cancelToolByUser)
                        if (updated !== msg) changed = true
                        updated
                    }
                )
            }
            if (!changed) return@withLock

            val updatedConversation = currentConversation.copy(messageNodes = updatedNodes)
            saveConversation(conversationId, updatedConversation)
        }
    }


    /**
     * 裁剪群聊历史消息，仅保留对指定座位相关的上下文。
     *
     * 策略：
     * 1. 保留最近 N 轮的所有消息
     * 2. 保留所有 @该座位 的消息（确保被提及的上下文不丢失）
     * 3. 按原始顺序排序
     *
     * 当历史消息不足 N 轮时，直接返回原始消息列表（无裁剪）。
     */
    private fun trimGroupChatContextForSeat(
        messages: List<UIMessage>,
        seatId: Uuid,
        template: GroupChatTemplate,
        seatDisplayNames: Map<Uuid, String>,
        maxRounds: Int = 10,
    ): List<UIMessage> {
        if (messages.size <= maxRounds * template.seats.size) return messages

        val seatName = seatDisplayNames[seatId]?.trim()?.lowercase()
            ?: return messages.takeLast(maxRounds * template.seats.size)

        // 1. 找到所有 @该座位的消息
        val mentionedIndices = messages.mapIndexedNotNull { index, msg ->
            val text = msg.toText().lowercase()
            if (text.contains("@$seatName")) index else null
        }.toSet()

        // 2. 最近 N 轮
        val recentStart = maxOf(0, messages.size - maxRounds * template.seats.size)
        val recentIndices = (recentStart until messages.size).toSet()

        // 3. 合并，按原始顺序排序
        val selectedIndices = (mentionedIndices + recentIndices).sorted()
        return selectedIndices.map { messages[it] }
    }
}
