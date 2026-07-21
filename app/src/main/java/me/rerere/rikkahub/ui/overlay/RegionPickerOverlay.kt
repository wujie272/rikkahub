package me.rerere.rikkahub.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.ceil
import kotlin.math.min

/**
 * 悬浮窗版区域选择，完全对齐 overlay-translator 的 RegionPickerOverlay + RegionPickerView。
 *
 * 两阶段：
 * 阶段 1 DRAWING — 拖拽画矩形
 * 阶段 2 ADJUSTING — 8 个手柄微调（4 角 + 4 边中点）+ 框内拖动整体移动
 */
class RegionPickerOverlay(private val context: Context) {
    private val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private val wm: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private var container: FrameLayout? = null
    private var pickerView: RegionPickerView? = null
    private var toolbar: ViewGroup? = null

    fun isShown(): Boolean = container != null

    fun show(
        initial: Rect? = null,
        onConfirm: (Rect) -> Unit,
        onCancel: () -> Unit,
        onClearAll: () -> Unit = onCancel
    ) {
        if (container != null) return

        lateinit var doCancel: () -> Unit
        lateinit var doConfirm: () -> Unit
        lateinit var doClearAll: () -> Unit

        val picker = RegionPickerView(
            context = context,
            initial = initial,
            onCancel = onCancel,
            onClearAllRequested = { doClearAll() }
        )
        pickerView = picker

        val tb = buildToolbar(
            onRedo = { picker.resetToDrawing() },
            onCancel = { doCancel() },
            onConfirm = { doConfirm() }
        )
        toolbar = tb

        val root = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        doCancel()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            addView(picker, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(tb, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(16)
                bottomMargin = dp(32)
            })
        }
        container = root

        doCancel = { dismiss(); onCancel() }
        doConfirm = {
            val r = picker.currentRect()
            if (r != null && r.width() >= 20 && r.height() >= 20) {
                dismiss(); onConfirm(r)
            } else {
                dismiss(); onCancel()
            }
        }
        doClearAll = { dismiss(); onClearAll() }

        picker.onRectChanged = { rect -> placeToolbar(rect) }

