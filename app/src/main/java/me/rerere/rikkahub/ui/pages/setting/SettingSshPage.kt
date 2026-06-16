package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSshPage() {
    val repo: SshHostRepository = koinInject()
    var hosts by remember { mutableStateOf<List<SshHostEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<SshHostEntity?>(null) }

    LaunchedEffect(Unit) {
        hosts = repo.getAll()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_ssh_title)) },
                navigationIcon = { BackButton() }
            )
        }
    ) { padding ->
        if (loaded && hosts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    HugeIcons.ServerStack01,
                    contentDescription = null,
                    modifier = Modifier.height(64.dp).width(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.setting_ssh_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (loaded) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                items(hosts, key = { it.name }) { host ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            HugeIcons.ServerStack01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = host.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "${host.user}@${host.host}:${host.port}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = buildAuthLabel(host),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        IconButton(onClick = { deleteTarget = host }) {
                            Icon(
                                HugeIcons.Delete02,
                                contentDescription = stringResource(R.string.setting_ssh_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

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
                        hosts = repo.getAll()
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
            }
        )
    }
}

private fun buildAuthLabel(host: SshHostEntity): String {
    val parts = mutableListOf<String>()
    if (!host.password.isNullOrBlank()) parts.add("password")
    if (!host.privateKey.isNullOrBlank()) parts.add("private key")
    return if (parts.isEmpty()) "no auth configured" else parts.joinToString(" + ")
}
