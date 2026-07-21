package me.rerere.rikkahub.ui.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * AI 对话浮窗管理器。
 * 使用 View-based 方式构建聊天界面，避免 ComposeView 在 WindowManager 中需要 LifecycleOwner 的问题。
 */
object FloatingChatWindow {
    private const val TAG = "FloatingChatWindow"

    @Volatile
    private var floatingWindow: DraggableFloatingWindow? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var messages: MutableList<ChatMessage> = mutableListOf(
        ChatMessage("你好！我是 Rikka AI，随时为你服务。", isUser = false)
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    // 缓存当前 View 引用，方便刷新
    private var messageContainer: LinearLayout? = null
    private var inputField: EditText? = null

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
        messageContainer = null
        inputField = null
    }

    fun isShowing(): Boolean = floatingWindow?.isShown() == true

    fun addMessage(text: String, isUser: Boolean) {
        messages.add(ChatMessage(text, isUser))
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
        fw.show(buildChatView(app))
    }

    private fun refreshContent() {
        val fw = floatingWindow ?: return
        if (!fw.isShown()) return
        val ctx = appContext ?: return
        messageContainer?.let { mc ->
            mc.removeAllViews()
            messages.forEach { msg ->
                mc.addView(createMessageBubble(ctx, msg))
            }
            // 滚动到底部
            mc.post {
                (mc.parent as? View)?.let { parent ->
                    (parent.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun buildChatView(context: Context): View {
        val density = context.resources.displayMetrics.density
        val pad8 = (8 * density).toInt()
        val pad4 = (4 * density).toInt()

        // 外层容器
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad8, pad8, pad8, pad8)
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(0xE61E1E2E.toInt())
            }
        }

        // 标题
        val title = TextView(context).apply {
            text = "💬 AI 助手"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        root.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = pad4 })

        // 分隔线
        val divider = View(context).apply {
            setBackgroundColor(0x40FFFFFF.toInt())
        }
        root.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ))

        // 消息列表（ScrollView 包裹 LinearLayout）
        val mc = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad4, 0, pad4)
        }
        messageContainer = mc
        messages.forEach { msg ->
            mc.addView(createMessageBubble(context, msg))
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(mc, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // 分隔线
        val divider2 = View(context).apply {
            setBackgroundColor(0x40FFFFFF.toInt())
        }
        root.addView(divider2, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { topMargin = pad4 })

        // 输入区域
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad4, 0, 0)
        }

        val editText = EditText(context).apply {
            hint = "输入消息…"
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 3
            background = GradientDrawable().apply {
                cornerRadius = 8f
                setStroke(1, 0x40FFFFFF.toInt())
                setColor(0x00000000)
            }
            setPadding(pad8, (6 * density).toInt(), pad8, (6 * density).toInt())
        }
        inputField = editText

        // 输入框获取焦点时让窗口可聚焦，弹出输入法
        editText.setOnFocusChangeListener { _, hasFocus ->
            floatingWindow?.setWindowFocusable(hasFocus)
        }

        val sendBtn = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_media_play)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            val btnPad = (8 * density).toInt()
            setPadding(btnPad, btnPad, btnPad, btnPad)
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF60A5FA.toInt())
            }
            setOnClickListener {
                val text = editText.text.toString().trim()
                if (text.isNotEmpty()) {
                    addMessage(text, isUser = true)
                    editText.setText("")
                    openMainChat(context, text)
                }
            }
        }

        val inputLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        inputLp.rightMargin = pad4
        inputRow.addView(editText, inputLp)
        inputRow.addView(sendBtn, LinearLayout.LayoutParams(
            (40 * density).toInt(),
            (40 * density).toInt()
        ))

        root.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        return root
    }

    private fun createMessageBubble(context: Context, msg: ChatMessage): View {
        val density = context.resources.displayMetrics.density
        val bgColor = if (msg.isUser) 0xFF60A5FA.toInt() else 0x40FFFFFF.toInt()
        val gravity = if (msg.isUser) Gravity.END else Gravity.START

        val bubble = TextView(context).apply {
            text = msg.text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 10
            setPadding((10 * density).toInt(), (6 * density).toInt(),
                (10 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    8f, 8f,  // top-left
                    8f, 8f,  // top-right
                    if (msg.isUser) 8f else 0f, if (msg.isUser) 8f else 0f,  // bottom-right
                    if (msg.isUser) 0f else 8f, if (msg.isUser) 0f else 8f   // bottom-left
                )
                setColor(bgColor)
            }
        }

        val container = FrameLayout(context)
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.gravity = gravity
            val margin4 = (4 * density).toInt()
            setMargins(margin4, margin4, margin4, margin4)
        }
        container.addView(bubble, lp)

        return container
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
