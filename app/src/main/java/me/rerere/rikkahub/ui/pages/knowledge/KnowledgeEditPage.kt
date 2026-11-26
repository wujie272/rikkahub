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
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenu
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
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.context.LocalNavController
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults

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

    // Load KB data if editing — 仅在表单未初始化时加载
    val selectedKb = vm.selectedKb.value
    val formName = form.name
    LaunchedEffect(kbId, selectedKb) {
        if (kbId != null && selectedKb != null && selectedKb.id == kbId && formName.isBlank()) {
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
                .padding(bottom = 32.dp),
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
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
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
                                    vm.detectModelDimensions(model.id)
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

            // 向量维度（高级设置外，紧跟模型选择）
            FormItem(
                label = { Text(stringResource(R.string.kb_dimension_label)) },
                description = { Text(stringResource(R.string.kb_dimension_desc, form.dimensions)) }
            ) {
                Text(
                    text = "${form.dimensions} 维",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = form.dimensions.toFloat(),
                    onValueChange = { v ->
                        // 对齐到 Qwen3 支持的维度值
                        val supported = listOf(64, 128, 256, 512, 768, 1024, 1536, 2048, 2560, 4096)
                        val snapped = supported.minBy { kotlin.math.abs(it - v.toInt()) }
                        vm.updateEditForm { f -> f.copy(dimensions = snapped) }
                    },
                    valueRange = 64f..4096f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )
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
                        Text(
                            text = "${form.chunkSize} 字符",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
                        Text(
                            text = "${form.chunkOverlap} 字符",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
                        description = {
                            val desc = when (form.chunkStrategy) {
                                "markdown" -> "按 Markdown 标题层级切分，保留父标题上下文（推荐）"
                                "fixed" -> "按固定字符数切分，兜底方案"
                                else -> "如何将文档切分成块"
                            }
                            Text(desc)
                        }
                    ) {
                        var strategyDropdownExpanded by remember { mutableStateOf(false) }
                        val strategyOptions = listOf(
                            "markdown" to stringResource(R.string.kb_strategy_markdown),
                            "fixed" to stringResource(R.string.kb_strategy_fixed),
                        )
                        val currentLabel = strategyOptions.first { it.first == form.chunkStrategy }.second
                        ExposedDropdownMenuBox(
                            expanded = strategyDropdownExpanded,
                            onExpandedChange = { strategyDropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = currentLabel,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = strategyDropdownExpanded) },
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                label = { Text(stringResource(R.string.kb_chunk_strategy)) }

                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = form.chunkStrategy == "fixed",
                                onClick = { vm.updateEditForm { f -> f.copy(chunkStrategy = "fixed") } },
                                label = { Text(stringResource(R.string.kb_strategy_fixed)) },
                            )
                            FilterChip(
                                selected = form.chunkStrategy == "paragraph",
                                onClick = { vm.updateEditForm { f -> f.copy(chunkStrategy = "paragraph") } },
                                label = { Text("Paragraph") },
                            )
                            FilterChip(
                                selected = form.chunkStrategy == "markdown",
                                onClick = { vm.updateEditForm { f -> f.copy(chunkStrategy = "markdown") } },
                                label = { Text(stringResource(R.string.kb_strategy_markdown)) },
                            )
                        }
                    }

                    // 相似度阈值
                    FormItem(
                        label = { Text(stringResource(R.string.kb_threshold)) },
                        description = { Text("${String.format("%.2f", form.threshold)} 以上的结果才会返回") }
                    ) {
                        Text(
                            text = String.format("%.2f", form.threshold),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
                        Text(
                            text = "${form.documentCount} 条",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
