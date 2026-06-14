package me.rerere.rikkahub.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Manifest-registered receiver for ADB shell callers.
 *
 * ADB shell calls have empty callingPackage, so these are denied unless
 * the master toggle is on AND the user explicitly opted in.
 */
class ExternalAutomationReceiver : BroadcastReceiver(), KoinComponent {

    private val config: ExternalAutomationConfig by inject()
    private val dispatcher: ExternalAutomationDispatcher by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                handle(intent)
            } catch (t: Throwable) {
                Log.w("ExtAutomationRecv", "dispatch failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(intent: Intent) {
        val action = intent.action
        val requestId = intent.getStringExtra(ExternalAutomationDispatcher.EXTRA_REQUEST_ID)
        val returnAction = intent.getStringExtra(ExternalAutomationDispatcher.EXTRA_RETURN_ACTION)
        val returnPackage = intent.getStringExtra(ExternalAutomationDispatcher.EXTRA_RETURN_PACKAGE)
        val callerLabel = "<adb>"

        when (val classification = dispatcher.classifyCaller(callerLabel)) {
            is ExternalAutomationDispatcher.TrustResult.Disabled -> {
                dispatcher.rejectAndCallback(callerLabel, action.orEmpty(), requestId, returnAction, returnPackage, "feature_disabled")
                return
            }
            is ExternalAutomationDispatcher.TrustResult.PendingUserApproval -> {
                dispatcher.rejectAndCallback(callerLabel, action.orEmpty(), requestId, returnAction, returnPackage, "untrusted_caller")
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
                    dispatcher.rejectAndCallback(callerLabel, action.orEmpty(), requestId, returnAction, returnPackage, "missing_prompt")
                    return
                }
                dispatcher.dispatchTask(prompt, callerLabel, requestId, returnAction, returnPackage)
            }
            else -> {
                dispatcher.rejectAndCallback(callerLabel, action.orEmpty(), requestId, returnAction, returnPackage, "unsupported_action_for_broadcast")
            }
        }
    }
}
