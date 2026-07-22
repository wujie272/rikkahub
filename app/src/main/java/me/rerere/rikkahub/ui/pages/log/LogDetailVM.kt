package me.rerere.rikkahub.ui.pages.log

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.data.ai.requestlog.AIRequestLogEntity
import me.rerere.rikkahub.data.ai.requestlog.AIRequestLogManager
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class LogDetailVM(
    private val id: Long,
    private val requestLogManager: AIRequestLogManager,
) : ViewModel() {
    val log = requestLogManager.observeById(id)

    fun deleteLog() {
        viewModelScope.launch {
            requestLogManager.deleteLog(id)
        }
    }

    fun shareLog(context: Context) {
        viewModelScope.launch {
            val entity = requestLogManager.observeById(id).first() ?: return@launch
            withContext(Dispatchers.IO) {
                val item = entity.toShareItem()
                val json = JsonInstant.encodeToString(
                    serializer = LogShareItem.serializer(),
                    value = item,
                )
                val filename = "log-${id}-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.json"
                val dir = context.appTempFolder
                val file = dir.resolve(filename)
                file.writeText(json, Charsets.UTF_8)

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, "Share Log")
                    )
                }
            }
        }
    }
}

@Serializable
private data class LogShareItem(
    val id: Long,
    val createdAt: Long,
    val durationMs: Long?,
    val latencyMs: Long?,
    val source: String,
    val providerName: String,
    val providerType: String,
    val modelId: String,
    val modelDisplayName: String,
    val stream: Boolean,
    val paramsJson: String,
    val requestMessagesJson: String,
    val requestUrl: String,
    val responseText: String,
    val responseRawText: String,
    val error: String?,
)

private fun AIRequestLogEntity.toShareItem(): LogShareItem = LogShareItem(
    id = id,
    createdAt = createdAt,
    durationMs = durationMs,
    latencyMs = latencyMs,
    source = source,
    providerName = providerName,
    providerType = providerType,
    modelId = modelId,
    modelDisplayName = modelDisplayName,
    stream = stream,
    paramsJson = paramsJson,
    requestMessagesJson = requestMessagesJson,
    requestUrl = requestUrl,
    responseText = responseText,
    responseRawText = responseRawText,
    error = error,
)
