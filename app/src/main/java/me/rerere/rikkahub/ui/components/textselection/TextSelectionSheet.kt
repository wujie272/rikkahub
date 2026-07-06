package me.rerere.rikkahub.ui.components.textselection

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Translate
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.activity.QuickAction
import me.rerere.rikkahub.ui.activity.TextSelectionState
import me.rerere.rikkahub.ui.activity.TextSelectionVM

@Composable
fun TextSelectionSheet(
    viewModel: TextSelectionVM,
    onDismiss: () -> Unit,
    onContinueInApp: () -> Unit,
) {
    val state = viewModel.state

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
        tonalElevation = 3.dp,
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (slideInVertically { it } + fadeIn()) togetherWith
                        (slideOutVertically { -it } + fadeOut())
            },
            label = "text_selection_content",
        ) { currentState ->
            when (currentState) {
                is TextSelectionState.ActionSelection -> ActionSelectionContent(viewModel, onDismiss)
                is TextSelectionState.CustomPrompt -> CustomPromptContent(viewModel)
                is TextSelectionState.Loading -> LoadingContent(viewModel)
                is TextSelectionState.Result -> ResultContent(viewModel, onDismiss)
                is TextSelectionState.Error -> ErrorContent(currentState.message, viewModel)
            }
        }
    }
}

@Composable
private fun ActionSelectionContent(viewModel: TextSelectionVM, onDismiss: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = stringResource(R.string.text_selection_menu_label), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, contentDescription = "Close") }
        }
        Spacer(modifier = Modifier.padding(top = 8.dp))
        Surface(modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(text = viewModel.selectedText, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
        Spacer(modifier = Modifier.padding(top = 16.dp))
        ActionButton(QuickAction.TRANSLATE, R.string.text_selection_translate, HugeIcons.Translate) { viewModel.onActionSelected(QuickAction.TRANSLATE) }
        ActionButton(QuickAction.EXPLAIN, R.string.text_selection_explain, HugeIcons.Idea01) { viewModel.onActionSelected(QuickAction.EXPLAIN) }
        ActionButton(QuickAction.SUMMARIZE, R.string.text_selection_summarize, HugeIcons.AiMagic) { viewModel.onActionSelected(QuickAction.SUMMARIZE) }
        ActionButton(QuickAction.CUSTOM, R.string.text_selection_ask, HugeIcons.Sparkles) { viewModel.onActionSelected(QuickAction.CUSTOM) }
    }
}

@Composable
private fun ActionButton(action: QuickAction, labelRes: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.size(12.dp))
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun CustomPromptContent(viewModel: TextSelectionVM) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.backToActionSelection() }) { Icon(HugeIcons.ArrowLeft01, contentDescription = "Back") }
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = stringResource(R.string.text_selection_ask), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(modifier = Modifier.padding(top = 16.dp))
        OutlinedTextField(value = viewModel.customPrompt, onValueChange = { viewModel.updateCustomPrompt(it) }, modifier = Modifier.fillMaxWidth().weight(1f), placeholder = { Text(stringResource(R.string.text_selection_custom_placeholder)) })
        Spacer(modifier = Modifier.padding(top = 16.dp))
        Surface(onClick = { viewModel.submitCustomPrompt() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(text = stringResource(R.string.text_selection_try_it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun LoadingContent(viewModel: TextSelectionVM) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = stringResource(R.string.text_selection_generating), style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.size(16.dp))
            IconButton(onClick = { viewModel.cancelGeneration() }) { Icon(HugeIcons.Cancel01, contentDescription = "Cancel") }
        }
    }
}

@Composable
private fun ResultContent(viewModel: TextSelectionVM, onDismiss: () -> Unit) {
    val state = viewModel.state as? TextSelectionState.Result
    val displayText = state?.responseText ?: ""
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state?.isStreaming == true) {
                    IconButton(onClick = { viewModel.cancelGeneration() }) { Icon(HugeIcons.Cancel01, contentDescription = "Stop") }
                } else {
                    IconButton(onClick = { viewModel.backToActionSelection() }) { Icon(HugeIcons.ArrowLeft01, contentDescription = "Back") }
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = stringResource(R.string.text_selection_preview), style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(modifier = Modifier.padding(top = 8.dp))
        Surface(modifier = Modifier.fillMaxWidth().weight(1f).animateContentSize(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(text = displayText, modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorContent(message: String, viewModel: TextSelectionVM) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.size(16.dp))
        Surface(onClick = { viewModel.backToActionSelection() }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary) {
            Text(text = "Back", modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}
