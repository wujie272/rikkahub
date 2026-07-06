package me.rerere.rikkahub.ui.components.textselection

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Translate
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.ChatTarget
import me.rerere.rikkahub.ui.activity.QuickAction
import me.rerere.rikkahub.ui.activity.TextSelectionState
import me.rerere.rikkahub.ui.activity.TextSelectionVM
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@Composable
fun TextSelectionSheet(
    viewModel: TextSelectionVM,
    onDismiss: () -> Unit,
    onContinueInApp: () -> Unit,
    onSendToConversation: (ChatTarget) -> Unit,
) {
    val amoledMode by rememberAmoledDarkMode()
    val isDarkMode = LocalDarkMode.current
    var showTargetPicker by remember { mutableStateOf(false) }

    var isVisible by remember { mutableStateOf(false) }
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (isVisible) 0.5f else 0f,
        animationSpec = tween(300),
        label = "background_alpha"
    )

    LaunchedEffect(Unit) { isVisible = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backgroundAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
            ) + fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
            ) + fadeOut(animationSpec = tween(150)),
        ) {
            CompositionLocalProvider(
                LocalAbsoluteTonalElevation provides
                        if (amoledMode && isDarkMode) 0.dp
                        else LocalAbsoluteTonalElevation.current,
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(16.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                    shape = RoundedCornerShape(40.dp),
                    color = if (amoledMode && isDarkMode) Color.Black
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 8.dp,
                ) {
                    AnimatedContent(
                        targetState = viewModel.state,
                        transitionSpec = {
                            (fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) togetherWith
                                    fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                        },
                        contentKey = { it::class.simpleName },
                        label = "state_transition",
                    ) { state ->
                        when (state) {
                            is TextSelectionState.ActionSelection -> {
                                ActionSelectionContent(
                                    selectedText = viewModel.selectedText,
                                    onActionSelected = { viewModel.onActionSelected(it) },
                                    onSendToConversationClick = { showTargetPicker = true },
                                    onDismiss = onDismiss,
                                )
                            }
                            is TextSelectionState.CustomPrompt -> {
                                CustomPromptContent(
                                    prompt = viewModel.customPrompt,
                                    onPromptChange = { viewModel.updateCustomPrompt(it) },
                                    onSubmit = { viewModel.submitCustomPrompt() },
                                    onBack = { viewModel.backToActionSelection() },
                                )
                            }
                            is TextSelectionState.Loading -> {
                                LoadingContent()
                            }
                            is TextSelectionState.Result -> {
                                ResultContent(
                                    responseText = state.responseText,
                                    isStreaming = state.isStreaming,
                                    isReasoning = state.isReasoning,
                                    isTranslate = viewModel.lastAction == QuickAction.TRANSLATE,
                                    onBack = { viewModel.backToActionSelection() },
                                    onStop = { viewModel.cancelGeneration() },
                                    onContinueInApp = onContinueInApp,
                                )
                            }
                            is TextSelectionState.Error -> {
                                ErrorContent(
                                    message = state.message,
                                    onRetry = { viewModel.backToActionSelection() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTargetPicker) {
        TextSelectionTargetPickerSheet(
            selectedText = viewModel.selectedText,
            onDismiss = { showTargetPicker = false },
            onTargetSelected = { target ->
                showTargetPicker = false
                onSendToConversation(target)
            },
        )
    }
}

@Composable
private fun ActionSelectionContent(
    selectedText: String,
    onActionSelected: (QuickAction) -> Unit,
    onSendToConversationClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.text_selection_menu_label),
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(HugeIcons.Cancel01, contentDescription = "Close", modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.size(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 100.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = selectedText,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        ActionButton(QuickAction.TRANSLATE, R.string.text_selection_translate, HugeIcons.Translate) { onActionSelected(QuickAction.TRANSLATE) }
        ActionButton(QuickAction.EXPLAIN, R.string.text_selection_explain, HugeIcons.Idea01) { onActionSelected(QuickAction.EXPLAIN) }
        ActionButton(QuickAction.SUMMARIZE, R.string.text_selection_summarize, HugeIcons.MagicWand01) { onActionSelected(QuickAction.SUMMARIZE) }
        ActionButton(QuickAction.CUSTOM, R.string.text_selection_ask, HugeIcons.Sparkles) { onActionSelected(QuickAction.CUSTOM) }
        Spacer(modifier = Modifier.size(4.dp))
        Surface(
            onClick = onSendToConversationClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    HugeIcons.Forward02, contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.text_selection_send_to_conversation),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ActionButton(action: QuickAction, labelRes: Int, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun CustomPromptContent(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(HugeIcons.ArrowLeft01, contentDescription = "Back", modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(text = stringResource(R.string.text_selection_ask), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.size(16.dp))
        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            placeholder = { Text(stringResource(R.string.text_selection_custom_placeholder)) },
        )
        Spacer(Modifier.size(16.dp))
        Surface(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primary,
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.text_selection_try_it),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Text(
            text = stringResource(R.string.text_selection_generating),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultContent(
    responseText: String,
    isStreaming: Boolean,
    isReasoning: Boolean,
    isTranslate: Boolean,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onContinueInApp: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val copiedText = stringResource(R.string.text_selection_copy)

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = "Back", modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                if (isStreaming) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = if (isReasoning) "Reasoning..." else stringResource(R.string.text_selection_generating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (isStreaming) {
                IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                    Icon(
                        HugeIcons.Cancel01,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 300.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                if (responseText.isBlank()) {
                    Text("...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    MarkdownBlock(content = responseText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (!isStreaming && responseText.isNotBlank()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    onClick = {
                        clipboardManager.setText(AnnotatedString(responseText))
                        toaster.show(copiedText, type = ToastType.Success)
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(HugeIcons.Copy01, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.text_selection_copy),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                if (!isTranslate) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        onClick = onContinueInApp,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(HugeIcons.ArrowLeft01, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.text_selection_continue_chat),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("⚠️ $message", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            onClick = onRetry,
        ) {
            Text(
                text = stringResource(R.string.back),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}
