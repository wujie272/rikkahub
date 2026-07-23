package me.rerere.rikkahub.ui.overlay

import android.annotation.SuppressLint
import android.util.Log
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private typealias DockSide = LiquidFloatingContainer.DockSide

/**
 * 悬浮触发球。
 * 带液态吸附尾巴、弹簧弹性吸附、弧形菜单。
 *
 * 用法：
 *   val ball = FloatingTriggerBall(context)
 *   ball.onTap = { /* 唤出 AI 对话 */ }
 *   ball.onLongPress = { /* 显示弧形菜单 */ }
 *   ball.show()
 */
class FloatingTriggerBall(
    private val context: Context
) {
    enum class Mode { ICON, LIVE2D }

    @Volatile var mode: Mode = Mode.ICON
        set(value) {
            if (field == value) return
            field = value
            rebuildContentView()
            onModeChanged?.invoke(value)
        }
    @Volatile var live2DRenderer: me.rerere.rikkahub.ui.overlay.Live2DRenderer? = null
    @Volatile var onModeChanged: ((Mode) -> Unit)? = null

    private fun rebuildContentView() {
        val container = liquidView ?: return
        val density = context.resources.displayMetrics.density
        val size = (sizeDp.coerceIn(28, 96) * density).toInt()
        container.removeAllViews()
        when (mode) {
            Mode.ICON -> {
                val iv = ImageView(context).apply {
                    setImageResource(android.R.drawable.ic_dialog_info)
                    imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    val pad = (size * 0.18f).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                iconView = iv
                container.addView(iv, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                live2DRenderer?.release()
                live2DRenderer = null
            }
            Mode.LIVE2D -> {
                val renderer = me.rerere.rikkahub.ui.overlay.Live2DRenderer(context)
                live2DRenderer = renderer
                container.addView(renderer.surfaceView, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                iconView = null
            }
        }
    }


    companion object {
        const val DEFAULT_SIZE_DP = 48
        const val AUTO_DOCK_DELAY_MS = 3000L
    }

    @Volatile var onTap: (() -> Unit)? = null
    @Volatile var onLongPress: (() -> Unit)? = null
    @Volatile var onRegionPick: (() -> Unit)? = null
    @Volatile var onPositionChanged: ((x: Int, y: Int) -> Unit)? = null
    /** 用于位置持久化的协程作用域，由 OverlayManager 注入，默认使用 IO 调度器 */
    @Volatile var ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var snapToEdge: Boolean = true
    @Volatile var autoDock: Boolean = false
    @Volatile var sizeDp: Int = DEFAULT_SIZE_DP
    @Volatile var initialX: Int = -1
    @Volatile var initialY: Int = -1
    @Volatile var dockInsetPx: Int = 0
    @Volatile var color: Int = 0xFF1E88E5.toInt()

    private val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private val wm: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private var view: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var iconView: ImageView? = null
    private var liquidView: LiquidFloatingContainer? = null
    private var snapAnimX: SpringAnimation? = null
    private var snapAnimY: SpringAnimation? = null
    private var arcMenuView: View? = null
    private val autoDockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoDockRunnable = Runnable {
        if (snapToEdge && autoDock && dockSide == DockSide.NONE) {
            snapToEdge()
            view?.alpha = 1.0f
        }
    }
    private var positionBeforeMenu: Pair<Int, Int>? = null

    private val dockSide: DockSide get() = liquidView?.side ?: DockSide.NONE

    fun isShown(): Boolean = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return
        val density = context.resources.displayMetrics.density
        val size = (sizeDp.coerceIn(28, 96) * density).toInt()
        val containerW = (size * 1.3f).toInt()
        val containerH = (size * 1.4f).toInt()

        val container = LiquidFloatingContainer(context).apply {
            fillColor = this@FloatingTriggerBall.color
            strokeColor = 0xFFFFFFFF.toInt()
            strokeWidthPx = 2f * density
            ballRadius = size / 2f
        }
        liquidView = container

        // 根据当前 mode 创建初始内容，而不是硬编码图标
        when (mode) {
            Mode.ICON -> {
                val iv = ImageView(context).apply {
                    setImageResource(android.R.drawable.ic_dialog_info)
                    imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    val pad = (size * 0.18f).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                iconView = iv
                container.addView(iv, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            }
            Mode.LIVE2D -> {
                val renderer = Live2DRenderer(context)
                live2DRenderer = renderer
                container.addView(renderer.surfaceView, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                iconView = null
            }
        }

        val (screenW, screenH) = currentScreenSize()
        val params = WindowManager.LayoutParams(
            containerW, containerH,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (initialX >= 0 && initialY >= 0) {
                x = initialX.coerceIn(0, (screenW - containerW).coerceAtLeast(0))
                y = initialY.coerceIn(0, screenH - containerH)
            } else {
                x = (16 * density).toInt()
                y = (screenH / 4).coerceIn(containerH, screenH - containerH * 2)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        attachTouchListener(container, params)
        container.isClickable = true
        container.isFocusable = true
        container.setOnClickListener { onTap?.invoke() }
        container.setOnLongClickListener {
            showArcMenu()
            true
        }

        runCatching { wm.addView(container, params) }
            .onFailure { e ->
                Log.e("FloatingTriggerBall", "Failed to add overlay view", e)
                return
            }
        view = container
        layoutParams = params

        if (snapToEdge) container.post { snapToEdge() }
    }

    fun hide() {
        autoDockHandler.removeCallbacks(autoDockRunnable)
        dismissArcMenu(restorePosition = false)
        snapAnimX?.cancel(); snapAnimX = null
        snapAnimY?.cancel(); snapAnimY = null
        // 释放 Live2D 渲染器，防止内存泄漏
        live2DRenderer?.release()
        live2DRenderer = null
        iconView = null
        liquidView = null
        layoutParams?.let { initialX = it.x; initialY = it.y }
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        layoutParams = null
    }

    fun setIcon(iconRes: Int) {
        iconView?.setImageResource(iconRes)
    }

    fun onConfigurationChanged() {
        val params = layoutParams ?: return
        val v = view ?: return
        val (screenW, screenH) = currentScreenSize()
        params.x = params.x.coerceIn(0, (screenW - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, screenH - params.height)
        runCatching { wm.updateViewLayout(v, params) }
        v.post { if (dockSide != DockSide.NONE) snapToEdge() }
    }

    /**
     * 已显示时动态调整触发球大小。
     * 更新 LiquidFloatingContainer 的 ballRadius、图标大小和窗口尺寸。
     */
    fun applySize() {
        val v = view ?: return
        val lp = layoutParams ?: return
        val density = context.resources.displayMetrics.density
        val newSize = (sizeDp.coerceIn(28, 96) * density).toInt()
        val newContainerW = (newSize * 1.3f).toInt()
        val newContainerH = (newSize * 1.4f).toInt()

        // 更新 LiquidFloatingContainer 的球半径
        liquidView?.ballRadius = newSize / 2f

        // 更新图标大小
        iconView?.let { iv ->
            iv.layoutParams = FrameLayout.LayoutParams(newSize, newSize, Gravity.CENTER)
            val pad = (newSize * 0.18f).toInt()
            iv.setPadding(pad, pad, pad, pad)
        }

        // 更新窗口尺寸
        lp.width = newContainerW
        lp.height = newContainerH
        runCatching { wm.updateViewLayout(v, lp) }

        // 重新吸附
        v.post { snapToEdge() }
    }

    // ==================== Touch 处理 ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchListener(target: View, params: WindowManager.LayoutParams) {
        val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 2f
        val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var initX = 0
        var initY = 0
        var downTime = 0L
        var moved = false
        var longPressFired = false
        val longPressRunnable = Runnable {
            if (!moved) {
                longPressFired = true
                showArcMenu()
            }
        }

        target.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    autoDockHandler.removeCallbacks(autoDockRunnable)
                    snapAnimY?.cancel(); snapAnimX?.cancel()
                    v.animate().cancel()
                    v.alpha = 1.0f
                    downX = ev.rawX
                    downY = ev.rawY
                    initX = params.x
                    initY = params.y
                    downTime = System.currentTimeMillis()
                    moved = false
                    longPressFired = false
                    v.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        v.removeCallbacks(longPressRunnable)
                        snapAnimX?.cancel()
                        if (dockSide != DockSide.NONE) {
                            // 唤醒：从 dock 状态平滑滑出，不瞬时跳变
                            val wakeX = wakeFromSnap()
                            initX = wakeX
                        } else {
                            initX = params.x
                        }
                        initY = params.y
                        downX = ev.rawX
                        downY = ev.rawY
                    }
                    if (moved) {
                        params.x = (initX + dx).toInt()
                        params.y = (initY + dy).toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressFired) {
                        onTap?.invoke()
                    } else if (moved) {
                        initialX = params.x
                        initialY = params.y
                        persistPosition()
                        if (snapToEdge) snapToEdge()
                    }
                    if (autoDock && snapToEdge) {
                        autoDockHandler.removeCallbacks(autoDockRunnable)
                        autoDockHandler.postDelayed(autoDockRunnable, AUTO_DOCK_DELAY_MS)
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ==================== Dock 唤醒 ====================

    /**
     * 从 dock 吸附态唤醒：恢复 alpha，用 SpringAnimation 平滑滑离边缘到 8dp margin 处。
     * 返回唤醒后的 X，用于 touch listener 重置 initX，让拖动从正确位置开始。
     */
    private fun wakeFromSnap(): Int {
        val v = view
        val params = layoutParams ?: return 0
        v?.animate()?.cancel()
        v?.alpha = 1.0f
        val docked = dockSide != DockSide.NONE
        if (docked) liquidView?.side = DockSide.NONE
        if (!docked) return params.x

        val (screenW, _) = currentScreenSize()
        val size = params.width
        val density = context.resources.displayMetrics.density
        val margin = (8 * density).toInt()
        val centerX = params.x + size / 2
        val targetX = if (centerX < screenW / 2) margin
        else (screenW - size - margin).coerceAtLeast(0)

        snapAnimX?.cancel()
        snapAnimX = SpringAnimation(FloatValueHolder(params.x.toFloat())).apply {
            spring = SpringForce(targetX.toFloat()).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                stiffness = SpringForce.STIFFNESS_HIGH
            }
            addUpdateListener { _, value, _ ->
                params.x = value.toInt()
                runCatching { wm.updateViewLayout(v, params) }
            }
            start()
        }
        return targetX
    }

    // ==================== 吸附 ====================

    private fun snapToEdge() {
        val v = view ?: return
        val params = layoutParams ?: return
        val (screenW, _) = currentScreenSize()
        val density = context.resources.displayMetrics.density
        val safeTop = (30 * density).toInt()
        val safeBottom = (48 * density).toInt()
        val (_, screenH) = currentScreenSize()
        val targetY = params.y.coerceIn(safeTop, screenH - params.height - safeBottom)

        snapAnimX?.cancel(); snapAnimY?.cancel()

        if (snapToEdge) {
            val centerX = params.x + params.width / 2
            val dockLeft = centerX < screenW / 2
            val inset = dockInsetPx.coerceAtLeast(0)
            val targetX = if (dockLeft) inset
            else (screenW - params.width - inset).coerceAtLeast(0)
            liquidView?.side = if (dockLeft) DockSide.LEFT else DockSide.RIGHT

            snapAnimX = SpringAnimation(FloatValueHolder(params.x.toFloat())).apply {
                spring = SpringForce(targetX.toFloat()).apply {
                    dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                    stiffness = 400f
                }
                addUpdateListener { _, value, _ ->
                    params.x = value.toInt()
                    runCatching { wm.updateViewLayout(v, params) }
                }
                addEndListener { _, _, _, _ ->
                    persistPosition()
                    v.animate().alpha(0.75f).setDuration(220L).start()
                }
                start()
            }
        } else {
            liquidView?.side = DockSide.NONE
            v.animate().cancel()
            v.alpha = 1.0f
            persistPosition()
        }

        snapAnimY = SpringAnimation(FloatValueHolder(params.y.toFloat())).apply {
            spring = SpringForce(targetY.toFloat()).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                stiffness = 400f
            }
            addUpdateListener { _, value, _ ->
                params.y = value.toInt()
                runCatching { wm.updateViewLayout(v, params) }
            }
            start()
        }
    }

    // ==================== 弧形菜单 ====================

    private fun showArcMenu() {
        if (arcMenuView != null) return
        val params = layoutParams ?: return
        val density = context.resources.displayMetrics.density
        val (screenW, screenH) = currentScreenSize()
        val cx = params.x + when (dockSide) {
            DockSide.LEFT -> params.width / 2
            DockSide.RIGHT -> params.width / 2
            DockSide.NONE -> params.width / 2
        }
        val cy = params.y + params.height / 2
        val ballR = (sizeDp / 2f * density).toInt()
        val itemSize = (sizeDp * 0.85f * density).toInt().coerceAtLeast((40 * density).toInt())
        val itemR = itemSize / 2
        val gapPx = (28 * density).toInt()
        val radius = ballR + itemR + gapPx
        val items = listOf(
            ArcMenuItem("✂️", "框选", { onRegionPick?.invoke() }),
            ArcMenuItem("💬", "对话", { onTap?.invoke() }),
            ArcMenuItem("🔒", "锁定", { toggleLock() }),
            ArcMenuItem("⚙️", "设置", { /* 打开设置 */ }),
            ArcMenuItem("✕", "关闭", { hide() }),
        )
        val itemCount = items.size
        val spread = when (itemCount) {
            1 -> 0.0; 2 -> Math.PI / 6; 3 -> Math.PI / 4
            4 -> Math.toRadians(54.0); 5 -> Math.toRadians(72.0)
            else -> Math.PI / 2
        }

        // 找最大空间方向
        val nearTop = cy - radius - itemSize < 0
        val nearBottom = cy + radius + itemSize > screenH
        val nearLeft = cx - radius - itemSize < 0
        val nearRight = cx + radius + itemSize > screenW
        val baseAngle: Double = when {
            nearTop && nearLeft -> Math.PI / 4
            nearTop && nearRight -> 3 * Math.PI / 4
            nearBottom && nearLeft -> -Math.PI / 4
            nearBottom && nearRight -> -3 * Math.PI / 4
            nearTop -> Math.PI / 2
            nearBottom -> -Math.PI / 2
            cx < screenW / 2 -> 0.0
            else -> Math.PI
        }

        val angles = DoubleArray(itemCount) { i ->
            baseAngle - spread + 2 * spread * i / (itemCount - 1).coerceAtLeast(1)
        }

        // 全屏背景层
        val root = FrameLayout(context).apply {
            setBackgroundColor(0x00000000)
            isClickable = true
            setOnClickListener { dismissArcMenu() }
        }

        items.forEachIndexed { idx, item ->
            val angle = angles[idx]
            val offsetX = (radius * Math.cos(angle)).toFloat()
            val offsetY = (radius * Math.sin(angle)).toFloat()
            val left = (cx + offsetX - itemSize / 2f).toInt()
                .coerceIn(0, (screenW - itemSize).coerceAtLeast(0))
            val top = (cy + offsetY - itemSize / 2f).toInt()
                .coerceIn(0, (screenH - itemSize).coerceAtLeast(0))

            // 圆形按钮：圆底 + emoji + 文字标签
            val btnWrapper = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                alpha = 0f
                scaleX = 0.4f
                scaleY = 0.4f
                rotation = -360f
                translationX = cx - (left + itemSize / 2f)
                translationY = cy - (top + itemSize / 2f)
                isClickable = true
                setOnClickListener { item.onClick(); dismissArcMenu() }
                contentDescription = item.label
            }

            // 圆形背景 + emoji
            val circleSize = itemSize
            val circle = FrameLayout(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xCC2C2C3E.toInt())
                    setStroke((1.5f * density).toInt(), 0x55FFFFFF.toInt())
                }
            }
            val emoji = android.widget.TextView(context).apply {
                text = item.icon
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
            }
            circle.addView(emoji, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            btnWrapper.addView(circle, circleSize, circleSize)

            // 文字标签
            val label = android.widget.TextView(context).apply {
                text = item.label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(0xCCFFFFFF.toInt())
                // 单行 + 省略号防溢出
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val labelMarginTop = (4 * density).toInt()
            val labelLp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = labelMarginTop }
            btnWrapper.addView(label, labelLp)

            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = left
                topMargin = top
            }
            root.addView(btnWrapper, lp)

            // 旋出动画：从球中心旋转飞出，OvershootInterpolator 让落位时轻微过冲再回弹
            btnWrapper.animate()
                .alpha(0.85f).scaleX(1f).scaleY(1f)
                .rotation(0f)
                .translationX(0f).translationY(0f)
                .setStartDelay(50L * idx)
                .setDuration(380L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                .start()
        }

        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        runCatching { wm.addView(root, menuParams) }
        arcMenuView = root
    }

    private fun dismissArcMenu(restorePosition: Boolean = true) {
        arcMenuView?.let { runCatching { wm.removeView(it) } }
        arcMenuView = null
        if (autoDock && snapToEdge) {
            autoDockHandler.removeCallbacks(autoDockRunnable)
            autoDockHandler.postDelayed(autoDockRunnable, AUTO_DOCK_DELAY_MS)
        }
    }

    private fun toggleLock() {
        // 简单的锁定视觉反馈
        iconView?.setImageResource(
            if (liquidView?.strokeWidthPx == 0f) android.R.drawable.ic_lock_idle_lock
            else android.R.drawable.ic_dialog_info
        )
    }

    // ==================== 持久化 ====================

    @Volatile private var positionPersistPending = false
    private fun persistPosition() {
        if (positionPersistPending) return
        positionPersistPending = true
        val params = layoutParams ?: run { positionPersistPending = false; return }
        val x = params.x; val y = params.y
        ioScope.launch {
            onPositionChanged?.invoke(x, y)
            positionPersistPending = false
        }
    }

    // ==================== 工具方法 ====================

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

    private data class ArcMenuItem(
        val icon: String,
        val label: String,
        val onClick: () -> Unit
    )
}
