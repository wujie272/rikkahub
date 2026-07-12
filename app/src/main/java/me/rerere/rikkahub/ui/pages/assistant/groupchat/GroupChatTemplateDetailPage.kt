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
import androidx.compose.runtime.rememberCoroutineScope
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
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.Screen
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import me.rerere.rikkahub.data.model.GroupChatSeat
import me.rerere.rikkahub.data.model.GroupChatMode
import me.rerere.rikkahub.data.model.GroupChatRuntimeConfig
import me.rerere.hugeicons.stroke.Zap
import me.rerere.hugeicons.stroke.Star
import me.rerere.hugeicons.stroke.Bot
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings

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
    val scope = rememberCoroutineScope()

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

            // ── 辩论快速配置 ──
            item {
                val chatService = koinInject<ChatService>()
                var showQuickSetup by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                HugeIcons.Zap,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "快速配置",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (currentTemplate.seats.isNotEmpty()) {
                                TextButton(
                                    onClick = { showQuickSetup = !showQuickSetup },
                                ) {
                                    Text(
                                        if (showQuickSetup) "收起" else "展开",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }

                        if (currentTemplate.seats.isEmpty()) {
                            Text(
                                text = "先添加助手成员，然后使用辩论模板快速配置角色",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        // 只在有成员且展开时显示快速配置按钮
                        if (currentTemplate.seats.isNotEmpty() && showQuickSetup) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            Text(
                                "辩论预设（将自动配置角色提示词）",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )

                            // 基础辩论：正+反+主持人
                            DebatePresetButton(
                                emoji = "🎯",
                                name = "基础辩论",
                                desc = "3角色：正方 + 反方 + 主持人",
                                accentColor = MaterialTheme.colorScheme.primary,
                                seats = currentTemplate.seats.take(3),
                                settings = settings,
                                onApply = { prompts ->
                                    scope.launch(Dispatchers.IO) {
                                        applyDebatePrompts(vm, currentTemplate.seats, prompts)
                                    }
                                    showQuickSetup = false
                                },
                            )

                            Spacer(Modifier.height(8.dp))

                            // 专业辩论：正+反+中立+主持人
                            DebatePresetButton(
                                emoji = "🏛️",
                                name = "专业辩论",
                                desc = "4角色：正方 + 反方 + 中立分析师 + 主持人",
                                accentColor = MaterialTheme.colorScheme.secondary,
                                seats = currentTemplate.seats.take(4),
                                settings = settings,
                                onApply = { prompts ->
                                    scope.launch(Dispatchers.IO) {
                                        applyDebatePrompts(vm, currentTemplate.seats, prompts)
                                    }
                                    showQuickSetup = false
                                },
                            )

                            Spacer(Modifier.height(8.dp))

                            // 专家论坛：法律+经济+技术+主持人
                            DebatePresetButton(
                                emoji = "🎓",
                                name = "专家论坛",
                                desc = "4角色：法律 + 经济 + 技术专家 + 主持人",
                                accentColor = MaterialTheme.colorScheme.tertiary,
                                seats = currentTemplate.seats.take(4),
                                settings = settings,
                                onApply = { prompts ->
                                    scope.launch(Dispatchers.IO) {
                                        applyDebatePrompts(vm, currentTemplate.seats, prompts)
                                    }
                                    showQuickSetup = false
                                },
                            )
                        }
                    }
                }
            }

            itemsIndexed(currentTemplate.seats, key = { _, seat -> seat.id }) { index, seat ->
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


            // ── Start group chat button ──
            item {
                val chatService = koinInject<ChatService>()
                var showModeDialog by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(
                        onClick = {
                            if (currentTemplate.seats.isEmpty()) return@Button
                            showModeDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        enabled = currentTemplate.seats.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            HugeIcons.Add01,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("开始群聊")
                    }
                }

                // ── Mode selection dialog ──
                if (showModeDialog) {
                    var showTopicInput by remember { mutableStateOf(false) }
                    var topicText by remember { mutableStateOf("") }

                    if (showTopicInput) {
                        AlertDialog(
                            onDismissRequest = {
                                showTopicInput = false
                                showModeDialog = false
                            },
                            title = { Text("开始群聊") },
                            text = {
                                OutlinedTextField(
                                    value = topicText,
                                    onValueChange = { topicText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("输入讨论主题或问题") },
                                    placeholder = { Text("例如：人工智能对人类未来的影响") },
                                    minLines = 3,
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            val userMsg = if (topicText.isNotBlank()) {
                                                listOf(me.rerere.ai.ui.UIMessagePart.Text(topicText))
                                            } else {
                                                emptyList()
                                            }
                                            val convId = chatService.startGroupChatConversation(
                                                templateId = currentTemplate.id,
                                                userMessage = userMsg,
                                            )
                                            navController.navigate(Screen.Chat(id = convId.toString()))
                                        }
                                        showTopicInput = false
                                        showModeDialog = false
                                    }
                                ) {
                                    Text("开始")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showTopicInput = false
                                    showModeDialog = false
                                }) {
                                    Text("取消")
                                }
                            }
                        )
                    } else {
                        AlertDialog(
                            onDismissRequest = { showModeDialog = false },
                            title = { Text("选择群聊模式") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        onClick = {
                                            showTopicInput = true
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("💬 自由讨论（轮流发言）")
                                    }
                                    TextButton(
                                        onClick = {
                                            showTopicInput = true
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("🎯 AI辩论（正反方交锋）")
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                TextButton(onClick = { showModeDialog = false }) {
                                    Text("取消")
                                }
                            }
                        )
                    }
                }
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


// ──── 辩论快速配置组件 ────

/**
 * 辩论预设按钮
 */
@Composable
private fun DebatePresetButton(
    emoji: String,
    name: String,
    desc: String,
    accentColor: androidx.compose.ui.graphics.Color,
    seats: List<GroupChatSeat>,
    settings: Settings,
    onApply: (List<String>) -> Unit,
) {
    Card(
        onClick = {
            val prompts = generateDebatePrompts(name, seats)
            if (prompts.size == seats.size) {
                onApply(prompts)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                HugeIcons.Add01,
                contentDescription = "应用",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    // 显示当前座位及其模型状态
    if (seats.isNotEmpty()) {
        Column(modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)) {
            seats.forEachIndexed { i, seat ->
                val assistant = settings.assistants.firstOrNull { it.id == seat.assistantId }
                val modelId = seat.overrides.chatModelId ?: assistant?.chatModelId ?: settings.chatModelId
                val modelName = settings.findModelById(modelId)?.displayName ?: ""
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 1.dp),
                ) {
                    Text(
                        "${i + 1}. ${assistant?.name?.ifBlank { "助手" } ?: "助手"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (modelName.isNotBlank()) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "· $modelName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 根据预设名称生成相应的辩论角色提示词
 */
private fun generateDebatePrompts(presetName: String, seats: List<GroupChatSeat>): List<String> {
    val systemPrompts = when (presetName) {
        "基础辩论" -> generateBasicDebatePrompts(seats.size)
        "专业辩论" -> generateProfessionalDebatePrompts(seats.size)
        "专家论坛" -> generateExpertForumPrompts(seats.size)
        else -> generateBasicDebatePrompts(seats.size)
    }
    return systemPrompts.take(seats.size)
}

private fun generateBasicDebatePrompts(count: Int): List<String> {
    val prompts = mutableListOf<String>()
    if (count >= 1) {
        prompts.add("""你是一位专业的正方辩论者。

你的立场：支持辩论观点。

辩论风格：
- 逻辑清晰，论证有力
- 引用具体事实、数据和案例
- 保持理性和专业的态度
- 每次发言控制在150-200字

请始终站在正方立场，为你的观点据理力争！""")
    }
    if (count >= 2) {
        prompts.add("""你是一位犀利的反方辩论者。

你的立场：反对辩论观点。

辩论风格：
- 思维敏锐，善于发现问题
- 用事实和逻辑拆解对方论证
- 提出有力的反驳和质疑
- 每次发言控制在150-200字

请始终站在反方立场，用理性和事实挑战对方观点！""")
    }
    if (count >= 3) {
        prompts.add("""你是一位专业的辩论主持人。

核心职责：
- 引导辩论方向和节奏
- 总结各方要点和分歧
- 判断讨论是否充分
- 决定何时结束辩论

重要：只有经过至少3轮充分讨论后才考虑结束辩论。""")
    }
    return prompts
}

private fun generateProfessionalDebatePrompts(count: Int): List<String> {
    val prompts = generateBasicDebatePrompts(count).toMutableList()
    if (count >= 4) {
        // Insert neutral analyst at position 2
        prompts.add(2, """你是一位客观中立的分析师。

分析风格：
- 保持绝对中立，不偏向任何一方
- 用理性和逻辑评估论证质量
- 指出可能被忽视的角度
- 寻找双方的共同点
- 每次发言控制在150-200字

请保持中立立场，为辩论提供客观理性的分析！""")
    }
    return prompts
}

private fun generateExpertForumPrompts(count: Int): List<String> {
    val prompts = mutableListOf<String>()
    if (count >= 1) {
        prompts.add("""你是一位资深法律专家，从法律角度参与辩论。

专业视角：
- 从法律法规角度分析问题
- 引用相关法条和判例
- 分析法律风险和合规性
- 每次发言控制在150-200字""")
    }
    if (count >= 2) {
        prompts.add("""你是一位经济学专家，从经济角度参与辩论。

专业视角：
- 分析经济成本和收益
- 评估市场影响和效率
- 考虑宏观和微观经济效应
- 每次发言控制在150-200字""")
    }
    if (count >= 3) {
        prompts.add("""你是一位技术专家，从技术角度参与辩论。

专业视角：
- 分析技术可行性和难度
- 评估技术风险和挑战
- 考虑技术发展趋势
- 每次发言控制在150-200字""")
    }
    if (count >= 4) {
        prompts.add("""你是一位专业的辩论主持人。

核心职责：
- 引导专家讨论方向
- 总结各领域专家的观点
- 推动跨领域交流
- 每次发言控制在150-200字""")
    }
    return prompts
}

/**
 * 应用辩论提示词到座位覆盖
 */
private suspend fun applyDebatePrompts(
    vm: GroupChatTemplateDetailVM,
    seats: List<GroupChatSeat>,
    prompts: List<String>,
) {
    seats.take(prompts.size).forEachIndexed { index, seat ->
        if (index < prompts.size) {
            vm.updateSeatOverrides(seat.id) { overrides ->
                overrides.copy(systemPrompt = prompts[index])
            }
        }
    }
}
