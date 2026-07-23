package me.rerere.rikkahub.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

import kotlin.math.roundToInt

/**
 * 可拖拽 + 可缩放的悬浮窗口外壳。
 * 基于 WindowManager，零外部依赖。
 *
 * 用法：
 *   val window = DraggableFloatingWindow(context)
 *   window.show(contentView)
 *   window.setContent(newContentView)
 *   window.hide()
 *
 * 接口设计：
 * - header 32dp：拖拽区域 + 右上角关闭按钮
 * - content：内容 View 区（ScrollView 包裹）
 * - footer 24dp：右下角缩放把手
 * - 左上角锁定/解锁按钮
 */
class DraggableFloatingWindow(
    private val context: Context
) {
    companion object {
        const val DEFAULT_WIDTH_DP = 320
        const val DEFAULT_HEIGHT_DP = 240
        const val MIN_WIDTH_DP = 160
        const val MIN_HEIGHT_DP = 120
        const val HEADER_HEIGHT_DP = 32
        const val FOOTER_HEIGHT_DP = 24
        const val CLOSE_BTN_DP = 28
    }

    @Volatile
    var locked: Boolean = false

    @Volatile
    var initialX: Int = -1

    @Volatile
    var initialY: Int = -1

    @Volatile
    var widthDp: Int = DEFAULT_WIDTH_DP

    @Volatile
    var heightDp: Int = DEFAULT_HEIGHT_DP

    @Volatile
    var onDismiss: (() -> Unit)? = null

    @Volatile
    var onPositionChanged: ((x: Int, y: Int, w: Int, h: Int) -> Unit)? = null

    private val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private val wm: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var contentSlot: FrameLayout? = null
    private var headerView: View? = null
    private var footerView: View? = null
    private var lockButton: ImageView? = null
    private var lastKnownScreenW: Int = 0
    private var lastKnownScreenH: Int = 0

    fun isShown(): Boolean = rootView != null

    /**
     * 显示悬浮窗口。内容通过 View 方式提供。
     *
     * @param wrapContent 是否用 ScrollView 包裹内容。
     *        如果内容是 ComposeView（自带 LazyColumn 等滚动容器），应传 false，
     *        避免嵌套滚动冲突。
     * @param onRootPreAttach root 创建后、addView 前的回调，
     *        用于设置 ViewTreeLifecycleOwner 等 Compose 所需的属性。
     */
    fun show(content: View, wrapContent: Boolean = true, onRootPreAttach: ((View) -> Unit)? = null) {
        if (isShown()) hide()
        val density = context.resources.displayMetrics.density
        val (screenW, screenH) = currentScreenSize()
        val w = (widthDp.coerceAtLeast(MIN_WIDTH_DP) * density).roundToInt()
        val h = (heightDp.coerceAtLeast(MIN_HEIGHT_DP) * density).roundToInt()

        val root = buildShell(content, wrapContent)

        // addView 前回调，让调用方有机会在 root 上设置 ViewTree 属性
        onRootPreAttach?.invoke(root)

        val centerX = ((screenW - w) / 2).coerceAtLeast(0)
        val centerY = ((screenH - h) / 2).coerceAtLeast(0)
        val statusBarH = statusBarHeightPx()
        val finalX = when {
            initialX + w <= 0 -> centerX
            initialX >= screenW -> centerX
            else -> initialX.coerceIn(0, (screenW - w).coerceAtLeast(0))
        }
        val finalY = when {
            initialY + h <= 0 -> centerY
            initialY >= screenH -> centerY
            initialY < statusBarH -> statusBarH.coerceAtMost((screenH - h).coerceAtLeast(0))
            else -> initialY.coerceIn(0, (screenH - h).coerceAtLeast(0))
        }

        val params = newLayoutParams(w, h).apply {
            gravity = Gravity.TOP or Gravity.START
            x = finalX
            y = finalY
        }
        runCatching { wm.addView(root, params) }
        rootView = root
        layoutParams = params
        lastKnownScreenW = screenW
        lastKnownScreenH = screenH
        applyLocked()
    }

    /**
     * 替换内容区 View。不重建窗口，避免闪烁。
     */
    fun setContent(content: View) {
        val slot = contentSlot ?: return
        slot.removeAllViews()
        slot.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    /**
     * 屏幕旋转时调用 —— 按比例重算位置
     */
    fun onConfigurationChanged() {
        val v = rootView ?: return
        val params = layoutParams ?: return
        val (newScreenW, newScreenH) = currentScreenSize()
        if (newScreenW <= 0 || newScreenH <= 0) return
        if (newScreenW == lastKnownScreenW && newScreenH == lastKnownScreenH) return
        val oldW = lastKnownScreenW.takeIf { it > 0 } ?: newScreenW
        val oldH = lastKnownScreenH.takeIf { it > 0 } ?: newScreenH
        val ratioX = params.x.toFloat() / oldW
        val ratioY = params.y.toFloat() / oldH
        val density = context.resources.displayMetrics.density
        val w = (widthDp.coerceAtLeast(MIN_WIDTH_DP) * density).roundToInt()
        val h = (heightDp.coerceAtLeast(MIN_HEIGHT_DP) * density).roundToInt()
        val statusBarH = statusBarHeightPx()
        val newX = (ratioX * newScreenW).toInt().coerceIn(0, (newScreenW - w).coerceAtLeast(0))
        val newY = (ratioY * newScreenH).toInt()
            .coerceIn(statusBarH.coerceAtMost((newScreenH - h).coerceAtLeast(0)),
                (newScreenH - h).coerceAtLeast(0))
        params.x = newX
        params.y = newY
        params.width = w
        params.height = h
        runCatching { wm.updateViewLayout(v, params) }
        lastKnownScreenW = newScreenW
        lastKnownScreenH = newScreenH
        initialX = newX
        initialY = newY
        firePositionChanged()
    }

    fun hide() {
        setWindowFocusable(false)
        val v = rootView ?: return
        runCatching { wm.removeView(v) }
        rootView = null
        layoutParams = null
        contentSlot = null
        headerView = null
        footerView = null
        lockButton = null
        lastKnownScreenW = 0
        lastKnownScreenH = 0
    }

    /**
     * 重置位置和大小到默认值（居中 + 默认尺寸）
     */
    fun resetToDefault() {
        initialX = -1
        initialY = -1
        widthDp = DEFAULT_WIDTH_DP
        heightDp = DEFAULT_HEIGHT_DP
        val v = rootView ?: return
        val params = layoutParams ?: return
        val density = context.resources.displayMetrics.density
        val w = (widthDp * density).roundToInt()
        val h = (heightDp * density).roundToInt()
        val (screenW, screenH) = currentScreenSize()
        params.width = w
        params.height = h
        params.x = ((screenW - w) / 2).coerceAtLeast(0)
        params.y = ((screenH - h) / 2).coerceAtLeast(0)
        runCatching { wm.updateViewLayout(v, params) }
        firePositionChanged()
    }


    /**
     * 切换窗口是否可获取焦点（用于输入法弹出/收回）。
     * 弹输入法时需移除 FLAG_NOT_FOCUSABLE，收起时恢复。
     */
    fun setWindowFocusable(focusable: Boolean) {
        val v = rootView ?: return
        val params = layoutParams ?: return
        val currentlyFocusable = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
        if (currentlyFocusable == focusable) return
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { wm.updateViewLayout(v, params) }
    }
    // ==================== 内部实现 ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun buildShell(content: View, wrapContent: Boolean = true): View {
        val density = context.resources.displayMetrics.density
        val headerH = (HEADER_HEIGHT_DP * density).roundToInt()
        val closeBtnSize = (CLOSE_BTN_DP * density).roundToInt()

        // Root: FrameLayout（背景 + 圆角）
        val root = FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f
                setColor(0xE61E1E2E.toInt())
            }
            clipToOutline = true
        }

        // Wrap: VERTICAL LinearLayout（header + content + footer）
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ------ Header ------
        val header = FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.argb(0x30, 0, 0, 0))
        }
        // 拖拽手柄（灰色短条）
        val dragBar = View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 999f
                setColor(0x80B0BEC5.toInt())
            }
        }
        header.addView(dragBar, FrameLayout.LayoutParams(
            (32 * density).roundToInt(), (4 * density).roundToInt(), Gravity.CENTER
        ))
        // 关闭按钮
        val closeBtn = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            val pad = (6 * density).roundToInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            setOnClickListener { onDismiss?.invoke() ?: hide() }
        }
        header.addView(closeBtn, FrameLayout.LayoutParams(
            closeBtnSize, closeBtnSize, Gravity.END or Gravity.CENTER_VERTICAL
        ).apply { rightMargin = (4 * density).roundToInt() })
        attachDragListener(header)
        wrap.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, headerH
        ))
        headerView = header

        // ------ Content ------
        val slot = FrameLayout(context).apply {
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        contentSlot = slot
        if (wrapContent) {
            // 旧 View 内容：用 ScrollView 包裹，支持滚动
            val scroll = ScrollView(context).apply {
                isFillViewport = false
                addView(slot, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            wrap.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        } else {
            // Compose 内容自带滚动（LazyColumn），直接放 slot
            wrap.addView(slot, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }

        // ------ Footer (缩放) ------
        val footer = FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.argb(0x30, 0, 0, 0))
        }
        val resizeHandle = View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(0x80B0BEC5.toInt())
            }
        }
        footer.addView(resizeHandle, FrameLayout.LayoutParams(
            (40 * density).roundToInt(), (6 * density).roundToInt(),
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply { rightMargin = (8 * density).roundToInt() })
        attachResizeListener(footer)
        wrap.addView(footer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (FOOTER_HEIGHT_DP * density).roundToInt()
        ))
        footerView = footer

        root.addView(wrap, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // ------ 锁按钮（独立于 header/footer，始终可见） ------
        val lockBtn = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_lock_lock)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            val pad = (5 * density).roundToInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleLocked() }
        }
        root.addView(lockBtn, FrameLayout.LayoutParams(
            closeBtnSize, closeBtnSize, Gravity.START or Gravity.TOP
        ).apply {
            topMargin = (4 * density).roundToInt()
            leftMargin = (4 * density).roundToInt()
        })
        lockButton = lockBtn

        return root
    }

    private fun toggleLocked() {
        locked = !locked
        applyLocked()
    }

    private fun applyLocked() {
        val vis = if (locked) View.GONE else View.VISIBLE
        headerView?.visibility = vis
        footerView?.visibility = vis
        lockButton?.setImageResource(
            if (locked) android.R.drawable.ic_lock_lock
            else android.R.drawable.ic_lock_idle_lock
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragListener(handle: View) {
        var startTouchX = 0f
        var startTouchY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, ev ->
            if (locked) return@setOnTouchListener false
            val params = layoutParams ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startTouchX = ev.rawX
                    startTouchY = ev.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - startTouchX).toInt()
                    val dy = (ev.rawY - startTouchY).toInt()
                    val (screenW, screenH) = currentScreenSize()
                    params.x = (startX + dx).coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                    params.y = (startY + dy).coerceIn(0, (screenH - params.height).coerceAtLeast(0))
                    val v = rootView ?: return@setOnTouchListener false
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    initialX = params.x
                    initialY = params.y
                    firePositionChanged()
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachResizeListener(handle: View) {
        var startTouchX = 0f
        var startTouchY = 0f
        var startW = 0
        var startH = 0
        val density = context.resources.displayMetrics.density
        handle.setOnTouchListener { _, ev ->
            if (locked) return@setOnTouchListener false
            val params = layoutParams ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startTouchX = ev.rawX
                    startTouchY = ev.rawY
                    startW = params.width
                    startH = params.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - startTouchX).toInt()
                    val dy = (ev.rawY - startTouchY).toInt()
                    val (screenW, screenH) = currentScreenSize()
                    val minW = (MIN_WIDTH_DP * density).roundToInt()
                    val minH = (MIN_HEIGHT_DP * density).roundToInt()
                    val maxW = (screenW - params.x).coerceAtLeast(minW)
                    val maxH = (screenH - params.y).coerceAtLeast(minH)
                    params.width = (startW + dx).coerceIn(minW, maxW)
                    params.height = (startH + dy).coerceIn(minH, maxH)
                    val v = rootView ?: return@setOnTouchListener false
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    widthDp = (params.width / density).roundToInt()
                    heightDp = (params.height / density).roundToInt()
                    firePositionChanged()
                    true
                }
                else -> false
            }
        }
    }

    private fun firePositionChanged() {
        onPositionChanged?.invoke(initialX, initialY, widthDp, heightDp)
    }

    private fun newLayoutParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }

    private fun statusBarHeightPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return runCatching {
                wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.displayCutout()
                ).top
            }.getOrDefault(0)
        }
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId)
        else (24 * context.resources.displayMetrics.density).toInt()
    }

    private fun currentScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            metrics.bounds.width() to metrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            dm.widthPixels to dm.heightPixels
        }
    }
}
