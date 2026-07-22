package me.rerere.rikkahub.ui.pages.log

import androidx.lifecycle.ViewModel
import me.rerere.rikkahub.data.ai.requestlog.AIRequestLogManager

class LogDetailVM(
    id: Long,
    requestLogManager: AIRequestLogManager,
) : ViewModel() {
    val log = requestLogManager.observeById(id)
}
