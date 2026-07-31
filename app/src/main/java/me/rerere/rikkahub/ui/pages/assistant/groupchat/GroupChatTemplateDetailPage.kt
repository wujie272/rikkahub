package me.rerere.rikkahub.ui.pages.assistant.groupchat

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.buildSeatDisplayNames
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
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
    val chatService = koinInject<ChatService>()

    val defaultAssistantName = "助手"
    val currentTemplate = template

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddMemberSheet by remember { mutableStateOf(false) }
    var expandedSeatId by remember(template?.id) { mutableStateOf<Uuid?>(null) }
    var showIntroDialog by remember(template?.id) { mutableStateOf(false) }
    var showHostPromptDialog by remember(template?.id) { mutableStateOf(false) }
    var showSeatPromptDialog by remember(template?.id) { mutableStateOf(false) }
    var seatPromptDialogSeatId by remember(template?.id) { mutableStateOf<Uuid?>(null) }
    var showModeDialog by remember { mutableStateOf(false) }
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

            // ── Quick setup (debate presets) ──
            item {
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

                        if (currentTemplate.seats.isNotEmpty() && showQuickSetup) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            Text(
                                "辩论预设（将自动配置角色提示词）",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )

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

            // ── Seat cards ──
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
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // ── Dialogs ──
    DeleteTemplateDialog(
        show = showDeleteDialog,
        onDismiss = { showDeleteDialog = false },
        onConfirm = {
            vm.deleteTemplate()
            navController.popBackStack()
        },
    )

    AddMemberBottomSheet(
        show = showAddMemberSheet,
        onDismiss = { showAddMemberSheet = false },
        availableAssistants = settings.assistants.filter { assistant ->
            template?.seats?.none { it.assistantId == assistant.id } ?: true
        },
        defaultAssistantName = defaultAssistantName,
        onAddSeat = { vm.addSeat(it) },
    )

    EditIntroDialog(
        show = showIntroDialog,
        template = currentTemplate,
        onDismiss = { showIntroDialog = false },
        onSave = { vm.updateIntro(it) },
    )

    EditHostPromptDialog(
        show = showHostPromptDialog,
        template = currentTemplate,
        onDismiss = { showHostPromptDialog = false },
        onSave = { vm.updateHostSystemPrompt(it) },
    )

    EditSeatPromptDialog(
        show = showSeatPromptDialog,
        template = currentTemplate,
        seatId = seatPromptDialogSeatId,
        assistants = settings.assistants,
        onDismiss = {
            showSeatPromptDialog = false
            seatPromptDialogSeatId = null
        },
        onSave = { id, prompt ->
            vm.updateSeatOverrides(id) { overrides ->
                overrides.copy(systemPrompt = prompt)
            }
        },
    )

    StartGroupChatModeDialog(
        show = showModeDialog,
        onDismiss = { showModeDialog = false },
        onStartChat = {
            scope.launch {
                val convId = chatService.startGroupChatConversation(
                    templateId = currentTemplate!!.id,
                    userMessage = emptyList(),
                )
                navController.navigate(Screen.Chat(id = convId.toString()))
            }
        },
    )
}
