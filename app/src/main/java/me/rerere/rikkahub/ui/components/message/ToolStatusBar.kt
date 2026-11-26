package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 工具状态条 — 显示工具名 + 状态 + 翻页
 */
@Composable
fun ToolStatusBar(
    toolBlocks: List<UIMessagePart.Tool>,
    onOpenDetail: (List<UIMessagePart.Tool>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (toolBlocks.isEmpty()) return

    var currentIndex by remember { mutableStateOf(toolBlocks.lastIndex.coerceAtLeast(0)) }
    val lastIndex = toolBlocks.lastIndex

    LaunchedEffect(lastIndex) {
        val block = toolBlocks.getOrNull(currentIndex)
        val isCurrentActive = block?.approvalState is ToolApprovalState.Pending
        if (!isCurrentActive) currentIndex = lastIndex.coerceAtLeast(0)
    }

    val block = toolBlocks.getOrNull(currentIndex) ?: return
    val isLive = block.approvalState is ToolApprovalState.Pending
    val isDone = block.isExecuted && block.approvalState !is ToolApprovalState.Denied
    val isFailed = block.approvalState is ToolApprovalState.Denied

    val renderer = remember(block.toolName) { ToolUIRegistry.resolve(block.toolName) }
    val context = remember(block) {
        me.rerere.rikkahub.ui.components.message.tools.ToolUIContext(
            tool = block,
            arguments = block.inputAsJson(),
            content = if (block.isExecuted) {
                runCatching {
                    JsonInstant.parseToJsonElement(
                        block.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    )
                }.getOrElse { JsonObject(emptyMap()) }
            } else null,
            loading = isLive,
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .shadow(4.dp, RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLive) {
            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.5.dp)
        } else {
            val (icon, tint) = when {
                isDone -> HugeIcons.CheckmarkCircle01 to Color(0xFF34C759)
                isFailed -> HugeIcons.Cancel01 to Color(0xFFFF3B30)
                else -> HugeIcons.Tools to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        }

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = renderer.title(context),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { onOpenDetail(toolBlocks, currentIndex) },
            overflow = TextOverflow.Ellipsis,
        )

        if (toolBlocks.size > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Icon(
                    HugeIcons.ArrowLeft01,
                    contentDescription = "Previous",
                    tint = if (currentIndex > 0) MaterialTheme.colorScheme.onSurface
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(
                            enabled = currentIndex > 0,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { if (currentIndex > 0) currentIndex-- },
                )
                Text(
                    "${currentIndex + 1}/${toolBlocks.size}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = "Next",
                    tint = if (currentIndex < toolBlocks.lastIndex) MaterialTheme.colorScheme.onSurface
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(
                            enabled = currentIndex < toolBlocks.lastIndex,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { if (currentIndex < toolBlocks.lastIndex) currentIndex++ },
                )
            }
        }
    }
}
