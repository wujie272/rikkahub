package me.rerere.rikkahub.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.ui.theme.RikkahubTheme

/**
 * Bubble Activity — 当用户点击 RikkaHub 的气泡通知时展开的浮动窗口。
 * 显示 AI 回复摘要，提供「打开 App」按钮跳转到完整对话。
 */
class RikkaBubbleActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversationId"
        const val EXTRA_SENDER_NAME = "senderName"
        const val EXTRA_CONTENT_PREVIEW = "contentPreview"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID) ?: ""
        val senderName = intent?.getStringExtra(EXTRA_SENDER_NAME) ?: ""
        val contentPreview = intent?.getStringExtra(EXTRA_CONTENT_PREVIEW) ?: ""

        setContent {
            RikkahubTheme {
                BubbleContent(
                    senderName = senderName,
                    contentPreview = contentPreview,
                    onOpenApp = {
                        val mainIntent = Intent(this@RikkaBubbleActivity, RouteActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("conversationId", conversationId)
                        }
                        startActivity(mainIntent)
                        finish()
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }
}

@Composable
private fun BubbleContent(
    senderName: String,
    contentPreview: String,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = senderName.ifEmpty { stringResource(R.string.app_name) },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Text(
                    text = contentPreview.ifEmpty { stringResource(R.string.bubble_no_content) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 20,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action button
            Button(
                onClick = onOpenApp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.bubble_open_in_app))
            }
        }
    }
}
