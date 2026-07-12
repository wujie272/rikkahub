package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowLeft
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.DEBATE_TOPICS
import me.rerere.rikkahub.data.model.GroupChatRuntimeConfig
import me.rerere.rikkahub.data.model.GroupChatSeat
import me.rerere.rikkahub.data.model.GroupChatSeatOverrides
import me.rerere.rikkahub.data.model.GroupChatTemplate
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingDebatePage() {
    val settingsStore = koinInject<SettingsStore>()
    val chatService = koinInject<ChatService>()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(0) } // 0=模板, 1=主题
    var showCreateDialog by remember { mutableStateOf(false) }
    var showTopicDialog by remember { mutableStateOf(false) }
    var selectedTopic by remember { mutableStateOf("") }
    var customTopic by remember { mutableStateOf("") }
    var expandedCategory by remember { mutableStateOf<String?>(null) }
    var startTitle by remember { mutableStateOf("") }

    // 只显示有座位的模板（可以开始辩论的）
    val debateTemplates = remember(settings.groupChatTemplates) {
        settings.groupChatTemplates.filter { it.seats.isNotEmpty() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI辩论", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = { showCreateDialog = true }) {
                        Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建")
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            // ── Tab bar ──
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("辩论模板 (${debateTemplates.size})") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("辩论主题") },
                )
            }

            Spacer(Modifier.height(8.dp))

            when (selectedTab) {
                0 -> DebateTemplateList(
                    templates = debateTemplates,
                    settingsStore = settingsStore,
                    chatService = chatService,
                    navController = navController,
                    scope = scope,
                    onDelete = { template ->
                        scope.launch(Dispatchers.IO) {
                            settingsStore.update { s ->
                                s.copy(groupChatTemplates = s.groupChatTemplates.filterNot { it.id == template.id })
                            }
                        }
                    },
                )
                1 -> DebateTopicList(
                    expandedCategory = expandedCategory,
                    onToggleCategory = { expandedCategory = if (expandedCategory == it) null else it },
                    onSelectTopic = { topic ->
                        selectedTopic = topic
                        showTopicDialog = true
                    },
                )
            }
        }
    }

    // ── 新建模板对话框 ──
    if (showCreateDialog) {
        var templateName by remember { mutableStateOf("") }
        var selectedPreset by remember { mutableStateOf("basic") }
        var assistant1 by remember { mutableStateOf(settings.assistants.firstOrNull()?.id) }
        var assistant2 by remember { mutableStateOf(settings.assistants.getOrNull(1)?.id) }
        var assistant3 by remember { mutableStateOf<Uuid?>(null) }
        var assistant4 by remember { mutableStateOf<Uuid?>(null) }

        val availableAssistants = settings.assistants.filter { it.id !in DEFAULT_ASSISTANTS_IDS || it.id == assistant1 || it.id == assistant2 }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建辩论模板") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = templateName,
                        onValueChange = { templateName = it },
                        label = { Text("模板名称") },
                        placeholder = { Text("例如：科技辩论、法律论坛") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Text("预设场景", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedPreset == "basic",
                            onClick = { selectedPreset = "basic" },
                            label = { Text("基础辩论") },
                        )
                        FilterChip(
                            selected = selectedPreset == "professional",
                            onClick = { selectedPreset = "professional" },
                            label = { Text("专业辩论") },
                        )
                        FilterChip(
                            selected = selectedPreset == "expert",
                            onClick = { selectedPreset = "expert" },
                            label = { Text("专家论坛") },
                        )
                    }

                    Text("选择助手角色", style = MaterialTheme.typography.labelMedium)
                    availableAssistants.forEach { a ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                when {
                                    assistant1 == null -> assistant1 = a.id
                                    assistant2 == null && a.id != assistant1 -> assistant2 = a.id
                                    assistant3 == null && a.id != assistant1 && a.id != assistant2 -> assistant3 = a.id
                                }
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(a.name.ifBlank { "助手" }, modifier = Modifier.weight(1f))
                            if (a.id == assistant1 || a.id == assistant2 || a.id == assistant3 || a.id == assistant4) {
                                Text("已选", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val seats = buildList {
                                assistant1?.let { id -> add(GroupChatSeat(assistantId = id)) }
                                assistant2?.let { id -> add(GroupChatSeat(assistantId = id)) }
                                assistant3?.let { id -> add(GroupChatSeat(assistantId = id)) }
                                assistant4?.let { id -> add(GroupChatSeat(assistantId = id)) }
                            }

                            // Apply debate prompts based on preset
                            val prompts = generatePresetPrompts(selectedPreset, seats.size)
                            val seatsWithPrompts = seats.mapIndexed { index, seat ->
                                if (index < prompts.size) {
                                    seat.copy(overrides = seat.overrides.copy(systemPrompt = prompts[index]))
                                } else seat
                            }

                            val template = GroupChatTemplate(
                                name = templateName.ifBlank { "辩论模板" },
                                seats = seatsWithPrompts,
                            )

                            settingsStore.update { s ->
                                s.copy(groupChatTemplates = s.groupChatTemplates + template)
                            }
                        }
                        showCreateDialog = false
                    },
                    enabled = templateName.isNotBlank() && assistant1 != null && assistant2 != null,
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("取消") }
            },
        )
    }

    // ── 开始辩论对话框 ──
    if (showTopicDialog && selectedTopic.isNotBlank()) {
        val matchedTemplate = debateTemplates.firstOrNull()

        AlertDialog(
            onDismissRequest = { showTopicDialog = false },
            title = { Text("开始辩论") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("主题：${selectedTopic}", style = MaterialTheme.typography.bodyMedium)

                    if (debateTemplates.isEmpty()) {
                        Text(
                            "暂无可用模板，请先创建辩论模板",
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text("选择模板：", style = MaterialTheme.typography.labelMedium)
                        debateTemplates.take(5).forEach { template ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { startTitle = template.name }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    template.name.ifBlank { "未命名" },
                                    modifier = Modifier.weight(1f),
                                    fontWeight = if (startTitle == template.name) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text("${template.seats.size}人", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val template = debateTemplates.firstOrNull { it.name == startTitle }
                            ?: debateTemplates.firstOrNull()
                        if (template != null) {
                            scope.launch {
                                val convId = chatService.startGroupChatConversation(
                                    templateId = template.id,
                                    userMessage = listOf(me.rerere.ai.ui.UIMessagePart.Text(selectedTopic)),
                                )
                                navController.navigate(Screen.Chat(id = convId.toString()))
                            }
                        }
                        showTopicDialog = false
                    },
                    enabled = debateTemplates.isNotEmpty(),
                ) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { showTopicDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DebateTemplateList(
    templates: List<GroupChatTemplate>,
    settingsStore: SettingsStore,
    chatService: ChatService,
    navController: me.rerere.rikkahub.ui.context.Navigator,
    scope: rememberCoroutineScope,
    onDelete: (GroupChatTemplate) -> Unit,
) {
    if (templates.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "暂无辩论模板",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "点击右上角「新建」创建辩论模板",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 快速开始卡片
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("快速开始", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "选择一个模板和主题，一键开始AI辩论",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        items(templates, key = { it.id }) { template ->
            DebateTemplateCard(
                template = template,
                settings = settingsStore.settingsFlow.value,
                onStart = { topic ->
                    scope.launch {
                        val convId = chatService.startGroupChatConversation(
                            templateId = template.id,
                            userMessage = if (topic.isNotBlank()) {
                                listOf(me.rerere.ai.ui.UIMessagePart.Text(topic))
                            } else emptyList(),
                        )
                        navController.navigate(Screen.Chat(id = convId.toString()))
                    }
                },
                onDelete = { onDelete(template) },
            )
        }
    }
}

@Composable
private fun DebateTemplateCard(
    template: GroupChatTemplate,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    onStart: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var showTopicInput by remember { mutableStateOf(false) }
    var topic by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🎯", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        template.name.ifBlank { "未命名模板" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "${template.seats.size}个角色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(HugeIcons.Delete01, "删除", tint = MaterialTheme.colorScheme.error)
                }
            }

            // Seat list
            Spacer(Modifier.height(8.dp))
            template.seats.forEach { seat ->
                val assistant = settings.assistants.firstOrNull { it.id == seat.assistantId }
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "• ${assistant?.name?.ifBlank { "助手" } ?: "助手"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showTopicInput = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.Play, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("开始辩论")
            }
        }
    }

    if (showTopicInput) {
        AlertDialog(
            onDismissRequest = { showTopicInput = false },
            title = { Text("辩论主题") },
            text = {
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入辩论主题...") },
                    minLines = 2,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onStart(topic)
                        showTopicInput = false
                    },
                    enabled = topic.isNotBlank(),
                ) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { showTopicInput = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DebateTopicList(
    expandedCategory: String?,
    onToggleCategory: (String) -> Unit,
    onSelectTopic: (String) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(DEBATE_TOPICS, key = { it.name }) { category ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleCategory(category.name) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            category.name,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${category.topics.size}个主题",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (expandedCategory == category.name) {
                        category.topics.forEach { topic ->
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            Text(
                                text = topic,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectTopic(topic) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

// ──── 预设Prompts生成 ────

private fun generatePresetPrompts(preset: String, count: Int): List<String> {
    return when (preset) {
        "basic" -> listOf(
            """你是一位专业的正方辩论者。你的立场：支持辩论观点。提供有力的证据和逻辑论证，保持理性和专业。每次发言控制在150-200字。""",
            """你是一位犀利的反方辩论者。你的立场：反对辩论观点。用事实和逻辑拆解对方论证，提出有力的反驳。每次发言控制在150-200字。""",
            """你是一位专业的辩论主持人。引导辩论方向和节奏，总结各方要点，判断讨论是否充分。只有经过至少3轮充分讨论后才考虑结束辩论。""",
        )
        "professional" -> listOf(
            """你是一位专业的正方辩论者。你的立场：支持辩论观点。提供有力的证据和逻辑论证。每次发言控制在150-200字。""",
            """你是一位犀利的反方辩论者。你的立场：反对辩论观点。用事实和逻辑拆解对方论证。每次发言控制在150-200字。""",
            """你是一位客观中立的分析师。保持中立，用理性和逻辑评估论证质量，寻找双方的共同点。每次发言控制在150-200字。""",
            """你是一位专业的辩论主持人。引导辩论方向和节奏，总结各方要点，判断讨论是否充分。只有经过至少3轮充分讨论后才考虑结束辩论。""",
        )
        "expert" -> listOf(
            """你是一位资深法律专家，从法律角度分析问题。引用相关法条和判例，分析法律风险。每次发言控制在150-200字。""",
            """你是一位经济学专家，从经济角度评估影响。分析成本和收益，考虑宏观和微观经济效应。每次发言控制在150-200字。""",
            """你是一位技术专家，从技术角度分析可行性。评估技术风险和挑战，考虑发展趋势。每次发言控制在150-200字。""",
            """你是一位辩论主持人，引导专家讨论。总结各领域专家的观点，推动跨领域交流。每次发言控制在150-200字。""",
        )
        else -> generatePresetPrompts("basic", count)
    }.take(count)
}

// Need DEFAULT_ASSISTANTS_IDS for the create dialog filter
private val DEFAULT_ASSISTANTS_IDS = me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANTS_IDS
