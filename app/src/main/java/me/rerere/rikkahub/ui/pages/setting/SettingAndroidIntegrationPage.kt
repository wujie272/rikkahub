package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Translate
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.TextSelectionAction
import me.rerere.rikkahub.data.model.TextSelectionConfig
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

private fun assistantName(assistant: me.rerere.rikkahub.data.model.Assistant): String {
    return assistant.name.ifEmpty {
        val systemPromptPreview = assistant.systemPrompt.take(30).replace("
", " ").trim()
        if (systemPromptPreview.isNotEmpty()) "Assistant: ${systemPromptPreview}..."
        else "Assistant (${assistant.id.toString().take(8)})"
    }
}

private val COMMON_LANGUAGES = listOf(
    "English", "Spanish", "French", "German", "Italian", "Portuguese",
    "Russian", "Japanese", "Chinese", "Korean", "Arabic", "Hindi"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingAndroidIntegrationPage(
    settingsStore: SettingsStore = koinInject()
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current

    val config = settings.textSelectionConfig
    var editingAction by remember { mutableStateOf<TextSelectionAction?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_android_integration)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Try It section
            item {
                CardGroup(title = { Text(stringResource(R.string.text_selection_try_it)) }) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = stringResource(R.string.text_selection_setup_instructions),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SelectionContainer {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Text(
                                        text = stringResource(R.string.text_selection_demo_text),
                                        modifier = Modifier.padding(16.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.text_selection_demo_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Settings
            item {
                CardGroup(title = { Text(stringResource(R.string.settings)) }) {
                    // Assistant selector
                    val defaultAssistant = settings.assistants.firstOrNull()
                    val assistantIds = settings.assistants.map { it.id.toString() }
                    val currentAssistantId = config.assistantId?.toString()
                        ?: defaultAssistant?.id?.toString()
                        ?: ""
                    item(
                        headlineContent = { Text(stringResource(R.string.text_selection_assistant)) },
                        supportingContent = {
                            Text(
                                config.assistantId?.let { id ->
                                    settings.assistants.find { it.id == id }?.name?.ifEmpty {
                                        settings.assistants.find { it.id == id }?.let { assistantName(it) }
                                    }
                                } ?: defaultAssistant?.name?.ifEmpty {
                                    defaultAssistant?.let { assistantName(it) }
                                } ?: stringResource(R.string.none)
                            )
                        },
                        trailingContent = {
                            Select(
                                options = assistantIds,
                                selectedOption = currentAssistantId,
                                onOptionSelected = { selected ->
                                    scope.launch {
                                        val uuid = try { kotlin.uuid.Uuid.parse(selected) } catch (_: Exception) { null }
                                        settingsStore.update { s ->
                                            s.copy(textSelectionConfig = config.copy(assistantId = uuid))
                                        }
                                    }
                                },
                                optionToString = { idStr ->
                                    try {
                                        val uuid = kotlin.uuid.Uuid.parse(idStr)
                                        val a = settings.assistants.find { it.id == uuid }
                                        a?.name?.ifEmpty { assistantName(a) } ?: "Unknown"
                                    } catch (_: Exception) { "Unknown" }
                                },
                                modifier = Modifier.width(150.dp)
                            )
                        }
                    )

                    // Translate language
                    item(
                        headlineContent = { Text(stringResource(R.string.text_selection_translate_language)) },
                        supportingContent = { Text(config.translateLanguage) },
                        trailingContent = {
                            Select(
                                options = COMMON_LANGUAGES,
                                selectedOption = config.translateLanguage,
                                onOptionSelected = { lang ->
                                    scope.launch {
                                        settingsStore.update { s ->
                                            s.copy(textSelectionConfig = config.copy(translateLanguage = lang))
                                        }
                                    }
                                },
                                optionToString = { it },
                                modifier = Modifier.width(130.dp)
                            )
                        }
                    )

                    // Reset
                    item(
                        onClick = { showResetDialog = true },
                        headlineContent = { Text(stringResource(R.string.reset)) },
                        supportingContent = { Text(stringResource(R.string.text_selection_actions_reset_to_defaults_desc)) }
                    )
                }
            }

            // Quick Actions
            item {
                CardGroup(title = { Text(stringResource(R.string.text_selection_actions)) }) {
                    config.actions.forEachIndexed { index, action ->
                        item(
                            onClick = { editingAction = action },
                            headlineContent = { Text(actionTitle(action, context)) },
                            supportingContent = {
                                Text(
                                    text = action.prompt.take(60).replace("\n", " ") +
                                        if (action.prompt.length > 60) "..." else "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = getIconForAction(action.id),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    // Edit dialog
    editingAction?.let { action ->
        EditActionDialog(
            action = action,
            providers = settings.providers,
            onDismiss = { editingAction = null },
            onSave = { updated ->
                scope.launch {
                    val newActions = config.actions.map {
                        if (it.id == updated.id) updated else it
                    }
                    settingsStore.update { s ->
                        s.copy(textSelectionConfig = config.copy(actions = newActions))
                    }
                }
                editingAction = null
            }
        )
    }

    // Reset dialog
    if (showResetDialog) {
        val oldActions = config.actions
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset)) },
            text = { Text(stringResource(R.string.text_selection_actions_reset_to_defaults_confirm)) },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        settingsStore.update { s ->
                            s.copy(textSelectionConfig = TextSelectionConfig())
                        }
                    }
                    toaster.show(
                        message = context.getString(R.string.text_selection_actions_reset_to_defaults),
                        type = ToastType.Success
                    )
                    showResetDialog = false
                }) {
                    Text(stringResource(R.string.reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun EditActionDialog(
    action: TextSelectionAction,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    onDismiss: () -> Unit,
    onSave: (TextSelectionAction) -> Unit
) {
    var prompt by remember { mutableStateOf(action.prompt) }
    var selectedModelId by remember { mutableStateOf(action.modelId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.text_selection_edit_action)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.text_selection_action_prompt)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp),
                    minLines = 5
                )
                Text(
                    text = stringResource(R.string.text_selection_action_variable, "{{language}}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (action.isCustomPrompt) {
                    Text(
                        text = stringResource(R.string.text_selection_action_variable, "{{custom_prompt}}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.text_selection_model),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    ModelSelector(
                        modelId = selectedModelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        allowClear = true,
                        onSelect = { model ->
                            val shouldClear = model.displayName.isBlank() && model.modelId.isBlank()
                            selectedModelId = if (shouldClear) null else model.id
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(action.copy(prompt = prompt, modelId = selectedModelId)) },
                enabled = prompt.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun actionTitle(action: TextSelectionAction, context: android.content.Context): String {
    return when (action.id) {
        "translate" -> context.getString(R.string.text_selection_translate)
        "explain" -> context.getString(R.string.text_selection_explain)
        "summarize" -> context.getString(R.string.text_selection_summarize)
        "custom" -> context.getString(R.string.text_selection_ask)
        else -> action.name
    }
}

private fun getIconForAction(actionId: String): ImageVector {
    return when (actionId) {
        "translate" -> HugeIcons.Translate
        "explain" -> HugeIcons.Idea01
        "summarize" -> HugeIcons.MagicWand01
        else -> HugeIcons.Sparkles
    }
}
