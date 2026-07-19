package me.rerere.rikkahub.ui.pages.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.Tick01
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.knowledge.KnowledgeDocumentEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import androidx.compose.material3.Switch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun DocumentChunkViewPage(
    kbId: String,
    filePath: String,
    vm: KnowledgeVM = koinViewModel(),
) {
    val chunks by vm.chunks.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val decodedPath = remember(filePath) { android.net.Uri.decode(filePath) }
    val fileName = remember(decodedPath) {
        val raw = decodedPath.substringAfterLast('/').ifBlank { decodedPath }
        android.net.Uri.decode(raw).substringAfterLast('/').ifBlank { android.net.Uri.decode(raw) }
    }


    LaunchedEffect(kbId, decodedPath) {
        vm.loadChunks(kbId, decodedPath)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Column {
                        Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.kb_chunks_count, chunks.size), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        if (chunks.isEmpty()) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.kb_no_chunks), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(chunks, key = { _, c -> c.id }) { index, chunk ->
                    ChunkCard(
                        index = index,
                        chunk = chunk,
                        onToggleEnabled = { enabled -> vm.toggleChunkEnabled(chunk.id, enabled) },
                        onEdit = { newText -> vm.updateChunkContent(chunk.id, newText) },
                        onDelete = { vm.deleteChunk(chunk.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChunkCard(
    index: Int,
    chunk: KnowledgeDocumentEntity,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var editText by remember(chunk.chunkText) { mutableStateOf(chunk.chunkText) }
    var copied by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (chunk.enabled) CustomColors.listItemColors.containerColor
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.kb_chunk_label, index + 1),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.kb_chars_count, chunk.chunkText.length),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("chunk", chunk.chunkText))
                        copied = true
                        scope.launch {
                            delay(1500)
                            copied = false
                        }
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(if (copied) HugeIcons.Tick01 else HugeIcons.Copy01,
                            stringResource(R.string.kb_copy), modifier = Modifier.size(16.dp))
                    }
                    // Edit
                    if (!editing) {
                        IconButton(onClick = {
                            editText = chunk.chunkText
                            editing = true
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(HugeIcons.Edit01, stringResource(R.string.kb_edit), modifier = Modifier.size(16.dp))
                        }
                    }
                    // Delete
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(HugeIcons.Delete01, stringResource(R.string.kb_delete), modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Enable/disable switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (chunk.enabled) stringResource(R.string.kb_enabled) else stringResource(R.string.kb_disabled),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (chunk.enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error)
                androidx.compose.material3.Switch(
                    checked = chunk.enabled,
                    onCheckedChange = onToggleEnabled,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Content
            if (editing) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    minLines = 3,
                    maxLines = 10,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        editing = false
                        editText = chunk.chunkText
                    }) {
                        Text(stringResource(R.string.kb_cancel))
                    }
                    TextButton(onClick = {
                        if (editText != chunk.chunkText) {
                            onEdit(editText)
                        }
                        editing = false
                    }) {
                        Text(stringResource(R.string.kb_save_revectorize))
                    }
                }
            } else {
                Text(
                    text = chunk.chunkText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

