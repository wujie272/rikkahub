package me.rerere.rikkahub.ui.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
/**
 * AI 对话浮窗管理器。
 * 使用 Jetpack Compose 构建聊天界面，通过 [OverlayLifecycleOwner] 提供生命周期支持。
 */
object FloatingChatWindow {
    private const val TAG = "FloatingChatWindow"

    @Volatile
    private var floatingWindow: DraggableFloatingWindow? = null

    @Volatile
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    @Volatile
    private var composeView: ComposeView? = null

    /** 消息列表状态，由 Compose 持有 */
    private val _messages = mutableStateListOf(
        ChatMessage("你好！我是 Rikka AI，随时为你服务。", isUser = false)
    )

    data class ChatMessage(
        val text: String,
        val isUser: Boolean
    )

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context) {
        val app = context.applicationContext
        if (!canShow(app)) {
            Log.d(TAG, "SYSTEM_ALERT_WINDOW not granted")
            return
        }
        if (floatingWindow?.isShown() == true) return

        val fw = DraggableFloatingWindow(app).apply {
            widthDp = 320
            heightDp = 400
            onDismiss = { hide() }
        }
        floatingWindow = fw

        // 创建 ComposeView + 生命周期 Owner
        val owner = OverlayLifecycleOwner()
        lifecycleOwner = owner
        val cv = ComposeView(app).apply {
            id = View.generateViewId()
            setContent { FloatingChatContent() }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        composeView = cv

        // 先 attach 再 addView（ComposeView 自带滚动，不包 ScrollView 防嵌套冲突）
        owner.attach(cv)
        fw.show(cv, wrapContent = false)
        owner.start()
    }

    fun hide() {
        lifecycleOwner?.pause()
        // 先移除 View（触发 Compose 的 DisposeOnDetachedFromWindow），再销毁 LifecycleOwner
        floatingWindow?.hide()
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        floatingWindow = null
        composeView = null
    }

    fun isShowing(): Boolean = floatingWindow?.isShown() == true

    fun addMessage(text: String, isUser: Boolean) {
        _messages.add(ChatMessage(text, isUser))
    }

    fun clearMessages() {
        _messages.clear()
    }

    // ==================== Compose UI ====================

    @Composable
    private fun FloatingChatContent() {
        val context = LocalContext.current
        val listState = rememberLazyListState()
        var inputText by remember { mutableStateOf("") }

        // 新消息时自动滚动到底部
        val msgCount = _messages.size
        LaunchedEffect(msgCount) {
            if (msgCount > 0) {
                listState.animateScrollToItem(msgCount - 1)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // 标题
            Text(
                text = "💬 AI 助手",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 分隔线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.25f))
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 消息列表
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(
                    items = _messages,
                    key = { it.hashCode() }
                ) { msg ->
                    ChatBubble(msg)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 分隔线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.25f))
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 输入区域
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            "输入消息…",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = Color.White
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    singleLine = false,
                    shape = RoundedCornerShape(8.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color.White,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val text = inputText.trim()
                            if (text.isNotEmpty()) {
                                addMessage(text, isUser = true)
                                inputText = ""
                                openMainChat(context, text)
                            }
                        }
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 发送按钮
                IconButton(
                    onClick = {
                        val text = inputText.trim()
                        if (text.isNotEmpty()) {
                            addMessage(text, isUser = true)
                            inputText = ""
                            openMainChat(context, text)
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF60A5FA))
                ) {
                    // 使用简单的三角形文本作为发送图标
                    Text(
                        text = "▶",
                        color = Color.White,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    @Composable
    private fun ChatBubble(msg: ChatMessage) {
        val isUser = msg.isUser
        val bgColor = if (isUser) Color(0xFF60A5FA) else Color.White.copy(alpha = 0.25f)
        val shape = RoundedCornerShape(
            topStart = 8.dp,
            topEnd = 8.dp,
            bottomStart = if (isUser) 8.dp else 0.dp,
            bottomEnd = if (isUser) 0.dp else 8.dp
        )

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Text(
                text = msg.text,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 10,
                modifier = Modifier
                    .clip(shape)
                    .background(bgColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
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
