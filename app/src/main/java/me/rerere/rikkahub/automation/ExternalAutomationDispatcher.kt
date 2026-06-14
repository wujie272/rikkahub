package me.rerere.rikkahub.automation

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Single dispatch point shared by ExternalAutomationActivity (app callers)
 * and ExternalAutomationReceiver (ADB callers).
 *
 * Intent actions:
 *   - me.rerere.rikkahub.RUN_TASK — fire-and-forget headless run
 *   - me.rerere.rikkahub.RUN_CHAT — interactive (v1: not implemented)
 *
 * Trust model:
 *   1. enabled flag OFF → reject
 *   2. caller in trustedPackages → run
 *   3. otherwise → reject (v1: no per-call dialog)
 *
 * Extras:
 *   task / task_b64 — prompt string (b64 for unicode safety)
 *   request_id — caller correlation id
 *   return_action / return_package — callback broadcast
 *
 * Callback status values:
 *   accepted / completed / failed / cancelled / rejected
 */
class ExternalAutomationDispatcher(
    private val context: Context,
    private val config: ExternalAutomationConfig,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
) {

    sealed class TrustResult {
        data object Trusted : TrustResult()
        data object PendingUserApproval : TrustResult()
        data object Disabled : TrustResult()
    }

    suspend fun classifyCaller(callerPackage: String?): TrustResult {
        if (!config.isEnabled()) return TrustResult.Disabled
        if (callerPackage.isNullOrBlank()) return TrustResult.PendingUserApproval
        return if (callerPackage in config.trustedPackages()) TrustResult.Trusted
        else TrustResult.PendingUserApproval
    }

    suspend fun dispatchTask(
        prompt: String,
        callerPackage: String,
        requestId: String?,
        returnAction: String?,
        returnPackage: String?,
    ): String {
        val parsedPrompt = prompt.trim()
        if (parsedPrompt.isEmpty()) {
            log(callerPackage, "RUN_TASK", "rejected:empty_prompt", requestId)
            sendCallback(returnAction, returnPackage, requestId, "rejected", "empty prompt")
            return "rejected:empty_prompt"
        }

        // Dedup: same (caller, requestId) within 60s window
        if (!requestId.isNullOrBlank()) {
            val dedupKey = callerPackage + "\u0000" + requestId
            val nowMs = android.os.SystemClock.elapsedRealtime()
            pruneRecentRequestIds(nowMs)
            val seenAt = recentRequestIds[dedupKey]
            if (seenAt != null && nowMs - seenAt < REQUEST_ID_DEDUP_WINDOW_MS) {
                log(callerPackage, "RUN_TASK", "deduped:request_id_replay", requestId)
                sendCallback(returnAction, returnPackage, requestId, "rejected", "duplicate request_id")
                return "rejected:duplicate_request_id"
            }
            recentRequestIds[dedupKey] = nowMs
        }

        sendCallback(returnAction, returnPackage, requestId, "accepted", null)
        log(callerPackage, "RUN_TASK", "accepted", requestId)

        appScope.launch(Dispatchers.IO) {
            runHeadless(parsedPrompt, requestId, returnAction, returnPackage)
        }

        return "accepted"
    }

    private suspend fun runHeadless(
        prompt: String,
        requestId: String?,
        returnAction: String?,
        returnPackage: String?,
    ) {
        try {
            val assistant = withContext(Dispatchers.IO) {
                settingsStore.settingsFlow.first().getCurrentAssistant()
            }
            val conv = Conversation.ofId(
                id = Uuid.random(),
                assistantId = assistant.id,
                newConversation = true,
            ).copy(title = "[External] ${prompt.take(40).ifBlank { "(empty)" }}")
            conversationRepo.insertConversation(conv)
            chatService.initializeConversation(conv.id)
            chatService.sendMessage(conv.id, listOf(UIMessagePart.Text(prompt)))

            sendCallback(returnAction, returnPackage, requestId, "completed", null)
        } catch (t: Throwable) {
            Log.w("ExtAutomation", "run failed", t)
            val status = if (t is kotlinx.coroutines.CancellationException) "cancelled" else "failed"
            sendCallback(returnAction, returnPackage, requestId, status, "${t::class.simpleName}: ${t.message.orEmpty()}")
        }
    }

    suspend fun rejectAndCallback(
        callerPackage: String,
        action: String,
        requestId: String?,
        returnAction: String?,
        returnPackage: String?,
        reason: String,
    ) {
        log(callerPackage, action, "rejected:$reason", requestId)
        sendCallback(returnAction, returnPackage, requestId, "rejected", reason)
    }

    private fun sendCallback(
        returnAction: String?,
        returnPackage: String?,
        requestId: String?,
        status: String,
        message: String?,
    ) {
        if (returnAction.isNullOrBlank() || returnPackage.isNullOrBlank()) return
        val intent = Intent(returnAction).apply {
            setPackage(returnPackage)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_REQUEST_ID, requestId.orEmpty())
            if (!message.isNullOrBlank()) putExtra(EXTRA_MESSAGE, message)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        runCatching { context.sendBroadcast(intent) }.onFailure {
            Log.w("ExtAutomation", "callback broadcast failed", it)
        }
    }

    private suspend fun log(
        callerPackage: String,
        action: String,
        status: String,
        requestId: String?,
    ) {
        config.logInvocation(
            ExternalAutomationConfig.InvocationLog(
                timestampMs = System.currentTimeMillis(),
                callerPackage = callerPackage,
                action = action,
                status = status,
                requestId = requestId,
            )
        )
    }

    private val recentRequestIds = ConcurrentHashMap<String, Long>()

    private fun pruneRecentRequestIds(nowMs: Long) {
        if (recentRequestIds.isEmpty()) return
        val cutoff = nowMs - REQUEST_ID_DEDUP_WINDOW_MS
        val it = recentRequestIds.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value < cutoff) it.remove()
        }
    }

    companion object {
        const val ACTION_RUN_TASK = "me.rerere.rikkahub.RUN_TASK"
        const val ACTION_RUN_CHAT = "me.rerere.rikkahub.RUN_CHAT"
        private const val REQUEST_ID_DEDUP_WINDOW_MS = 60_000L
        const val EXTRA_TASK = "task"
        const val EXTRA_TASK_B64 = "task_b64"
        const val EXTRA_CHAT = "chat"
        const val EXTRA_CHAT_B64 = "chat_b64"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_RETURN_ACTION = "return_action"
        const val EXTRA_RETURN_PACKAGE = "return_package"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"

        fun extractPrompt(intent: Intent, rawKey: String, b64Key: String): String? =
            extractPromptStrings(
                raw = intent.getStringExtra(rawKey),
                base64Encoded = intent.getStringExtra(b64Key),
            )

        @JvmStatic
        fun extractPromptStrings(raw: String?, base64Encoded: String?): String? {
            raw?.takeIf { it.isNotBlank() }?.let { return it }
            base64Encoded?.takeIf { it.isNotBlank() }?.let { encoded ->
                return runCatching {
                    String(java.util.Base64.getUrlDecoder().decode(encoded))
                }.recoverCatching {
                    String(java.util.Base64.getDecoder().decode(encoded))
                }.getOrNull()
            }
            return null
        }
    }
}
