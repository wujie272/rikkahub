package me.rerere.rikkahub.ui.pages.knowledge

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.context.LocalNavController
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeEditPage(
    kbId: String? = null,
    vm: KnowledgeVM = koinViewModel(),
) {
    val form by vm.editForm.collectAsStateWithLifecycle()
    val models by vm.availableModels.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAdvanced by remember { mutableStateOf(false) }
    val isEditing = kbId != null
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
    val navController = LocalNavController.current
    val snackbar by vm.snackbar.collectAsStateWithLifecycle()

    LaunchedEffect(snackbar) {
        snackbar?.let { msg ->
            toaster.show(message = msg, type = com.dokar.sonner.ToastType.Success)
            vm.dismissSnackbar()
        }
    }

    // Load KB data if editing
    val selectedKb = vm.selectedKb.value
    LaunchedEffect(kbId, selectedKb) {
        if (kbId != null && selectedKb != null && selectedKb.id == kbId) {
            vm.initEditForm(selectedKb)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(if (isEditing) stringResource(R.string.kb_edit) else stringResource(R.string.kb_create)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 名称
            FormItem(
                label = { Text(stringResource(R.string.kb_name)) },
                description = { Text(stringResource(R.string.kb_name_hint)) }
            ) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { newName ->
                        vm.updateEditForm { f -> f.copy(name = newName) }
                    },
                    label = { Text(stringResource(R.string.kb_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.kb_name_placeholder)) }
                )
            }

            // 描述
            FormItem(
                label = { Text(stringResource(R.string.kb_description)) },
                description = { Text(stringResource(R.string.kb_description_hint)) }
            ) {
                OutlinedTextField(
                    value = form.description,
                    onValueChange = { newDesc ->
                        vm.updateEditForm { f -> f.copy(description = newDesc) }
                    },
                    label = { Text(stringResource(R.string.kb_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
            }

            // 嵌入模型
            FormItem(
                label = { Text(stringResource(R.string.kb_embedding_model)) },
                description = { Text(stringResource(R.string.kb_embedding_model_desc)) }
            ) {
                ExposedDropdownMenuBox(
                    expanded = modelDropdownExpanded,
                    onExpandedChange = { modelDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = form.modelDisplayName.ifBlank { stringResource(R.string.kb_model_select) },
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text(stringResource(R.string.kb_embedding_model)) }
                    )
                    ExposedDropdownMenu(
                        expanded = modelDropdownExpanded,
                        onDismissRequest = { modelDropdownExpanded = false }
                    ) {
                        models.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.displayName)
                                        Text(model.providerName, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    vm.updateEditForm { f ->
                                        f.copy(modelId = model.id, modelDisplayName = model.displayName)
                                    }
                                    modelDropdownExpanded = false
                                }
                            )
                        }
                        if (models.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.kb_no_embedding_model)) },
                                onClick = { modelDropdownExpanded = false }
                            )
                        }
                    }
                }
                if (form.modelId.isNotBlank()) {
                    Text(stringResource(R.string.kb_dimensions, form.dimensions), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 高级设置折叠
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Icon(
                        if (showAdvanced) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        null, modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(if (showAdvanced) stringResource(R.string.kb_advanced_settings_collapse) else stringResource(R.string.kb_advanced_settings))
                }
            }

            AnimatedVisibility(visible = showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // 分块大小
                    FormItem(
                        label = { Text(stringResource(R.string.kb_chunk_size)) },
                        description = { Text(stringResource(R.string.kb_chunk_size_desc, form.chunkSize)) }
                    ) {
                        Slider(
                            value = form.chunkSize.toFloat(),
                            onValueChange = { v -> vm.updateEditForm { f -> f.copy(chunkSize = v.toInt()) } },
                            valueRange = 200f..4000f,
                            steps = 18,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 块重叠
                    FormItem(
                        label = { Text(stringResource(R.string.kb_chunk_overlap)) },
                        description = { Text(stringResource(R.string.kb_chunk_overlap_desc, form.chunkOverlap)) }
                    ) {
                        Slider(
                            value = form.chunkOverlap.toFloat(),
                            onValueChange = { v -> vm.updateEditForm { f -> f.copy(chunkOverlap = v.toInt()) } },
                            valueRange = 0f..1000f,
                            steps = 9,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 分块策略
                    FormItem(
                        label = { Text(stringResource(R.string.kb_chunk_strategy)) },
                        description = { Text(stringResource(R.string.kb_chunk_strategy_desc)) }
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val strategyLabels = mapOf(
                                "fixed" to stringResource(R.string.kb_strategy_fixed),
                                "paragraph" to stringResource(R.string.kb_strategy_paragraph),
                                "markdown" to stringResource(R.string.kb_strategy_markdown),
                                "code" to stringResource(R.string.kb_strategy_code),
                            )
                            listOf("fixed", "paragraph", "markdown", "code").forEach { value ->
                                val label = strategyLabels[value] ?: value
                                TextButton(
                                    onClick = { vm.updateEditForm { f -> f.copy(chunkStrategy = value) } },
                                    enabled = form.chunkStrategy == value
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }

                    // 相似度阈值
                    FormItem(
                        label = { Text(stringResource(R.string.kb_threshold)) },
                        description = { Text("${String.format("%.2f", form.threshold)} 以上的结果才会返回") }
                    ) {
                        Slider(
                            value = form.threshold,
                            onValueChange = { v -> vm.updateEditForm { f -> f.copy(threshold = v) } },
                            valueRange = 0.1f..0.95f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 返回文档数
                    FormItem(
                        label = { Text(stringResource(R.string.kb_document_count)) },
                        description = { Text(stringResource(R.string.kb_document_count_desc, form.documentCount)) }
                    ) {
                        Slider(
                            value = form.documentCount.toFloat(),
                            onValueChange = { v -> vm.updateEditForm { f -> f.copy(documentCount = v.toInt()) } },
                            valueRange = 1f..20f,
                            steps = 18,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // 保存按钮
            Button(
                onClick = {
                    vm.saveKnowledgeBase(
                        isEditing = isEditing,
                        id = kbId,
                        onDone = { navController.popBackStack() }
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Text(if (isEditing) stringResource(R.string.kb_save) else stringResource(R.string.kb_create_action))
            }
        }
    }
}
