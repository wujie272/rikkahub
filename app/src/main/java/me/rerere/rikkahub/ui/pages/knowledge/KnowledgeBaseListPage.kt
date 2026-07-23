package me.rerere.rikkahub.ui.pages.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import androidx.compose.foundation.background
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.knowledge.KnowledgeBaseEntity
import me.rerere.rikkahub.ui.pages.knowledge.KnowledgeVM.ModelDisplay
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun KnowledgeBaseListPage(vm: KnowledgeVM = koinViewModel()) {
    val kbs by vm.knowledgeBases.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val models by vm.availableModels.collectAsStateWithLifecycle()

    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var newKbName by rememberSaveable { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf<ModelDisplay?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.kb_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        floatingActionButton = {
            FloatingActionButton(onClick = {
                newKbName = ""
                selectedModel = models.firstOrNull()
                showCreateDialog = true
            }) {
                Icon(HugeIcons.Add01, stringResource(R.string.kb_create))
            }
        }
    ) { innerPadding ->
        if (showCreateDialog) {
            CreateKnowledgeBaseDialog(
                name = newKbName,
                onNameChange = { newKbName = it },
                models = models,
                selectedModel = selectedModel,
                onModelChange = { selectedModel = it },
                onDismiss = { showCreateDialog = false },
                onConfirm = {
                    val model = selectedModel ?: return@CreateKnowledgeBaseDialog
                    vm.initCreateForm()
                    vm.updateEditForm { it.copy(name = newKbName.trim(), modelId = model.id, modelDisplayName = model.displayName) }
                    vm.detectModelDimensions(model.id)
                    vm.saveKnowledgeBase(false, null) {
                        showCreateDialog = false
                    }
                },
            )
        }
        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (kbs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(HugeIcons.Book03, null, modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Text(stringResource(R.string.kb_empty), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.kb_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(kbs, key = { it.id }) { kb ->
                    val dismissState = rememberSwipeToDismissBoxState()
                    val scope = rememberCoroutineScope()

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(end = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            ) {
                                FilledTonalIconButton(
                                    onClick = { scope.launch { dismissState.reset() } }
                                ) {
                                    Icon(HugeIcons.Cancel01, null)
                                }
                                FilledTonalIconButton(
                                    onClick = { vm.deleteKnowledgeBase(kb.id) }
                                ) {
                                    Icon(HugeIcons.Delete01, null)
                                }
                            }
                        },
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true,
                    ) {
                        KnowledgeBaseCard(
                            kb = kb,
                            onClick = {
                                vm.selectKnowledgeBase(kb.id)
                                navController.navigate(Screen.KnowledgeBaseDetail(kb.id))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeBaseCard(
    kb: KnowledgeBaseEntity,
    onClick: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(HugeIcons.Bookshelf01, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(kb.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(kb.modelId.take(8) + "...", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(HugeIcons.File02, null, modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(dateFormat.format(Date(kb.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }

        }
    }
}

@Composable
private fun CreateKnowledgeBaseDialog(
    name: String,
    onNameChange: (String) -> Unit,
    models: List<ModelDisplay>,
    selectedModel: ModelDisplay?,
    onModelChange: (ModelDisplay) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var modelMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kb_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.kb_name)) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.kb_name_placeholder)) },
                )

                // 模型选择
                Box {
                    OutlinedTextField(
                        value = selectedModel?.let { "${it.displayName} (${it.providerName})" } ?: "",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.kb_model)) },
                        readOnly = true,
                        enabled = models.isNotEmpty(),
                        trailingIcon = {
                            IconButton(onClick = { modelMenuExpanded = true }) {
                                Icon(HugeIcons.ArrowDown01, null, modifier = Modifier.size(16.dp))
                            }
                        },
                    )
                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.8f),
                    ) {
                        for (model in models) {
                            DropdownMenuItem(
                                text = { Text("${model.displayName} (${model.providerName})") },
                                onClick = {
                                    onModelChange(model)
                                    modelMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = name.isNotBlank() && selectedModel != null,
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
