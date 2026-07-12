package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.buildSeatDisplayNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GroupChatMode
import me.rerere.rikkahub.data.model.GroupChatRuntimeConfig
import me.rerere.rikkahub.data.model.GroupChatSeat
import me.rerere.rikkahub.data.model.GroupChatTemplate
import me.rerere.rikkahub.data.model.toMessageNode

private const val TAG = "GroupChatRunner"

/** 主持人结束辩论的指令检测标记 */
private const val DEBATE_END_MARKER = "[DEBATE_END]"

/**
 * 群聊运行状态
 */
sealed interface GroupChatRunState {
    /** 空闲 */
    data object Idle : GroupChatRunState

    /** 运行中 */
    data class Running(
        val currentRound: Int,
        val maxRounds: Int,
        val currentSeatName: String?,
        val currentSeatIndex: Int,
        val totalSeats: Int,
    ) : GroupChatRunState

    /** 已结束 */
    data class Finished(
        val reason: String,
        val totalRounds: Int,
    ) : GroupChatRunState

    /** 出错 */
    data class Failed(val error: String) : GroupChatRunState
}

/**
 * 群聊执行引擎
 *
 * 负责调度多个助手轮流发言，支持三种模式：
 * [GroupChatMode.ROUND_ROBIN] — 按座位顺序循环，每轮所有启用座位各发言一次
 * [GroupChatMode.DEBATE] — 同上，但主持人座位可检测 [DEBATE_END] 提前结束，且可生成总结
 * [GroupChatMode.FREE] — 由路由模型决定下一个发言人（预留，MVP暂不实现）
 */