        val (physW, physH) = physicalScreenSize()
        val params = WindowManager.LayoutParams(
            physW, physH,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0; fitInsetsSides = 0
            }
        }
        runCatching { wm.addView(root, params) }
        root.post { picker.post { placeToolbar(picker.currentRect()) } }
    }

    fun dismiss() {
        container?.let { runCatching { wm.removeView(it) } }
        container = null; pickerView = null; toolbar = null
    }

    private fun placeToolbar(rect: Rect?) {
        val tb = toolbar ?: return
        val parent = container ?: return
        val parentW = parent.width; val parentH = parent.height
        val tbW = tb.width; val tbH = tb.height
        if (tbW == 0 || tbH == 0 || parentW == 0 || parentH == 0) {
            parent.post { placeToolbar(rect) }; return
        }
        val gap = dp(12); val safe = dp(16)
        val lp = tb.layoutParams as FrameLayout.LayoutParams
        if (rect != null && rect.width() >= 20 && rect.height() >= 20) {
            var top = rect.bottom + gap
            if (top + tbH > parentH - safe) top = rect.top - gap - tbH
            if (top < safe) top = parentH - safe - tbH
            val left = (rect.centerX() - tbW / 2).coerceIn(safe, parentW - tbW - safe)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = left; lp.topMargin = top
            lp.rightMargin = 0; lp.bottomMargin = 0
        } else {
            lp.gravity = Gravity.BOTTOM or Gravity.END
            lp.leftMargin = 0; lp.topMargin = 0
            lp.rightMargin = safe; lp.bottomMargin = dp(32)
        }
        tb.layoutParams = lp
    }

    private fun buildToolbar(onRedo: () -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit): LinearLayout {
        val bg = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0xCC222222.toInt()) }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; background = bg
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(toolbarButton("重选", false, onRedo))
            addView(toolbarButton("取消", false, onCancel))
            addView(toolbarButton("确定", true, onConfirm))
        }
    }

    private fun toolbarButton(text: String, primary: Boolean, onClick: () -> Unit): Button {
        return Button(context).apply {
            this.text = text; isAllCaps = false
            setTextColor(if (primary) Color.WHITE else 0xFFE0E0E0.toInt())
            val bg = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (primary) 0xFF1976D2.toInt() else 0xFF424242.toInt())
            }
            background = bg; minWidth = dp(72)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4); marginEnd = dp(4) }
            setOnClickListener { onClick() }
        }
    }

    private fun physicalScreenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION") display.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    // ==================== RegionPickerView ====================

    private class RegionPickerView(
        context: Context,
        private val initial: Rect?,
        private val onCancel: () -> Unit,
        private val onClearAllRequested: (() -> Unit)? = null
    ) : View(context) {

        var onRectChanged: ((Rect?) -> Unit)? = null

        enum class Mode { DRAWING, ADJUSTING }
        private enum class DragKind { NONE, MOVE, N, S, W, E, NW, NE, SW, SE }

        private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt(); style = Paint.Style.FILL }
        private val rectStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2f) }
        private val clearInside = Paint().apply { color = Color.TRANSPARENT; xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val handleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1976D2.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
        private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = sp(14f) }
        private val sizePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = sp(12f) }
        private val sizeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt(); style = Paint.Style.FILL }

        private val handleRadius = dp(8f)
        private val handleHitRadius = dp(28f)
        private val minSide = dp(40f).toInt()

        private var mode: Mode = if (initial != null) Mode.ADJUSTING else Mode.DRAWING
        private var rect: Rect? = initial?.let { Rect(it) }
        private var dragStartX = 0f; private var dragStartY = 0f
        private var dragKind: DragKind = DragKind.NONE
        private var dragAnchor: Rect? = null
        private var dragDownX = 0f; private var dragDownY = 0f
        private var lastTapTime = 0L

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            isClickable = true; isFocusable = true
        }

        fun currentRect(): Rect? = rect?.let { Rect(it) }
        fun currentMode(): Mode = mode

        fun resetToDrawing() {
            rect = null; mode = Mode.DRAWING; dragKind = DragKind.NONE; dragAnchor = null
            invalidate(); onRectChanged?.invoke(rect?.let { Rect(it) })
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rect?.let { r ->
                r.left = r.left.coerceIn(0, w - minSide)
                r.top = r.top.coerceIn(0, h - minSide)
                r.right = r.right.coerceIn(r.left + minSide, w)
                r.bottom = r.bottom.coerceIn(r.top + minSide, h)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
            val r = rect
            if (r != null && r.width() > 0 && r.height() > 0) {
                canvas.drawRect(r, clearInside)
                canvas.drawRect(r, rectStroke)
                if (mode == Mode.ADJUSTING) {
                    drawHandles(canvas, r)
                    drawSizeLabel(canvas, r)
                }
            } else {
                canvas.drawText("在屏幕上拖拽选择区域", dp(20f), dp(64f), tipPaint)
            }
        }

        private fun drawHandles(canvas: Canvas, r: Rect) {
            for ((cx, cy) in handleCenters(r)) {
                canvas.drawCircle(cx, cy, handleRadius, handleFill)
                canvas.drawCircle(cx, cy, handleRadius, handleBorder)
            }
        }

        private fun drawSizeLabel(canvas: Canvas, r: Rect) {
            val label = "${r.width()} × ${r.height()}"
            val padding = dp(6f).toInt()
            val metrics = sizePaint.fontMetrics
            val textHeight = ceil(metrics.descent - metrics.ascent).toInt()
            val labelWidth = ceil(sizePaint.measureText(label)).toInt() + padding * 2
            val labelHeight = textHeight + padding * 2
            val gap = ceil(handleRadius + dp(3f)).toInt()
            val margin = dp(4f).toInt()

            // 智能放置：优先上方，不够再下方
            val topSpace = r.top - margin
            val bottomSpace = height - r.bottom - margin
            val bx: Int; val by: Int
            if (topSpace >= labelHeight + gap) {
                bx = (r.left + r.width() / 2 - labelWidth / 2).coerceIn(margin, width - labelWidth - margin)
                by = r.top - gap - labelHeight
            } else if (bottomSpace >= labelHeight + gap) {
                bx = (r.left + r.width() / 2 - labelWidth / 2).coerceIn(margin, width - labelWidth - margin)
                by = r.bottom + gap
            } else {
                bx = margin; by = margin
            }

            val fr = RectF(bx.toFloat(), by.toFloat(), (bx + labelWidth).toFloat(), (by + labelHeight).toFloat())
            canvas.drawRoundRect(fr, dp(4f), dp(4f), sizeBg)
            canvas.drawText(label, (bx + padding).toFloat(), by + padding - metrics.ascent, sizePaint)
        }

        private fun handleCenters(r: Rect): List<Pair<Float, Float>> {
            val cx = (r.left + r.right) / 2f; val cy = (r.top + r.bottom) / 2f
            return listOf(
                r.left.toFloat() to r.top.toFloat(),    // NW
                r.right.toFloat() to r.top.toFloat(),   // NE
                r.left.toFloat() to r.bottom.toFloat(), // SW
                r.right.toFloat() to r.bottom.toFloat(),// SE
                cx to r.top.toFloat(),                   // N
                cx to r.bottom.toFloat(),                // S
                r.left.toFloat() to cy,                  // W
                r.right.toFloat() to cy                  // E
            )
        }

        private fun hitTest(x: Float, y: Float): DragKind {
            val r = rect ?: return DragKind.NONE
            val cx = (r.left + r.right) / 2f; val cy = (r.top + r.bottom) / 2f
            fun near(hx: Float, hy: Float) = kotlin.math.abs(x - hx) <= handleHitRadius && kotlin.math.abs(y - hy) <= handleHitRadius
            return when {
                near(r.left.toFloat(), r.top.toFloat()) -> DragKind.NW
                near(r.right.toFloat(), r.top.toFloat()) -> DragKind.NE
                near(r.left.toFloat(), r.bottom.toFloat()) -> DragKind.SW
                near(r.right.toFloat(), r.bottom.toFloat()) -> DragKind.SE
                near(cx, r.top.toFloat()) -> DragKind.N
                near(cx, r.bottom.toFloat()) -> DragKind.S
                near(r.left.toFloat(), cy) -> DragKind.W
                near(r.right.toFloat(), cy) -> DragKind.E
                r.contains(x.toInt(), y.toInt()) -> DragKind.MOVE
                else -> DragKind.NONE
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            return when (mode) {
                Mode.DRAWING -> onTouchDrawing(event)
                Mode.ADJUSTING -> onTouchAdjusting(event)
            }
        }

        private fun onTouchDrawing(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.x; dragStartY = event.y
                    rect = Rect(event.x.toInt(), event.y.toInt(), event.x.toInt(), event.y.toInt())
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    rect = makeRect(dragStartX, dragStartY, event.x, event.y)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val r = rect
                    if (r == null || r.width() < 20 || r.height() < 20) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 300) {
                            lastTapTime = 0
                            val handler = onClearAllRequested
                            if (handler != null) handler.invoke()
                            else { rect = Rect(0, 0, width, height); mode = Mode.ADJUSTING; invalidate() }
                        } else { lastTapTime = now; rect = null; invalidate() }
                    } else { mode = Mode.ADJUSTING; invalidate() }
                }
                MotionEvent.ACTION_CANCEL -> { rect = null; invalidate() }
            }
            return true
        }

        private fun onTouchAdjusting(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragKind = hitTest(event.x, event.y)
                    dragDownX = event.x; dragDownY = event.y
                    dragAnchor = rect?.let { Rect(it) }
                    if (dragKind == DragKind.NONE) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 300) { resetToDrawing(); lastTapTime = 0 }
                        else { lastTapTime = now }
                    }
                }
                MotionEvent.ACTION_MOVE -> applyDrag(event.x, event.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragKind = DragKind.NONE; dragAnchor = null }
            }
            return true
        }

        private fun applyDrag(x: Float, y: Float) {
            val anchor = dragAnchor ?: return; val r = rect ?: return
            val dx = (x - dragDownX).toInt(); val dy = (y - dragDownY).toInt()
            var nl = anchor.left; var nt = anchor.top; var nr = anchor.right; var nb = anchor.bottom
            when (dragKind) {
                DragKind.MOVE -> {
                    val w = anchor.width(); val h = anchor.height()
                    nl = (anchor.left + dx).coerceIn(0, width - w)
                    nt = (anchor.top + dy).coerceIn(0, height - h)
                    nr = nl + w; nb = nt + h
                }
                DragKind.NW -> { nl = (anchor.left + dx).coerceIn(0, anchor.right - minSide); nt = (anchor.top + dy).coerceIn(0, anchor.bottom - minSide) }
                DragKind.NE -> { nr = (anchor.right + dx).coerceIn(anchor.left + minSide, width); nt = (anchor.top + dy).coerceIn(0, anchor.bottom - minSide) }
                DragKind.SW -> { nl = (anchor.left + dx).coerceIn(0, anchor.right - minSide); nb = (anchor.bottom + dy).coerceIn(anchor.top + minSide, height) }
                DragKind.SE -> { nr = (anchor.right + dx).coerceIn(anchor.left + minSide, width); nb = (anchor.bottom + dy).coerceIn(anchor.top + minSide, height) }
                DragKind.N -> { nt = (anchor.top + dy).coerceIn(0, anchor.bottom - minSide) }
                DragKind.S -> { nb = (anchor.bottom + dy).coerceIn(anchor.top + minSide, height) }
                DragKind.W -> { nl = (anchor.left + dx).coerceIn(0, anchor.right - minSide) }
                DragKind.E -> { nr = (anchor.right + dx).coerceIn(anchor.left + minSide, width) }
                DragKind.NONE -> return
            }
            r.set(nl, nt, nr, nb); invalidate(); onRectChanged?.invoke(rect?.let { Rect(it) })
        }

        private fun makeRect(sx: Float, sy: Float, ex: Float, ey: Float): Rect {
            return Rect(
                minOf(sx, ex).toInt().coerceAtLeast(0),
                minOf(sy, ey).toInt().coerceAtLeast(0),
                maxOf(sx, ex).toInt().coerceAtMost(width),
                maxOf(sy, ey).toInt().coerceAtMost(height)
            )
        }

        private fun dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
        private fun sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    }
}
