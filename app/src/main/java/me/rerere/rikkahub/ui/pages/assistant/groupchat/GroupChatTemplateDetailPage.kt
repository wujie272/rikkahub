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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.GroupChatTemplate
import me.rerere.rikkahub.data.model.buildSeatDisplayNames
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatTemplateDetailPage(id: String) {
    val vm: GroupChatTemplateDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val template by vm.template.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddMemberSheet by remember { mutableStateOf(false) }
    var expandedSeatId by remember(template?.id) { mutableStateOf<Uuid?>(null) }

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
        val currentTemplate = template
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

                        OutlinedTextField(
                            value = currentTemplate.intro,
                            onValueChange = vm::updateIntro,
                            label = { Text("简介") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            minLines = 2,
                            maxLines = 4,
                        )
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
                            text = "路由模型负责决定每次由哪位成员发言。留空则默认取前3位启用的成员。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // Show host model info or selector
                        val hostModel = currentTemplate.hostModelId?.let { modelId ->
                            settings.providers.flatMap { it.models }.firstOrNull { it.id == modelId }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = hostModel?.name ?: "未选择（使用默认路由）",
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { /* TODO: model picker */ }) {
                                Text(if (hostModel != null) "更换" else "选择")
                            }
                            if (hostModel != null) {
                                TextButton(onClick = { vm.updateHostModel(null) }) {
                                    Text("清除")
                                }
                            }
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
                    defaultName = "助手",
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
                    onRemove = { vm.removeSeat(seat.id) },
                    overrides = seat.overrides,
                    onUpdateOverrides = { transform -> vm.updateSeatOverrides(seat.id, transform) },
                    settings = settings,
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // ── Delete confirmation ──
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

    // ── Add member bottom sheet ──
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
                        Text(
                            text = assistant.name.ifBlank { "未命名助手" },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
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
    overrides: me.rerere.rikkahub.data.model.GroupChatSeatOverrides,
    onUpdateOverrides: ((me.rerere.rikkahub.data.model.GroupChatSeatOverrides) -> me.rerere.rikkahub.data.model.GroupChatSeatOverrides) -> Unit,
    settings: me.rerere.rikkahub.data.datastore.Settings,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(spring()),
        colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggleExpand() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Avatar placeholder
                androidx.compose.foundation.Canvas(modifier = Modifier.size(36.dp).clip(CircleShape)) {
                    drawCircle(color = androidx.compose.ui.graphics.Color(0xFF3498DB))
                }
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(displayName, style = MaterialTheme.typography.bodyLarge)
                    assistant?.let {
                        Text(
                            text = it.name.ifBlank { "未命名" },
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

            // Expanded settings
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    val model = overrides.chatModelId?.let { modelId ->
                        settings.providers.flatMap { it.models }.firstOrNull { it.id == modelId }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("覆写模型：", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = model?.name ?: "使用助手默认模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("搜索：", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = overrides.searchEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateOverrides { it.copy(searchEnabled = enabled) }
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("记忆：", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = overrides.memoryEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateOverrides { it.copy(memoryEnabled = enabled) }
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "提示词、MCP、Max Tokens等高级配置将在后续版本完善。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
