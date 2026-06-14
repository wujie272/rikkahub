package me.rerere.rikkahub.automation

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Exported translucent Activity for app-caller intents (Tasker, MacroDroid, etc.).
 *
 * Uses Theme.Translucent.NoTitleBar so it never flashes a window before finish().
 */
class ExternalAutomationActivity : Activity() {

    private val config: ExternalAutomationConfig by inject()
    private val dispatcher: ExternalAutomationDispatcher by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent ?: run { finish(); return }
        val callerPkg = callingPackage ?: referrer?.host
        val action = intent.action
        val requestId = intent.getStringExtra(ExternalAutomationDispatcher.EXTRA_REQUEST_ID)
        val returnAction = intent.getStringExtra(ExternalAutomationDispatcher.EXTRA_RETURN_ACTION)
        val returnPackage = intent.getStringExtra(ExternalAutomationDispatcher.EXTRA_RETURN_PACKAGE)

        CoroutineScope(Dispatchers.Default).launch {
            try {
                handleAction(action, intent, callerPkg, requestId, returnAction, returnPackage)
            } catch (t: Throwable) {
                Log.w("ExtAutomation", "dispatch failed", t)
            } finally {
                runOnUiThread { finish() }
            }
        }
    }

    private suspend fun handleAction(
        action: String?,
        intent: android.content.Intent,
        callerPkg: String?,
        requestId: String?,
        returnAction: String?,
        returnPackage: String?,
    ) {
        when (val classification = dispatcher.classifyCaller(callerPkg)) {
            is ExternalAutomationDispatcher.TrustResult.Disabled -> {
                dispatcher.rejectAndCallback(callerPkg.orEmpty(), action.orEmpty(), requestId, returnAction, returnPackage, "feature_disabled")
                return
            }
            is ExternalAutomationDispatcher.TrustResult.PendingUserApproval -> {
                dispatcher.rejectAndCallback(callerPkg.orEmpty(), action.orEmpty(), requestId, returnAction, returnPackage, "untrusted_caller")
                return
            }
            is ExternalAutomationDispatcher.TrustResult.Trusted -> { /* proceed */ }
        }

        when (action) {
            ExternalAutomationDispatcher.ACTION_RUN_TASK -> {
                val prompt = ExternalAutomationDispatcher.extractPrompt(
                    intent,
                    ExternalAutomationDispatcher.EXTRA_TASK,
                    ExternalAutomationDispatcher.EXTRA_TASK_B64,
                )
                if (prompt.isNullOrBlank()) {
                    dispatcher.rejectAndCallback(callerPkg.orEmpty(), action.orEmpty(), requestId, returnAction, returnPackage, "missing_prompt")
                    return
                }
                dispatcher.dispatchTask(prompt, callerPkg.orEmpty(), requestId, returnAction, returnPackage)
            }
            ExternalAutomationDispatcher.ACTION_RUN_CHAT -> {
                dispatcher.rejectAndCallback(callerPkg.orEmpty(), action.orEmpty(), requestId, returnAction, returnPackage, "not_implemented_in_v1")
            }
            else -> {
                dispatcher.rejectAndCallback(callerPkg.orEmpty(), action.orEmpty(), requestId, returnAction, returnPackage, "unknown_action")
            }
        }
    }
}
