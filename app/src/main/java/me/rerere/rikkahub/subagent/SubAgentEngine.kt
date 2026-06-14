package me.rerere.rikkahub.subagent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

private const val TAG = "SubAgentEngine"
private const val GLOBAL_CAP = 16
private const val PER_ASSISTANT_CAP = 4

/**
 * 子代理引擎 — 简化版（无 HeadlessConversations，无持久化）。
 *
 * 每个子代理在一个新 Conversation 里运行，结果通过 harvestFinalText() 收割。
 * 后台模式跑完后会往父对话发一条通知消息。
 */
class SubAgentEngine(
    private val registry: SubAgentRegistry,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
) {
    // 懒加载避免 DI 循环
    private val chatService: ChatService by lazy {
        org.koin.java.KoinJavaComponent.getKoin().get<ChatService>()
    }

    sealed class DispatchResult {
        data class Ok(val run: SubAgentRun) : DispatchResult()
        data class Reject(val error: String, val detail: String) : DispatchResult()
    }

    suspend fun dispatch(
        parentAssistantId: String,
        parentChatId: String?,
        request: SubAgentRequest,
    ): DispatchResult = withContext(Dispatchers.Default) {
        // 检查并发上限
        if (registry.globalActiveCount() >= GLOBAL_CAP) {
            return@withContext DispatchResult.Reject(
                "global_cap_reached",
                "max $GLOBAL_CAP concurrent sub-agents across all assistants"
            )
        }
        if (registry.activeCountForAssistant(parentAssistantId) >= PER_ASSISTANT_CAP) {
            return@withContext DispatchResult.Reject(
                "assistant_cap_reached",
                "this assistant's cap of $PER_ASSISTANT_CAP concurrent sub-agents is reached"
            )
        }

        val runId = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val run = SubAgentRun(
            id = runId,
            parentChatId = parentChatId,
            parentAssistantId = parentAssistantId,
            label = request.label?.takeIf { it.isNotBlank() } ?: request.task.take(60),
            task = request.task,
            status = SubAgentStatus.PENDING,
            startedAtMs = now,
        )
        registry.add(run)

        val job = appScope.launch(Dispatchers.IO) {
            executeRun(runId, parentAssistantId, parentChatId, request)
        }
        registry.setJob(runId, job)

        if (request.runInBackground) {
            DispatchResult.Ok(registry.get(runId) ?: run)
        } else {
            try { job.join() } catch (_: Throwable) {}
            DispatchResult.Ok(registry.get(runId) ?: run)
        }
    }

    private suspend fun executeRun(
        runId: String,
        parentAssistantId: String,
        parentChatId: String?,
        request: SubAgentRequest,
    ) {
        registry.update(runId) { it.copy(status = SubAgentStatus.RUNNING) }

        val parentUuid = runCatching { Uuid.parse(parentAssistantId) }.getOrNull()
            ?: run { markTerminal(runId, SubAgentStatus.FAILED, "invalid assistant id"); return }

        val conv = Conversation.ofId(
            id = Uuid.random(),
            assistantId = parentUuid,
            newConversation = true,
        ).copy(title = "[Agent] ${request.label?.take(40) ?: request.task.take(40)}")
        conversationRepo.insertConversation(conv)
        chatService.initializeConversation(conv.id)

        try {
            // 追加收割指令，让子代理最后输出一段总结
            val taskWithWrapup = buildString {
                append(request.task)
                appendLine("\n\nWhen you have finished, end with one short paragraph that summarises what you did and what you found. Do NOT stop on a tool call — finish with assistant text. The dispatcher harvests only your final text reply, so this paragraph is the entire response the parent sees.")
            }
            chatService.sendMessage(conv.id, listOf(UIMessagePart.Text(taskWithWrapup)))

            // 等待完成
            val completed: Unit? = withTimeoutOrNull(request.timeoutSeconds * 1000L) {
                chatService.getGenerationJobStateFlow(conv.id).first { it == null }
                Unit
            }
            if (completed == null) {
                markTerminal(runId, SubAgentStatus.TIMED_OUT, "exceeded ${request.timeoutSeconds}s cap")
                notifyParent(parentChatId, runId)
                return
            }

            val finalText = harvestFinalText(conv.id)
            registry.update(runId) {
                it.copy(
                    status = SubAgentStatus.SUCCEEDED,
                    result = finalText,
                    finishedAtMs = System.currentTimeMillis(),
                )
            }
            notifyParent(parentChatId, runId)
        } catch (t: Throwable) {
            Log.w(TAG, "sub-agent run failed", t)
            val terminal = if (t is kotlinx.coroutines.CancellationException) SubAgentStatus.CANCELLED else SubAgentStatus.FAILED
            markTerminal(runId, terminal, "${t::class.simpleName}: ${t.message.orEmpty()}")
            notifyParent(parentChatId, runId)
        }
    }

    private suspend fun notifyParent(parentChatId: String?, runId: String) {
        if (parentChatId == null) return
        val parentUuid = runCatching { Uuid.parse(parentChatId) }.getOrNull() ?: return
        val run = registry.get(runId) ?: return
        if (!run.runInBackground) return // 前台运行不需要通知，结果通过 tool result 返回

        val message = buildString {
            appendLine("[Sub-agent ${run.label} — ${run.status.name}]")
            run.error?.takeIf { it.isNotBlank() }?.let { appendLine("Error: $it") }
            run.result?.takeIf { it.isNotBlank() }?.let { appendLine(); append(it) }
        }.trimEnd()

        runCatching {
            chatService.getGenerationJobStateFlow(parentUuid).first { it == null }
            chatService.sendMessage(parentUuid, listOf(UIMessagePart.Text(message)))
        }
    }

    private suspend fun harvestFinalText(conversationId: Uuid): String {
        return runCatching {
            val conv = conversationRepo.getConversationById(conversationId) ?: return@runCatching ""
            val selectedMessages = conv.messageNodes.mapNotNull { node ->
                node.messages.getOrNull(node.selectIndex)
            }
            val assistantMessages = selectedMessages.filter { msg ->
                msg.role.name.equals("assistant", ignoreCase = true)
            }
            if (assistantMessages.isEmpty()) return@runCatching ""

            val lastTexts = assistantMessages.last().parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }.trim()
            if (lastTexts.isNotBlank()) return@runCatching lastTexts

            // 回溯拼接
            assistantMessages.flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
                .joinToString("\n") { it.text }.trim()
        }.getOrDefault("")
    }

    private fun markTerminal(runId: String, status: SubAgentStatus, error: String?) {
        registry.update(runId) {
            it.copy(status = status, error = error, finishedAtMs = System.currentTimeMillis())
        }
        registry.clearJob(runId)
    }
}
