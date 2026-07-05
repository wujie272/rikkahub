package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderApiKey
import me.rerere.ai.provider.ProviderKeyStrategy
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.activeApiKeyValuesForRequest
import me.rerere.ai.provider.copyWithApiKeyConfig
import me.rerere.ai.provider.enableMultiKeyFromCurrentValue
import me.rerere.ai.provider.getApiKeyValue
import me.rerere.ai.provider.getProviderApiKeys
import me.rerere.ai.provider.getProviderKeyStrategy
import me.rerere.ai.provider.isMultiKeyEnabled
import me.rerere.ai.provider.normalizedProviderApiKeys
import me.rerere.ai.provider.splitProviderApiKeys
import me.rerere.ai.provider.syncEnabledApiKeysToLegacy
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.readClipboardText

/**
 * Inline multi-key section: switch + manage button.
 * Placed inside ProviderConfigure*() after the legacy apiKey field.
 */
@Composable
fun ProviderMultiKeySection(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) {
    var showManager by remember { mutableStateOf(false) }
    val activeCount = provider.activeApiKeyValuesForRequest().size

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.setting_provider_page_multi_key_mode))
            Text(
                text = stringResource(R.string.setting_provider_page_multi_key_mode_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = provider.isMultiKeyEnabled(),
            onCheckedChange = { enabled ->
                val updated = if (enabled) {
                    provider.enableMultiKeyFromCurrentValue()
                } else {
                    provider.copyWithApiKeyConfig(multiKeyEnabled = false)
                }
                onEdit(updated.syncEnabledApiKeysToLegacy())
            },
        )
    }

    AnimatedVisibility(visible = provider.isMultiKeyEnabled()) {
        FilledTonalButton(
            onClick = { showManager = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(
                    R.string.setting_provider_page_manage_multi_keys_with_count,
                    activeCount,
                    provider.getProviderApiKeys().normalizedProviderApiKeys().size,
                ),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    if (showManager) {
        ProviderKeyManagerSheet(
            provider = provider,
            onDismissRequest = { showManager = false },
            onProviderChange = onEdit,
        )
    }
}

// ── Key Management Bottom Sheet ──

@Composable
private fun ProviderKeyManagerSheet(
    provider: ProviderSetting,
    onDismissRequest: () -> Unit,
    onProviderChange: (ProviderSetting) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keys = provider.getProviderApiKeys().normalizedProviderApiKeys()
    val activeCount = keys.count { it.enabled }

    var editingKey by remember { mutableStateOf<ProviderApiKey?>(null) }
    var importText by remember { mutableStateOf<String?>(null) }

    fun updateKeys(updatedKeys: List<ProviderApiKey>) {
        onProviderChange(
            provider.copyWithApiKeyConfig(
                multiKeyEnabled = true,
                apiKeys = updatedKeys.normalizedProviderApiKeys(),
            ).syncEnabledApiKeysToLegacy()
        )
    }

    fun hide() {
        scope.launch {
            sheetState.hide()
            onDismissRequest()
        }
    }

    ModalBottomSheet(
        onDismissRequest = ::hide,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Title
            Text(
                text = stringResource(R.string.setting_provider_page_api_keys),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "$activeCount / ${keys.size} ${stringResource(R.string.setting_provider_page_keys_enabled)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Strategy selector
            ProviderKeyStrategySelector(
                strategy = provider.getProviderKeyStrategy(),
                onStrategyChange = { strategy ->
                    onProviderChange(provider.copyWithApiKeyConfig(keyStrategy = strategy))
                },
            )

            // Add / Import buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { editingKey = ProviderApiKey(key = "") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.setting_provider_page_add_key))
                }
                OutlinedButton(
                    onClick = {
                        importText = context.readClipboardText()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.setting_provider_page_paste_from_clipboard))
                }
            }

            // Key list
            if (keys.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_no_api_keys),
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(
                        items = keys,
                        key = { _, key -> key.id },
                    ) { index, apiKey ->
                        ProviderApiKeyCard(
                            index = index,
                            apiKey = apiKey,
                            onToggle = { enabled ->
                                updateKeys(keys.map {
                                    if (it.id == apiKey.id) it.copy(enabled = enabled) else it
                                })
                            },
                            onEdit = { editingKey = apiKey },
                            onDelete = {
                                updateKeys(keys.filterNot { it.id == apiKey.id })
                            },
                        )
                    }
                }
            }
        }
    }

    // Add / Edit dialog
    if (editingKey != null) {
        ProviderApiKeyEditDialog(
            initial = editingKey ?: ProviderApiKey(key = ""),
            onDismissRequest = { editingKey = null },
            onConfirm = { editedKey ->
                val updated = if (keys.any { it.id == editedKey.id }) {
                    keys.map { if (it.id == editedKey.id) editedKey else it }
                } else {
                    keys + editedKey
                }
                updateKeys(updated)
                editingKey = null
            },
        )
    }

    // Import dialog
    if (importText != null) {
        ProviderApiKeyImportDialog(
            initialText = importText.orEmpty(),
            onDismissRequest = { importText = null },
            onImport = { raw ->
                val existingValues = keys.map { it.key }.toSet()
                val importedKeys = splitProviderApiKeys(raw)
                    .filterNot { it in existingValues }
                    .map { value -> ProviderApiKey(key = value) }
                if (importedKeys.isNotEmpty()) {
                    updateKeys(keys + importedKeys)
                }
                importText = null
            },
        )
    }
}

