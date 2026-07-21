package me.rerere.rikkahub.ui.overlay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * AI 对话浮窗管理器。
 * 使用 [DraggableFloatingWindow] 显示一个迷你 AI 对话界面。
 * 用户输入消息后，跳转到主 RikkaHub 聊天界面。
 */
object FloatingChatWindow {
    private const val TAG = "FloatingChatWindow"

    @Volatile
    private var floatingWindow: DraggableFloatingWindow? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var messages: List<ChatMessage> = listOf(
        ChatMessage("你好！我是 Rikka AI，随时为你服务。", isUser = false)
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    data class ChatMessage(
        val text: String,
        val isUser: Boolean
    )

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!canShow(app)) {
            Log.d(TAG, "SYSTEM_ALERT_WINDOW not granted")
            return
        }
        mainHandler.post { showInternal(app) }
    }

    fun hide() {
        floatingWindow?.hide()
        floatingWindow = null
    }

    fun isShowing(): Boolean = floatingWindow?.isShown() == true

    /**
     * 添加一条系统消息（AI 回复等）
     */
    fun addMessage(text: String, isUser: Boolean) {
        messages = messages + ChatMessage(text, isUser)
        mainHandler.post { refreshContent() }
    }

    private fun showInternal(app: Context) {
        if (floatingWindow?.isShown() == true) return
        val fw = DraggableFloatingWindow(app).apply {
            widthDp = 320
            heightDp = 400
            onDismiss = { hide() }
        }
        floatingWindow = fw
        fw.show {
            ChatContent()
        }
    }

    private fun refreshContent() {
        val fw = floatingWindow ?: return
        if (!fw.isShown()) return
        fw.setContent { ChatContent() }
    }

    @Composable
    private fun ChatContent() {
        val context = LocalContext.current
        var inputText by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        // 新消息时自动滚动到底部
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        Surface(
            color = Color(0xE61E1E2E.toInt()),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                // 标题
                Text(
                    text = "💬 AI 助手",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                HorizontalDivider(color = Color(0x40FFFFFF.toInt()), thickness = 0.5.dp)

                // 消息列表
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(messages) { msg ->
                        MessageBubble(msg)
                    }
                }

                HorizontalDivider(color = Color(0x40FFFFFF.toInt()), thickness = 0.5.dp)

                // 输入区域
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入消息…", color = Color(0x80FFFFFF.toInt())) },
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF60A5FA.toInt()),
                            unfocusedBorderColor = Color(0x40FFFFFF.toInt()),
                            cursorColor = Color.White,
                        ),
                        shape = RoundedCornerShape(8.dp),
                        maxLines = 3,
                    )

                    Spacer(Modifier.width(4.dp))

                    // 发送按钮
                    FilledIconButton(
                        onClick = {
                            val text = inputText.trim()
                            if (text.isNotEmpty()) {
                                addMessage(text, isUser = true)
                                inputText = ""
                                // 打开主 RikkaHub 聊天界面
                                openMainChat(context, text)
                            }
                        },
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF60A5FA.toInt())
                        )
                    ) {
                        Text("↑", color = Color.White, fontSize = 18.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun MessageBubble(msg: ChatMessage) {
        val alignment = if (msg.isUser) Alignment.End else Alignment.Start
        val bgColor = if (msg.isUser) Color(0xFF60A5FA.toInt())
        else Color(0x40FFFFFF.toInt())
        val textColor = Color.White

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                color = bgColor,
                shape = RoundedCornerShape(
                    topStart = 8.dp, topEnd = 8.dp,
                    bottomStart = if (msg.isUser) 8.dp else 0.dp,
                    bottomEnd = if (msg.isUser) 0.dp else 8.dp
                )
            ) {
                Text(
                    text = msg.text,
                    color = textColor,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    maxLines = 10,
                )
            }
        }
    }

    private fun openMainChat(context: Context, message: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(
                context.packageName
            ) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            intent.putExtra("quick_message", message)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open main chat", e)
        }
    }
}
