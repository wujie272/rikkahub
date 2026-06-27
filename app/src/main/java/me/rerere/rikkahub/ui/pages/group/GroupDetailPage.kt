package me.rerere.rikkahub.ui.pages.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.UserMultiple
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.GroupMemberEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.data.ai.group.SpeakerStrategy
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailPage(id: String) {
    val vm: GroupDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val group by vm.group.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val allAssistants by vm.allAssistants.collectAsStateWithLifecycle()
    val assistantNames by vm.assistantNames.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    var showEditInfoDialog by remember { mutableStateOf(false) }
    var showAddMemberDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = group?.name ?: stringResource(R.string.group_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val convId = vm.startChat()
                            navController.navigate(Screen.GroupChat(id = convId.toString()))
                        }
                    }) {
                        Icon(
                            HugeIcons.MessageAdd01,
                            contentDescription = stringResource(R.string.group_detail_start_chat),
                        )
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            if (allAssistants.isNotEmpty()) {
                FloatingActionButton(onClick = { showAddMemberDialog = true }) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                }
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Group info section ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CustomColors.cardColorsOnSurfaceContainer,
                ) {
                    GroupInfoSection(
                        name = group?.name ?: "",
                        description = group?.description ?: "",
                        onEdit = { showEditInfoDialog = true },
                    )
                }
            }

            // ── Speaker strategy section ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CustomColors.cardColorsOnSurfaceContainer,
                ) {
                    SpeakerStrategySelector(
                        currentStrategyId = group?.speakerStrategy ?: SpeakerStrategy.DEFAULT.id,
                        onStrategyChange = { vm.updateSpeakerStrategy(it) },
                    )
                }
            }

            // ── Members section header ──
            item {
                Text(
                    text = stringResource(R.string.group_detail_members),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }

            if (members.isEmpty()) {
                item {
                    EmptyMembersState()
                }
            }

            items(members, key = { it.assistantId }) { member ->
                MemberCard(
                    member = member,
                    assistantName = assistantNames[member.assistantId]
                        ?: member.assistantId.take(8) + "...",
                    onRemove = { vm.removeMember(member.assistantId) },
                    onPriorityChange = { vm.updateMemberPriority(member.assistantId, it) },
                    onProbabilityChange = { vm.updateMemberProbability(member.assistantId, it) },
                )
            }
        }
    }

    // ── Edit info dialog ──
    if (showEditInfoDialog) {
        val currentName = group?.name ?: ""
        val currentDesc = group?.description ?: ""
        var editName by rememberSaveable { mutableStateOf(currentName) }
        var editDesc by rememberSaveable { mutableStateOf(currentDesc) }

        AlertDialog(
            onDismissRequest = { showEditInfoDialog = false },
            title = { Text(stringResource(R.string.group_detail_edit_info)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.group_page_name)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = editDesc,
                        onValueChange = { editDesc = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.group_detail_description)) },
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.updateGroup(editName.trim(), editDesc.trim())
                        showEditInfoDialog = false
                    },
                    enabled = editName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditInfoDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // ── Add member dialog ──
    if (showAddMemberDialog) {
        val currentMemberIds = members.map { it.assistantId }.toSet()
        val available = allAssistants.filter { it.key !in currentMemberIds }

        AlertDialog(
            onDismissRequest = { showAddMemberDialog = false },
            title = { Text(stringResource(R.string.group_detail_add_member)) },
            text = {
                if (available.isEmpty()) {
                    Text(
                        text = stringResource(R.string.group_detail_all_assistants_added),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(available.entries.toList(), key = { it.key }) { (id, name) ->
                            TextButton(
                                onClick = {
                                    vm.addMember(id)
                                    showAddMemberDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddMemberDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun GroupInfoSection(
    name: String,
    description: String,
    onEdit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Play,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.edit))
            }
        }
    }
}

@Composable
private fun MemberCard(
    member: GroupMemberEntity,
    assistantName: String,
    onRemove: () -> Unit,
    onPriorityChange: (Int) -> Unit,
    onProbabilityChange: (Float) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HugeIcons.UserMultiple,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = assistantName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.group_detail_remove_member),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Priority slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${stringResource(R.string.group_detail_member_priority)}: ${member.priority}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = member.priority.toFloat(),
                    onValueChange = { onPriorityChange(it.toInt()) },
                    valueRange = 0f..10f,
                    steps = 9,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Probability slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${stringResource(R.string.group_detail_member_probability)}: ${"%.0f".format(member.responseProbability * 100)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = member.responseProbability,
                    onValueChange = { onProbabilityChange(it) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SpeakerStrategySelector(
    currentStrategyId: String,
    onStrategyChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.group_detail_speaker_strategy),
            style = MaterialTheme.typography.titleSmallEmphasized,
        )
        Text(
            text = stringResource(R.string.group_detail_strategy_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SpeakerStrategy.ALL.values.forEach { strategy ->
            val isSelected = strategy.id == currentStrategyId

            Card(
                onClick = { onStrategyChange(strategy.id) },
                modifier = Modifier.fillMaxWidth(),
                colors = if (isSelected) {
                    androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                } else {
                    CustomColors.cardColorsOnSurfaceContainer
                },
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val iconText = when (strategy) {
                        is SpeakerStrategy.ProbabilityBased -> "\uD83C\uDFB2"
                        is SpeakerStrategy.RoundRobin -> "\uD83D\uDD04"
                        is SpeakerStrategy.PriorityBased -> "\uD83D\uDC51"
                        is SpeakerStrategy.Random -> "\uD83C\uDFB0"
                        is SpeakerStrategy.ForcedOnly -> "\uD83E\uDD10"
                        else -> "?"
                    }
                    Text(
                        text = iconText,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strategy.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = HugeIcons.CheckmarkCircle01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMembersState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = HugeIcons.UserMultiple,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Text(
            text = stringResource(R.string.group_detail_no_members),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
