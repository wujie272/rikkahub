package me.rerere.rikkahub.ui.pages.setting

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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.setting.components.SshHostFormSheet
import me.rerere.rikkahub.ui.pages.setting.components.SshTestDialog
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSshPage() {
    val repo: SshHostRepository = koinInject()
    var hosts by remember { mutableStateOf<List<SshHostEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()



    var showAddSheet by remember { mutableStateOf(false) }
    var editingHost by remember { mutableStateOf<SshHostEntity?>(null) }
    var testingHost by remember { mutableStateOf<SshHostEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<SshHostEntity?>(null) }

    fun refresh() {
        scope.launch {
            hosts = repo.getAll()
            loaded = true
        }
    }

    LaunchedEffect(Unit) { refresh() }



    val pullRefreshState = rememberPullToRefreshState()
    val isRefreshing = remember { mutableStateOf(false) }

    fun refreshAsync() = scope.async {
        hosts = repo.getAll()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_ssh_title)) },
                navigationIcon = { BackButton() }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.setting_ssh_add))
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing.value,
            onRefresh = {
                scope.launch {
                    isRefreshing.value = true
                    refreshAsync().await()
                    isRefreshing.value = false
                }
            },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize()
        ) {
            if (loaded && hosts.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        HugeIcons.ServerStack01,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.setting_ssh_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (loaded) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Spacer(Modifier.height(8.dp)) }
                    items(hosts, key = { it.name }) { host ->
                        SshHostItem(
                            host = host,
                            onEdit = { editingHost = host },
                            onTest = { testingHost = host },
                            onDelete = { deleteTarget = host },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) } // FAB clearance
                }
            }
        }
    }

    // Add sheet
    if (showAddSheet) {
        SshHostFormSheet(
            onDismiss = { showAddSheet = false },
            onSave = { entity ->
                scope.launch {
                    repo.upsert(entity)
                    refresh()
                }
            },
        )
    }

    // Edit sheet
    editingHost?.let { host ->
        SshHostFormSheet(
            existing = host,
            onDismiss = { editingHost = null },
            onSave = { entity ->
                scope.launch {
                    if (entity.name != host.name) {
                        repo.deleteByName(host.name)
                    }
                    repo.upsert(entity)
                    refresh()
                }
            },
        )
    }

    // Test dialog
    testingHost?.let { host ->
        SshTestDialog(
            host = host,
            onDismiss = { testingHost = null },
        )
    }

    // Delete confirmation
    deleteTarget?.let { host ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.setting_ssh_delete_confirm)) },
            text = {
                Text("${host.user}@${host.host}:${host.port} (${host.name})")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.deleteByName(host.name)
                        refresh()
                    }
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.setting_ssh_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SshHostItem(
    host: SshHostEntity,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dismissBoxState = rememberSwipeToDismissBoxState()

    SwipeToDismissBox(
        state = dismissBoxState,
        backgroundContent = {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                FilledTonalIconButton(
                    onClick = {
                        scope.launch { dismissBoxState.reset() }
                    }
                ) {
                    Icon(HugeIcons.Cancel01, null)
                }
                FilledTonalIconButton(
                    onClick = { onDelete() }
                ) {
                    Icon(HugeIcons.Delete01, null)
                }
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEdit() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    HugeIcons.ServerStack01,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = host.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${host.user}@${host.host}:${host.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = buildAuthLabel(host),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                Row {
                    IconButton(onClick = onTest) {
                        Icon(
                            HugeIcons.Connect,
                            contentDescription = stringResource(R.string.setting_ssh_test),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(
                            HugeIcons.Edit02,
                            contentDescription = stringResource(R.string.setting_ssh_edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun buildAuthLabel(host: SshHostEntity): String {
    val parts = mutableListOf<String>()
    if (!host.password.isNullOrBlank()) parts.add("password")
    if (!host.privateKey.isNullOrBlank()) parts.add("private key")
    return if (parts.isEmpty()) "no auth configured" else parts.joinToString(" + ")
}