// ── Strategy Selector ──

@Composable
private fun ProviderKeyStrategySelector(
    strategy: ProviderKeyStrategy,
    onStrategyChange: (ProviderKeyStrategy) -> Unit,
) {
    Text(
        text = stringResource(R.string.setting_provider_page_key_strategy),
        style = MaterialTheme.typography.titleSmall,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ProviderKeyStrategy.entries.forEachIndexed { index, s ->
            SegmentedButton(
                selected = strategy == s,
                onClick = { onStrategyChange(s) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ProviderKeyStrategy.entries.size),
                label = {
                    Text(
                        when (s) {
                            ProviderKeyStrategy.LRU -> "LRU"
                            ProviderKeyStrategy.RANDOM -> "Random"
                            ProviderKeyStrategy.ROUND_ROBIN -> "RoundRobin"
                        }
                    )
                },
            )
        }
    }
}

// ── Key Card ──

@Composable
private fun ProviderApiKeyCard(
    index: Int,
    apiKey: ProviderApiKey,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = apiKey.alias.ifBlank { "Key ${index + 1}" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = maskApiKey(apiKey.key),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = apiKey.enabled,
                    onCheckedChange = onToggle,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onEdit) {
                    Text(stringResource(R.string.setting_provider_page_edit_key))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ── Edit Key Dialog ──

@Composable
private fun ProviderApiKeyEditDialog(
    initial: ProviderApiKey,
    onDismissRequest: () -> Unit,
    onConfirm: (ProviderApiKey) -> Unit,
) {
    var alias by remember(initial.id) { mutableStateOf(initial.alias) }
    var value by remember(initial.id) { mutableStateOf(initial.key) }
    var visible by remember(initial.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.setting_provider_page_edit_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text(stringResource(R.string.setting_provider_page_alias)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    label = { Text(stringResource(R.string.setting_provider_page_api_key)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = if (visible) 4 else 1,
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) {
                                    me.rerere.hugeicons.stroke.ViewOff
                                } else {
                                    me.rerere.hugeicons.stroke.View
                                },
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = {
                    onConfirm(
                        initial.copy(
                            alias = alias.trim(),
                            key = value.trim(),
                        )
                    )
                },
            ) {
                Text(stringResource(R.string.setting_provider_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

// ── Import Keys Dialog ──

@Composable
private fun ProviderApiKeyImportDialog(
    initialText: String,
    onDismissRequest: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.setting_provider_page_import_keys)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 8,
                label = { Text(stringResource(R.string.setting_provider_page_api_keys)) },
                supportingText = { Text(stringResource(R.string.setting_provider_page_import_keys_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onImport(text) },
            ) {
                Text(stringResource(R.string.setting_provider_page_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun maskApiKey(value: String): String {
    val trimmed = value.trim()
    if (trimmed.length <= 8) return "****"
    return "${trimmed.take(4)}...${trimmed.takeLast(4)}"
}
