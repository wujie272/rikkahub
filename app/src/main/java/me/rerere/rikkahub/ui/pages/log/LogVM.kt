package me.rerere.rikkahub.ui.pages.log

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.ai.requestlog.AIRequestLogEntity
import me.rerere.rikkahub.data.ai.requestlog.AIRequestLogManager
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class LogVM(
    private val requestLogManager: AIRequestLogManager,
) : ViewModel() {
    private val rawLogs = requestLogManager.observeRecent()

    private val _selectedSource = MutableStateFlow<AIRequestSource?>(null)
    val selectedSource: StateFlow<AIRequestSource?> = _selectedSource.asStateFlow()

    private val _errorOnly = MutableStateFlow(false)
    val errorOnly: StateFlow<Boolean> = _errorOnly.asStateFlow()

    val logs: StateFlow<List<AIRequestLogEntity>> = combine(rawLogs, _selectedSource, _errorOnly) { logs, filter, errOnly ->
        var result = logs
        if (filter != null) result = result.filter { it.source == filter.name }
        if (errOnly) result = result.filter { it.error != null }
        result
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun setSourceFilter(source: AIRequestSource?) {
        _selectedSource.value = source
    }

    fun toggleErrorOnly() {
        _errorOnly.value = !_errorOnly.value
    }

    fun clearAll() {
        viewModelScope.launch {
            requestLogManager.clearAll()
            _selectedSource.value = null
            _errorOnly.value = false
        }
    }

    suspend fun exportLogs(context: Context) {
        val logs = logs.value
        if (logs.isEmpty()) return

        withContext(Dispatchers.IO) {
            val exportItems = logs.map { it.toExportItem() }
            val json = JsonInstant.encodeToString(
                serializer = ListSerializer(LogExportItem.serializer()),
                value = exportItems,
            )

            val filename = "logs-export-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.json"
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
                    Intent.createChooser(intent, context.getString(me.rerere.rikkahub.R.string.log_page_export))
                )
            }
        }
    }
}

@Serializable
private data class LogExportItem(
    val id: Long,
    val createdAt: Long,
    val durationMs: Long?,
    val source: String,
    val providerName: String,
    val providerType: String,
    val modelId: String,
    val modelDisplayName: String,
    val stream: Boolean,
    val paramsJson: String,
    val requestMessagesJson: String,
    val requestUrl: String,
    val requestPreview: String,
    val responsePreview: String,
    val responseText: String,
    val responseRawText: String,
    val error: String?,
)

private fun AIRequestLogEntity.toExportItem(): LogExportItem = LogExportItem(
    id = id,
    createdAt = createdAt,
    durationMs = durationMs,
    source = source,
    providerName = providerName,
    providerType = providerType,
    modelId = modelId,
    modelDisplayName = modelDisplayName,
    stream = stream,
    paramsJson = paramsJson,
    requestMessagesJson = requestMessagesJson,
    requestUrl = requestUrl,
    requestPreview = requestPreview,
    responsePreview = responsePreview,
    responseText = responseText,
    responseRawText = responseRawText,
    error = error,
)