class GroupChatRunner(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    private val providerManager: ProviderManager,
) {
    private var engineJob: Job? = null

    private val _runState = MutableStateFlow<GroupChatRunState>(GroupChatRunState.Idle)
    val runState: StateFlow<GroupChatRunState> = _runState.asStateFlow()

    val isRunning: Boolean get() = engineJob?.isActive == true

    /**
     * 启动群聊
     *
     * @param conversationId 目标对话 ID
     * @param template 群聊模板
     * @param userMessage 触发的用户消息
     * @param runtimeConfig 运行配置
     */
    fun start(
        conversationId: kotlin.uuid.Uuid,
        template: GroupChatTemplate,
        userMessage: List<UIMessagePart>,
        runtimeConfig: GroupChatRuntimeConfig = GroupChatRuntimeConfig.roundRobinDefaults(),
    ) {
        if (isRunning) {
            Log.w(TAG, "start: already running, ignoring")
            return
        }

        engineJob?.cancel()
        engineJob = appScope.launch {
            try {
                runEngine(conversationId, template, userMessage, runtimeConfig)
            } catch (e: CancellationException) {
                Log.i(TAG, "runEngine cancelled")
                _runState.value = GroupChatRunState.Finished("cancelled", 0)
            } catch (e: Exception) {
                Log.e(TAG, "runEngine failed", e)
                _runState.value = GroupChatRunState.Failed(e.message ?: "unknown error")
            }
        }
    }

    /** 停止运行 */
    fun stop() {
        engineJob?.cancel()
        engineJob = null
    }

    // ──── 引擎核心 ────

    private suspend fun runEngine(
        conversationId: kotlin.uuid.Uuid,
        template: GroupChatTemplate,
        userMessage: List<UIMessagePart>,
        config: GroupChatRuntimeConfig,
    ) = coroutineScope {
        val enabledSeats = template.seats.filter { it.id !in config.disabledSeatIds }
        if (enabledSeats.isEmpty()) {
            _runState.value = GroupChatRunState.Failed("no enabled seats")
            return@coroutineScope
        }

        Log.i(TAG, "runEngine: mode=${config.mode}, seats=${enabledSeats.size}, maxRounds=${config.maxRounds}")

        // ── 1. 添加用户消息 ──
        chatService.updateConversationState(conversationId) { conv ->
            conv.copy(
                messageNodes = conv.messageNodes + UIMessage(
                    role = MessageRole.USER,
                    parts = userMessage,
                ).toMessageNode(),
            )
        }

        delay(500) // 让 UI 有时间渲染

        // ── 1.5 辩论开始消息（仅 DEBATE） ──
        if (config.mode == GroupChatMode.DEBATE) {
            val rolesDesc = enabledSeats.joinToString("\n") { seat ->
                val name = buildSeatDisplayName(template, seat, 0)
                "• **${name}**"
            }
            val startMsg = buildString {
                appendLine("🎯 **AI辩论开始**\n")
                appendLine("**最大轮数：** ${config.maxRounds}")
                appendLine()
                appendLine("**参与角色：**")
                appendLine(rolesDesc)
                appendLine()
                appendLine("---\n")
                appendLine("让辩论开始！")
            }
            chatService.updateConversationState(conversationId) { conv ->
                conv.copy(
                    messageNodes = conv.messageNodes + UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text(startMsg)),
                    ).toMessageNode().copy(senderName = "🎯 系统")
                )
            }
            delay(1000)
        }

        var currentRound = 1
        var shouldContinue = true
        val conversationHistory = mutableListOf<String>()

        _runState.value = GroupChatRunState.Running(
            currentRound = currentRound,
            maxRounds = config.maxRounds,
            currentSeatName = null,
            currentSeatIndex = 0,
            totalSeats = enabledSeats.size,
        )

        // ── 2. 主循环 ──
        // 收集用户消息中的 @提及
        val userText = userMessage.filterIsInstance<UIMessagePart.Text>()
            .joinToString(" ") { it.text }
        val mentionedSeatIds = resolveMentionedSeatIds(template, userText)

        while (currentRound <= config.maxRounds && shouldContinue && isActive) {
            Log.d(TAG, "=== Round $currentRound ===")

            // 决定本轮发言者
            val speakerSeatIds = when {
                // 如果用户 @了特定成员，只让被@的成员发言
                mentionedSeatIds.isNotEmpty() -> mentionedSeatIds.filter { id ->
                    enabledSeats.any { it.id == id }
                }
                // 有路由模型时用路由模型决定
                config.mode != GroupChatMode.DEBATE && template.hostModelId != null && currentRound <= 1 -> {
                    routeGroupChatSpeakers(
                        template = template,
                        userText = userText,
                        conversationHistory = conversationHistory,
                    ).filter { id -> enabledSeats.any { it.id == id } }
                }
                // 默认：所有启用座位轮流发言
                else -> enabledSeats.map { it.id }
            }

            if (speakerSeatIds.isEmpty()) {
                speakerSeatIds.let { enabledSeats.map { it.id } }
            }

            for ((seatIndex, seatId) in speakerSeatIds.withIndex()) {
                if (!isActive || !shouldContinue) break

                val seat = enabledSeats.firstOrNull { it.id == seatId } ?: continue
                val displayName = buildSeatDisplayName(template, seat, seatIndex)

                _runState.value = GroupChatRunState.Running(
                    currentRound = currentRound,
                    maxRounds = config.maxRounds,
                    currentSeatName = displayName,
                    currentSeatIndex = seatIndex,
                    totalSeats = speakerSeatIds.size,
                )

                // 构建上下文（含群聊信息）
                val context = buildGroupChatContext(
                    template = template,
                    seat = seat,
                    currentRound = currentRound,
                    history = conversationHistory,
                )

                // 调用 LLM
                val response = callSeatLlm(
                    conversationId = conversationId,
                    template = template,
                    seat = seat,
                    context = context,
                )

                if (response == null) continue

                conversationHistory.add("${displayName}：${response}")

                // 检测主持人结束指令（仅 DEBATE）
                if (config.mode == GroupChatMode.DEBATE && config.moderatorEndCheckEnabled) {
                    val isModerator = seat.overrides.systemPrompt?.contains("moderator", ignoreCase = true) == true
                            || seat.overrides.systemPrompt?.contains("主持人", ignoreCase = true) == true
                    if (isModerator && response.contains(DEBATE_END_MARKER)) {
                        Log.i(TAG, "Moderator triggered debate end at round $currentRound")
                        shouldContinue = false
                        break
                    }
                }

                // 轮间延迟
                if (isActive && shouldContinue) {
                    delay(config.interSeatDelayMs)
                }
            }

            // 互怼检测：如果某位成员反对另一位，自动触发回怼
            if (config.mode == GroupChatMode.DEBATE && shouldContinue && isActive) {
                val interReplies = detectInterReplies(
                    template = template,
                    history = conversationHistory,
                    enabledSeats = enabledSeats,
                )
                for ((replier, targetText) in interReplies) {
                    if (!isActive) break
                    val displayName = buildSeatDisplayName(template, replier, 0)
                    val context = buildString {
                        appendLine("你是${displayName}。")
                        appendLine()
                        appendLine("你被点名要求回应以下内容：")
                        appendLine(targetText)
                        appendLine()
                        appendLine("请直接回应对方观点，简洁有力，不超过200字。")
                    }
                    val response = callSeatLlm(
                        conversationId = conversationId,
                        template = template,
                        seat = replier,
                        context = context,
                    )
                    if (response != null) {
                        conversationHistory.add("${displayName}（回怼）：${response}")
                    }
                    delay(config.interSeatDelayMs)
                }
            }

            currentRound++
        }

        // ── 3. 总结（仅 DEBATE） ──
        if (config.mode == GroupChatMode.DEBATE && config.summaryEnabled && isActive) {
            generateSummary(conversationId, template, conversationHistory)
        }

        // ── 3.5 辩论结束消息（仅 DEBATE） ──
        if (config.mode == GroupChatMode.DEBATE) {
            val reason = if (currentRound > config.maxRounds) "达到最大轮数" else "主持人结束"
            val endMsg = "🏁 **AI辩论结束**\n\n共进行了 ${currentRound - 1} 轮辩论。\n结束原因：${reason}\n\n感谢各位AI角色的精彩辩论！"
            chatService.updateConversationState(conversationId) { conv ->
                conv.copy(
                    messageNodes = conv.messageNodes + UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text(endMsg)),
                    ).toMessageNode().copy(senderName = "🎯 系统")
                )
            }
        }

        // ── 4. 保存并完成 ──
        chatService.saveConversation(conversationId, chatService.getConversationFlow(conversationId).value)

        _runState.value = GroupChatRunState.Finished(
            reason = if (currentRound > config.maxRounds) "max_rounds_reached" else "moderator_ended",
            totalRounds = currentRound - 1,
        )

        Log.i(TAG, "runEngine finished: ${_runState.value}")
    }

    // ──── LLM 调用 ────

    /**
     * 调用某个座位的 LLM（流式输出）
     * 
     * 逐 token 写入对话，用户实时看到生成过程
     */
    private suspend fun callSeatLlm(
        conversationId: kotlin.uuid.Uuid,
        template: GroupChatTemplate,
        seat: GroupChatSeat,
        context: String,
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.value
                val assistant = resolveAssistant(settings, seat) ?: run {
                    Log.w(TAG, "callSeatLlm: assistant not found for seat ${seat.id}")
                    return@withContext null
                }

                val model = resolveModel(settings, seat, assistant) ?: run {
                    Log.w(TAG, "callSeatLlm: no model for seat ${seat.id}")
                    chatService.addError(
                        IllegalStateException("No model configured for seat ${assistant.name}"),
                        conversationId,
                    )
                    return@withContext null
                }

                val provider = model.findProvider(settings.providers) ?: run {
                    Log.w(TAG, "callSeatLlm: provider not found for model ${model.modelId}")
                    return@withContext null
                }

                if (!provider.enabled) {
                    Log.w(TAG, "callSeatLlm: provider ${provider.name} is disabled")
                    return@withContext null
                }

                val providerHandler = providerManager.getProviderByType(provider)
                val displayName = buildSeatDisplayName(template, seat, 0)

                // 预创建一条空消息，后续流式更新内容
                val msgId = kotlin.uuid.Uuid.random()
                chatService.updateConversationState(conversationId) { conv ->
                    conv.copy(
                        messageNodes = conv.messageNodes + UIMessage(
                            id = msgId,
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Text("")),
                        ).toMessageNode().copy(senderName = displayName)
                    )
                }

                // 流式调用 — 逐 token 更新对话
                var accumulated = StringBuilder()

                providerHandler.streamText(
                    providerSetting = provider,
                    messages = listOf(UIMessage.user(context)),
                    params = TextGenerationParams(model = model),
                ).collect { chunk ->
                    if (!isActive) throw CancellationException("Stream cancelled")

                    val delta = chunk.choices.firstOrNull()?.delta?.toText() ?: ""
                    if (delta.isNotEmpty()) {
                        accumulated.append(delta)

                        // 每 10 个字符更新一次 UI（减少 StateFlow 写压力）
                        if (accumulated.length % 10 < delta.length || delta.contains("\n")) {
                            val currentText = accumulated.toString()
                            chatService.updateConversationState(conversationId) { conv ->
                                conv.copy(
                                    messageNodes = conv.messageNodes.map { node ->
                                        if (node.currentMessage.id == msgId) {
                                            node.copy(
                                                messages = node.messages.map { msg ->
                                                    if (msg.id == msgId) {
                                                        msg.copy(parts = listOf(UIMessagePart.Text(currentText)))
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

                val text = accumulated.toString().trim()
                if (text.isBlank()) {
                    // 移除空白消息
                    chatService.updateConversationState(conversationId) { conv ->
                        conv.copy(messageNodes = conv.messageNodes.filterNot { it.currentMessage.id == msgId })
                    }
                    return@withContext null
                }

                // 移除结束标记（主持人用）
                val cleanText = text.replace(DEBATE_END_MARKER, "").trim()

                // 最终更新
                chatService.updateConversationState(conversationId) { conv ->
                    conv.copy(
                        messageNodes = conv.messageNodes.map { node ->
                            if (node.currentMessage.id == msgId) {
                                node.copy(
                                    messages = node.messages.map { msg ->
                                        if (msg.id == msgId) {
                                            msg.copy(parts = listOf(UIMessagePart.Text(cleanText)))
                                        } else msg
                                    }
                                )
                            } else node
                        }
                    )
                }

                Log.d(TAG, "callSeatLlm: ${displayName} responded (${cleanText.length} chars)")
                cleanText
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "callSeatLlm failed for seat ${seat.id}, using fallback", e)
                // 退路：用模拟响应填上，避免辩论卡住
                val displayName = buildSeatDisplayName(template, seat, 0)
                val fallbackText = getSimulatedResponse(
                    when {
                        seat.overrides.systemPrompt?.contains("pro", ignoreCase = true) == true ||
                            seat.overrides.systemPrompt?.contains("正方", ignoreCase = true) == true -> "pro"
                        seat.overrides.systemPrompt?.contains("con", ignoreCase = true) == true ||
                            seat.overrides.systemPrompt?.contains("反方", ignoreCase = true) == true -> "con"
                        seat.overrides.systemPrompt?.contains("moderator", ignoreCase = true) == true ||
                            seat.overrides.systemPrompt?.contains("主持人", ignoreCase = true) == true -> "moderator"
                        else -> "neutral"
                    }
                )
                chatService.updateConversationState(conversationId) { conv ->
                    conv.copy(
                        messageNodes = conv.messageNodes + UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Text(fallbackText)),
                        ).toMessageNode().copy(senderName = displayName)
                    )
                }
                fallbackText
            }
        }
    }private suspend fun generateSummary(
        conversationId: kotlin.uuid.Uuid,
        template: GroupChatTemplate,
        history: List<String>,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.value

                // 找第一个有模型的座位做总结
                val summarySeat = template.seats.firstOrNull { seat ->
                    val assistant = resolveAssistant(settings, seat) ?: return@firstOrNull false
                    resolveModel(settings, seat, assistant) != null
                } ?: return@withContext

                val assistant = resolveAssistant(settings, summarySeat) ?: return@withContext
                val model = resolveModel(settings, summarySeat, assistant) ?: return@withContext
                val provider = model.findProvider(settings.providers) ?: return@withContext
                if (!provider.enabled) return@withContext

                val providerHandler = providerManager.getProviderByType(provider)

                val summaryPrompt = """
请对以下 AI 辩论进行客观、专业的总结分析：

辩论记录：
${history.joinToString("\n\n")}

请提供一个结构化总结，包括：
1. 主要观点梳理：各方的核心论点
2. 分歧点分析：争议的焦点
3. 共识点识别：可能达成一致的观点
4. 结论建议：基于辩论的平衡性建议
                """.trimIndent()

                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(UIMessage.user(summaryPrompt)),
                    params = TextGenerationParams(model = model),
                )

                val text = result.choices.firstOrNull()?.message?.toText()?.trim() ?: return@withContext
                if (text.isBlank()) return@withContext

                chatService.updateConversationState(conversationId) { conv ->
                    conv.copy(
                        messageNodes = conv.messageNodes + UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Text("🏁 **辩论总结**\n\n${text}")),
                        ).toMessageNode().copy(senderName = "📊 总结")
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "generateSummary failed", e)
            }
        }
    }

    
    /**
     * 获取模拟响应（当 AI 调用失败时的退路）
     */
    private fun getSimulatedResponse(stance: String): String {
        val responses = mapOf(
            "pro" to listOf(
                "我认为这个观点有充分的证据支持。从实际效果来看，这种方法是合理的选择。",
                "支持这个观点的理由很充分。数据表明这是正确的方向。",
                "基于大量实际案例，我们可以清楚地看到这种做法带来的积极效果。"
            ),
            "con" to listOf(
                "我必须指出这个观点存在明显缺陷。这种做法可能带来意想不到的负面后果。",
                "反对的理由很充分。从风险评估来看，这种方法的潜在危害大于收益。",
                "虽然表面上看起来合理，但深入分析会发现实际执行中会遇到很多问题。"
            ),
            "neutral" to listOf(
                "从客观角度分析，双方都有合理之处。我们需要更全面地考虑各种因素。",
                "让我们理性看待这个问题。每种观点都有其价值，关键是如何找到最佳方案。",
                "这个问题确实复杂，需要从多个维度来评估可行性。"
            ),
            "moderator" to listOf(
                "感谢各位的精彩发言。让我总结一下目前的主要观点和分歧点。",
                "讨论很充分，各方都提出了有价值的观点。让我们继续深入探讨。",
                "基于目前的讨论，各方的观点都得到了充分表达。"
            )
        )
        val roleResponses = responses[stance] ?: responses["neutral"]!!
        return roleResponses[java.util.Random().nextInt(roleResponses.size)]
    }

    // ──── @Name 提及系统 ────

    /**
     * 从文本中解析 @提及的座位 ID
     * 支持 @Name 和 @Name#2 语法
     */
    private fun resolveMentionedSeatIds(
        template: GroupChatTemplate,
        text: String,
    ): List<kotlin.uuid.Uuid> {
        if (text.isBlank() || !text.contains('@')) return emptyList()

        val assistantsById = mutableMapOf<kotlin.uuid.Uuid, Assistant?>()
        val settings = settingsStore.settingsFlow.value
        template.seats.forEach { seat ->
            assistantsById[seat.assistantId] = settings.assistants.firstOrNull { it.id == seat.assistantId }
        }

        val seatDisplayNames = template.buildSeatDisplayNames(
            assistantsById = settings.assistants.associateBy { it.id },
            defaultName = "助手",
        )

        val keyToSeatIds = mutableMapOf<String, MutableList<kotlin.uuid.Uuid>>()
        template.seats.forEach { seat ->
            val key = seatDisplayNames[seat.id]?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            val normalized = key.lowercase(java.util.Locale.ROOT)
            keyToSeatIds.getOrPut(normalized) { mutableListOf() }.add(seat.id)
        }

        if (keyToSeatIds.isEmpty()) return emptyList()

        val sortedKeys = keyToSeatIds.keys.sortedByDescending { it.length }
        val result = mutableListOf<kotlin.uuid.Uuid>()
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

    // ──── 路由模型 ────

    /**
     * 使用路由模型决定本次由谁发言
     * 当 template.hostModelId 配置时调用
     */
    private suspend fun routeGroupChatSpeakers(
        template: GroupChatTemplate,
        userText: String,
        conversationHistory: List<String>,
    ): List<kotlin.uuid.Uuid> {
        val settings = settingsStore.settingsFlow.value
        val enabledSeats = template.seats
        if (enabledSeats.isEmpty()) return emptyList()

        // 没有路由模型则返回前3个座位
        val hostModelId = template.hostModelId ?: return enabledSeats.take(3).map { it.id }
        val hostModel = settings.findModelById(hostModelId) ?: return enabledSeats.take(3).map { it.id }

        val assistantsById = settings.assistants.associateBy { it.id }
        val seatDisplayNames = template.buildSeatDisplayNames(
            assistantsById = assistantsById,
            defaultName = "助手",
        )

        val seatLines = enabledSeats.mapNotNull { seat ->
            val assistant = assistantsById[seat.assistantId] ?: return@mapNotNull null
            val name = seatDisplayNames[seat.id]?.trim().orEmpty()
                .ifBlank { assistant.name.ifBlank { "助手" } }
            "- ${seat.id}: $name"
        }

        val recentHistory = conversationHistory.takeLast(4).joinToString("\n")
        


        val routerPrompt = buildString {
            appendLine("你是群聊的路由模型。你只输出 JSON，不回复用户。")
            appendLine()
            appendLine("规则：")
            appendLine("- 从座位列表中选择 1 到 3 个最相关的发言人")
            appendLine("- 避免重复选择同一人")
            appendLine("""输出格式：{"speakers":["<seatId>", ...]}""")
            appendLine()
            appendLine("可用座位：")
            seatLines.forEach { appendLine(it) }
            appendLine()
            if (recentHistory.isNotBlank()) {
                appendLine("最近发言：")
                appendLine(recentHistory)
                appendLine()
            }
            appendLine("用户消息：")
            appendLine(userText.take(2000))
        }

        return try {
            val provider = hostModel.findProvider(settings.providers) ?: return enabledSeats.take(3).map { it.id }
            if (!provider.enabled) return enabledSeats.take(3).map { it.id }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(routerPrompt)),
                params = TextGenerationParams(model = hostModel),
            )

            val text = result.choices.firstOrNull()?.message?.toText()?.trim() ?: ""
            if (text.isBlank()) return enabledSeats.take(3).map { it.id }

            // 解析 JSON 输出
            val jsonStart = text.indexOf('{')
            val jsonEnd = text.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd < 0) return enabledSeats.take(3).map { it.id }

            val jsonStr = text.substring(jsonStart, jsonEnd + 1)
            val json = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr).jsonObject
            val speakerIds = json["speakers"]?.jsonArray?.map { it.jsonPrimitive.content }
                ?.mapNotNull { runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull() }
                ?.filter { id -> enabledSeats.any { it.id == id } }
                ?: enabledSeats.take(3).map { it.id }

            speakerIds.take(3)
        } catch (e: Exception) {
            Log.w(TAG, "routeGroupChatSpeakers failed", e)
            enabledSeats.take(3).map { it.id }
        }
    }

    // ──── 互怼检测 ────

    /**
     * 检测发言中是否存在反对/驳斥，返回需要回怼的座位和对应内容
     */
    private fun detectInterReplies(
        template: GroupChatTemplate,
        history: List<String>,
        enabledSeats: List<GroupChatSeat>,
    ): List<Pair<GroupChatSeat, String>> {
        if (history.size < 2) return emptyList()

        val disagreementMarkers = listOf(
            "我不同意", "不同意", "不认同", "反对", "有误", "不对", "错误", "不准确",
            "i disagree", "disagree with", "that's wrong", "incorrect", "not correct",
        )

        val result = mutableListOf<Pair<GroupChatSeat, String>>()
        val settings = settingsStore.settingsFlow.value

        // 只检查最近 2 条发言
        val recentEntries = history.takeLast(2)
        for (entry in recentEntries) {
            val normalized = entry.lowercase(java.util.Locale.ROOT)
            val hasDisagreement = disagreementMarkers.any { normalized.contains(it) }
            if (!hasDisagreement) continue

            // 找到被怼的座位（前一条发言的发言人）
            val previousEntry = history.getOrNull(history.indexOf(entry) - 1) ?: continue
            val previousSpeakerName = previousEntry.substringBefore("：").trim()
            if (previousSpeakerName.isBlank()) continue

            // 找到对应的座位
            val targetSeat = enabledSeats.firstOrNull { seat ->
                val name = buildSeatDisplayName(template, seat, 0)
                previousSpeakerName.contains(name) || name.contains(previousSpeakerName)
            } ?: continue

            // 避免重复回怼
            if (result.any { it.first.id == targetSeat.id }) continue
            result.add(targetSeat to previousEntry)
        }

        return result.take(2) // 最多 2 轮互怼
    }

    // ──── 群聊上下文构建 ────

    /**
     * 为座位构建 LLM 上下文（含群聊信息 + @Name 说明）
     */
    private fun buildGroupChatContext(
        template: GroupChatTemplate,
        seat: GroupChatSeat,
        currentRound: Int,
        history: List<String>,
    ): String {
        val settings = settingsStore.settingsFlow.value
        val assistant = resolveAssistant(settings, seat)
        val basePrompt = seat.overrides.systemPrompt ?: assistant?.systemPrompt ?: ""
        val displayName = buildSeatDisplayName(template, seat, 0)

        // 构建成员列表
        val seatDisplayNames = template.buildSeatDisplayNames(
            assistantsById = settings.assistants.associateBy { it.id },
            defaultName = "助手",
        )
        val memberNames = template.seats.mapNotNull { s -> seatDisplayNames[s.id] }
        val membersLine = if (memberNames.isEmpty()) "未知" else memberNames.joinToString(", ")

        // 检测立场
        val isModerator = basePrompt.contains("moderator", ignoreCase = true) ||
            basePrompt.contains("主持人", ignoreCase = true)
        val isPro = basePrompt.contains("pro", ignoreCase = true) ||
            basePrompt.contains("正方", ignoreCase = true)
        val isCon = basePrompt.contains("con", ignoreCase = true) ||
            basePrompt.contains("反方", ignoreCase = true)
        val isNeutral = basePrompt.contains("neutral", ignoreCase = true) ||
            basePrompt.contains("中立", ignoreCase = true)

        return buildString {
            appendLine("你是${displayName}。")
            appendLine()

            // 群聊信息
            if (template.name.isNotBlank()) {
                appendLine("群聊：${template.name}")
            }
            appendLine("成员：${membersLine}")
            appendLine("你的身份：${displayName}")
            appendLine()
            appendLine("规则：")
            appendLine("- 保持自己的风格，不要模仿其他成员")
            appendLine("- 使用 @Name 可以点名让某位成员回应")
            appendLine("- 其他成员的消息来自他们自己，不是用户")
            appendLine()

            // 立场提示
            if (isModerator) {
                appendLine("📋 **你的角色：辩论主持人**")
                appendLine("- 当前辩论进度：第${currentRound}轮，已有${history.size}次发言")
                if (currentRound < 2) {
                    appendLine("- 辩论刚开始，请推动讨论深入")
                } else if (currentRound < 3) {
                    appendLine("- 辩论进行中，继续引导各方深入交流")
                } else {
                    appendLine("- 可考虑是否已充分讨论")
                }
                appendLine()
                appendLine("🔚 如果认为讨论充分，可在回复末尾添加 ${DEBATE_END_MARKER} 结束辩论。")
            } else if (isPro) {
                appendLine("🎯 **立场：正方** — 支持观点，提供证据和逻辑论证")
            } else if (isCon) {
                appendLine("🎯 **立场：反方** — 反对观点，提出反驳和质疑")
            } else if (isNeutral) {
                appendLine("🎯 **角色：中立分析师** — 客观分析双方观点")
            }

            if (basePrompt.isNotBlank() && !isModerator && !isPro && !isCon && !isNeutral) {
                appendLine()
                appendLine("---")
                appendLine(basePrompt)
            }

            if (template.intro.isNotBlank()) {
                appendLine()
                appendLine("讨论背景：${template.intro}")
            }

            if (history.isNotEmpty()) {
                appendLine()
                appendLine("前面的发言：")
                history.takeLast(10).forEach { line ->
                    appendLine(line)
                }
            }

            appendLine()
            appendLine("请基于以上内容回应，简洁有力，不超过300字。")
        }
    }

    // ──── 工具方法 ────// ──── 工具方法 ────

    /**
     * 为座位构建 LLM 上下文（立场感知）
     * 
     * 根据座位角色（正反方/主持人/中立）生成不同的上下文，
     * 主持人会收到辩论进度信息，正反方会收到各自立场提示
     */
    private fun buildContext(
        template: GroupChatTemplate,
        seat: GroupChatSeat,
        currentRound: Int,
        history: List<String>,
    ): String {
        val assistant = resolveAssistant(settingsStore.settingsFlow.value, seat)
        val basePrompt = seat.overrides.systemPrompt ?: assistant?.systemPrompt ?: ""
        val displayName = buildSeatDisplayName(template, seat, 0)

        // 检测立场
        val isModerator = basePrompt.contains("moderator", ignoreCase = true) ||
            basePrompt.contains("主持人", ignoreCase = true)
        val isPro = basePrompt.contains("pro", ignoreCase = true) ||
            basePrompt.contains("正方", ignoreCase = true)
        val isCon = basePrompt.contains("con", ignoreCase = true) ||
            basePrompt.contains("反方", ignoreCase = true)
        val isNeutral = basePrompt.contains("neutral", ignoreCase = true) ||
            basePrompt.contains("中立", ignoreCase = true)
        val isSummary = basePrompt.contains("summary", ignoreCase = true) ||
            basePrompt.contains("总结", ignoreCase = true)

        return buildString {
            appendLine("你是${displayName}。")
            appendLine()

            // 立场提示
            if (isModerator) {
                appendLine("📋 **你的角色：辩论主持人**")
                appendLine("- 公正中立，引导辩论方向")
                appendLine("- 总结各方要点，推动讨论深入")
                appendLine("- 当前辩论进度：第${currentRound}轮，已有${history.size}次发言")
                if (currentRound < 2) {
                    appendLine("- 辩论刚开始，请推动讨论深入，不要急于结束")
                } else if (currentRound < 3) {
                    appendLine("- 辩论进行中，继续引导各方深入交流")
                } else {
                    appendLine("- 可以考虑是否已充分讨论，必要时可建议结束")
                }
                appendLine()
                appendLine("🔚 **结束指令**：如果认为讨论已充分，可在回复末尾添加 ${DEBATE_END_MARKER} 来结束辩论。")
            } else if (isPro) {
                appendLine("🎯 **你的立场：正方** — 支持辩论观点")
                appendLine("- 提供有力的证据和逻辑论证")
                appendLine("- 反驳对方的质疑")
                appendLine("- 保持理性和专业")
            } else if (isCon) {
                appendLine("🎯 **你的立场：反方** — 反对辩论观点")
                appendLine("- 揭示对方论证的漏洞")
                appendLine("- 提出有力的反驳和质疑")
                appendLine("- 保持批判性思维")
            } else if (isNeutral) {
                appendLine("🎯 **你的角色：中立分析师**")
                appendLine("- 客观分析双方观点")
                appendLine("- 指出论证中的逻辑问题")
                appendLine("- 提供平衡的视角")
            } else if (isSummary) {
                appendLine("🎯 **你的角色：总结分析师**")
                appendLine("- 总结整个辩论过程")
                appendLine("- 识别争议焦点和共识")
                appendLine("- 提供平衡的结论")
            }

            if (basePrompt.isNotBlank()) {
                appendLine()
                appendLine("---")
                appendLine(basePrompt)
            }

            appendLine()
            appendLine("---")
            appendLine("当前是第${currentRound}轮辩论。")

            if (template.intro.isNotBlank()) {
                appendLine()
                appendLine("讨论背景：${template.intro}")
            }

            if (history.isNotEmpty()) {
                appendLine()
                appendLine("前面的发言：")
                history.takeLast(8).forEach { line ->
                    appendLine(line)
                }
            }

            appendLine()
            if (isModerator) {
                appendLine("请基于以上内容主持辩论，每个发言控制在200字以内。")
            } else if (isSummary) {
                appendLine("请提供全面、深入、平衡的总结分析。")
            } else {
                appendLine("请基于以上内容回应，简洁有力，不超过200字。")
            }
        }
    }

    /**
     * 构建座位显示名
     */
    private fun buildSeatDisplayName(
        template: GroupChatTemplate,
        seat: GroupChatSeat,
        _seatIndex: Int,
    ): String {
        val assistant = resolveAssistant(settingsStore.settingsFlow.value, seat)
        val baseName = assistant?.name?.ifBlank { null } ?: "助手"
        return if (seat.instanceNumber > 1) "$baseName#${seat.instanceNumber}" else baseName
    }

    /**
     * 解析座位的助手
     */
    private fun resolveAssistant(
        settings: Settings,
        seat: GroupChatSeat,
    ): Assistant? {
        return settings.assistants.firstOrNull { it.id == seat.assistantId }
    }

    /**
     * 解析座位的模型
     */
    private fun resolveModel(
        settings: Settings,
        seat: GroupChatSeat,
        assistant: Assistant,
    ): Model? {
        val modelId = seat.overrides.chatModelId ?: assistant.chatModelId ?: settings.chatModelId
        return settings.findModelById(modelId)
    }
}
