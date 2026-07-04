package me.rerere.rikkahub.ui.pages.assistant.groupchat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.GroupChatSeatOverrides
import me.rerere.rikkahub.data.model.buildSeatDisplayNames
import me.rerere.rikkahub.ui.components.ai.McpPickerButton
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.SearchPickerButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatTemplateDetailPage(id: String) {
    val vm: GroupChatTemplateDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val template by vm.template.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    val defaultAssistantName = "助手"
    val currentTemplate = template

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddMemberSheet by remember { mutableStateOf(false) }
    var expandedSeatId by remember(template?.id) { mutableStateOf<Uuid?>(null) }
    var showIntroDialog by remember(template?.id) { mutableStateOf(false) }
    var showHostPromptDialog by remember(template?.id) { mutableStateOf(false) }
    var showSeatPromptDialog by remember(template?.id) { mutableStateOf(false) }
    var seatPromptDialogSeatId by remember(template?.id) { mutableStateOf<Uuid?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = template?.name?.ifBlank { "新群聊模板" } ?: "新群聊模板",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = "删除模板",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                colors = CustomColors.topBarColors,
            )
        }
    ) { innerPadding ->
        if (currentTemplate == null) {
            Text(
                text = "模板加载中...",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Basic info ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("基本信息", style = MaterialTheme.typography.titleSmall)

                        OutlinedTextField(
                            value = currentTemplate.name,
                            onValueChange = vm::updateName,
                            label = { Text("群聊名称") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            singleLine = true,
                        )

                        TextButton(
                            onClick = { showIntroDialog = true },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(
                                text = if (currentTemplate.intro.isNotBlank()) currentTemplate.intro else "点击编辑简介（可选）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (currentTemplate.intro.isNotBlank()) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // ── Host model ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("路由模型", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "负责决定每次由哪位成员发言。留空则使用默认逻辑。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "选择路由模型：",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.width(8.dp))
                            ModelSelector(
                                modelId = currentTemplate.hostModelId,
                                providers = settings.providers,
                                type = ModelType.CHAT,
                                allowClear = true,
                                onSelect = { model ->
                                    if (model.displayName.isBlank() && model.modelId.isBlank()) {
                                        vm.updateHostModel(null)
                                    } else {
                                        vm.updateHostModel(model.id)
                                    }
                                },
                            )
                        }

                        TextButton(
                            onClick = { showHostPromptDialog = true },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(
                                text = if (currentTemplate.hostSystemPrompt.isNotBlank()) {
                                    "编辑路由提示词"
                                } else {
                                    "添加路由提示词（可选）"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            // ── Members section ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "成员 (${currentTemplate.seats.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = { showAddMemberSheet = true }) {
                        Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加成员")
                    }
                }
            }

            if (currentTemplate.seats.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "暂无成员",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "点击上方「添加成员」加入助手",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            items(currentTemplate.seats, key = { it.id }) { seat ->
                val assistant = settings.assistants.firstOrNull { it.id == seat.assistantId }
                val displayNames = currentTemplate.buildSeatDisplayNames(
                    assistantsById = settings.assistants.associateBy { it.id },
                    defaultName = defaultAssistantName,
                )
                val displayName = displayNames[seat.id] ?: "未知"
                val isExpanded = expandedSeatId == seat.id

                SeatCard(
                    seatId = seat.id,
                    displayName = displayName,
                    assistant = assistant,
                    defaultEnabled = seat.defaultEnabled,
                    isExpanded = isExpanded,
                    onToggleExpand = { expandedSeatId = if (isExpanded) null else seat.id },
                    onToggleEnabled = { vm.setSeatEnabled(seat.id, it) },
                    onRemove = { vm.removeSeat(seat.id); expandedSeatId = null },
                    overrides = seat.overrides,
                    onUpdateOverrides = { transform -> vm.updateSeatOverrides(seat.id, transform) },
                    settings = settings,
                    onEditPrompt = {
                        seatPromptDialogSeatId = seat.id
                        showSeatPromptDialog = true
                    },
                )
            }


            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // ── Delete dialog ──
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除模板") },
            text = { Text("确定要删除这个群聊模板吗？所有相关配置将丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTemplate()
                    navController.popBackStack()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // ── Add member sheet ──
    if (showAddMemberSheet) {
        val sheetState = rememberModalBottomSheetState()
        val existingAssistantIds = template?.seats?.map { seat -> seat.assistantId }?.toSet() ?: emptySet()
        val availableAssistants = settings.assistants.filter { it.id !in existingAssistantIds }

        ModalBottomSheet(
            onDismissRequest = { showAddMemberSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text("选择助手加入群聊", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                if (availableAssistants.isEmpty()) {
                    Text(
                        text = "所有助手已加入群聊",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }

                availableAssistants.forEach { assistant ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.addSeat(assistant.id)
                                showAddMemberSheet = false
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UIAvatar(
                            name = assistant.name.ifBlank { defaultAssistantName },
                            value = assistant.avatar,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = assistant.name.ifBlank { defaultAssistantName },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ── Intro dialog ──
    if (showIntroDialog && currentTemplate != null) {
        var localIntro by remember(currentTemplate.id) { mutableStateOf(currentTemplate.intro) }
        AlertDialog(
            onDismissRequest = { showIntroDialog = false },
            title = { Text("编辑简介") },
            text = {
                OutlinedTextField(
                    value = localIntro,
                    onValueChange = { localIntro = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateIntro(localIntro)
                    showIntroDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showIntroDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ── Host prompt dialog ──
    if (showHostPromptDialog && currentTemplate != null) {
        var localPrompt by remember(currentTemplate.id) { mutableStateOf(currentTemplate.hostSystemPrompt) }
        AlertDialog(
            onDismissRequest = { showHostPromptDialog = false },
            title = { Text("路由模型提示词") },
            text = {
                OutlinedTextField(
                    value = localPrompt,
                    onValueChange = { localPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateHostSystemPrompt(localPrompt)
                    showHostPromptDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showHostPromptDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ── Seat prompt dialog ──
    if (showSeatPromptDialog && currentTemplate != null) {
        val seatId = seatPromptDialogSeatId
        val seat = seatId?.let { id -> currentTemplate.seats.firstOrNull { it.id == id } }
        val assistant = seat?.assistantId?.let { assistantId -> settings.assistants.firstOrNull { it.id == assistantId } }
        val basePrompt = assistant?.systemPrompt.orEmpty()
        val currentOverride = seat?.overrides?.systemPrompt
        var localPrompt by remember(currentTemplate.id, seatId) { mutableStateOf(currentOverride ?: basePrompt) }

        AlertDialog(
            onDismissRequest = {
                showSeatPromptDialog = false
                seatPromptDialogSeatId = null
            },
            title = { Text("编辑成员提示词") },
            text = {
                OutlinedTextField(
                    value = localPrompt,
                    onValueChange = { localPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                )
            },
            confirmButton = {},
            dismissButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = { localPrompt = basePrompt },
                        enabled = localPrompt != basePrompt,
                    ) {
                        Text("恢复默认")
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = {
                            showSeatPromptDialog = false
                            seatPromptDialogSeatId = null
                        }) {
                            Text("取消")
                        }
                        TextButton(onClick = {
                            val resolvedSeatId = seatId ?: return@TextButton
                            val normalized = localPrompt.takeIf { it != basePrompt }
                            vm.updateSeatOverrides(resolvedSeatId) { overrides ->
                                overrides.copy(systemPrompt = normalized)
                            }
                            showSeatPromptDialog = false
                            seatPromptDialogSeatId = null
                        }) {
                            Text("保存")
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun SeatCard(
    seatId: Uuid,
    displayName: String,
    assistant: Assistant?,
    defaultEnabled: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
    overrides: GroupChatSeatOverrides,
    onUpdateOverrides: ((GroupChatSeatOverrides) -> GroupChatSeatOverrides) -> Unit,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    onEditPrompt: () -> Unit,
) {
    val mcpManager = koinInject<me.rerere.rikkahub.data.ai.mcp.McpManager>()
    val effectiveModelId = overrides.chatModelId ?: assistant?.chatModelId ?: settings.chatModelId

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(spring()),
        colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ── Header row ──
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggleExpand() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UIAvatar(
                    name = displayName,
                    value = assistant?.avatar ?: me.rerere.rikkahub.data.model.Avatar.Dummy,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(displayName, style = MaterialTheme.typography.bodyLarge)
                    settings.findModelById(effectiveModelId)?.let { model ->
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Switch(
                    checked = defaultEnabled,
                    onCheckedChange = onToggleEnabled,
                )

                IconButton(onClick = onRemove) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = "移除成员",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // ── Expanded overrides ──
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    // Model override
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "聊天模型",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        ModelSelector(
                            modelId = effectiveModelId,
                            providers = settings.providers,
                            type = ModelType.CHAT,
                            onSelect = { model ->
                                onUpdateOverrides { it.copy(chatModelId = model.id) }
                            },
                        )
                        IconButton(
                            enabled = overrides.chatModelId != null,
                            onClick = { onUpdateOverrides { it.copy(chatModelId = null) } },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                HugeIcons.Cancel01,
                                contentDescription = "清除",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Max tokens
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Max Tokens",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = overrides.maxTokens?.toString() ?: "",
                            onValueChange = { raw ->
                                val tokens = raw.toIntOrNull()?.takeIf { it > 0 }
                                onUpdateOverrides { it.copy(maxTokens = tokens) }
                            },
                            modifier = Modifier.width(120.dp),
                            singleLine = true,
                            placeholder = { Text("默认") },
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Search
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "网络搜索",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        SearchPickerButton(
                            enableSearch = overrides.searchEnabled,
                            settings = settings,
                            onToggleSearch = { enabled ->
                                onUpdateOverrides { it.copy(searchEnabled = enabled) }
                            },
                            onUpdateSearchService = { index ->
                                onUpdateOverrides { overrides ->
                                    overrides.copy(
                                        searchEnabled = true,
                                        searchMode = 2,
                                    )
                                }
                            },
                            model = settings.findModelById(effectiveModelId),
                        )
                    }

                    // MCP
                    if (settings.mcpServers.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        val mcpAssistant = (assistant ?: Assistant(id = seatId)).copy(
                            id = assistant?.id ?: seatId,
                            name = assistant?.name.orEmpty(),
                            avatar = assistant?.avatar ?: me.rerere.rikkahub.data.model.Avatar.Dummy,
                            mcpServers = overrides.mcpServerIds,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "MCP 服务器",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            McpPickerButton(
                                assistant = mcpAssistant,
                                servers = settings.mcpServers,
                                mcpManager = mcpManager,
                                onUpdateAssistant = { updated ->
                                    onUpdateOverrides { it.copy(mcpServerIds = updated.mcpServers) }
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Memory
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "使用记忆",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = overrides.memoryEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateOverrides { it.copy(memoryEnabled = enabled) }
                            },
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))

                    // Bottom buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = onEditPrompt) {
                            Text(text = "编辑提示词")
                        }

                        TextButton(onClick = onRemove) {
                            Text(
                                text = "移除成员",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
