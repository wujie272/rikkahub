package me.rerere.rikkahub.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import kotlin.math.sqrt

/**
 * 液态吸附悬浮球容器。
 * 自己在 dispatchDraw 前画"球本体 + 液态吸附尾巴"的 path 作为背景，
 * 子 view（图标 ImageView）始终居中、不变形。
 *
 * 三态：
 * - NONE → 画完整圆 + 描边（默认浮动态）
 * - LEFT → 球 + 两条凹贝塞尔连接到容器左边（屏幕物理左边）
 * - RIGHT → 球 + 镜像连接到容器右边
 */
internal class LiquidFloatingContainer(context: Context) : FrameLayout(context) {
    enum class DockSide { NONE, LEFT, RIGHT }

    var side: DockSide = DockSide.NONE
        set(value) {
            if (field != value) {
                field = value
                updateChildTranslation()
                invalidate()
            }
        }

    var ballRadius: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                updateChildTranslation()
                invalidate()
            }
        }

    var fillColor: Int = 0xFF1E88E5.toInt()
        set(value) {
            if (field != value) { field = value; fillPaint.color = value; invalidate() }
        }

    var strokeColor: Int = 0xFFFFFFFF.toInt()
        set(value) {
            if (field != value) { field = value; strokePaint.color = value; invalidate() }
        }

    var strokeWidthPx: Float = 0f
        set(value) {
            if (field != value) { field = value; strokePaint.strokeWidth = value; invalidate() }
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = strokeColor
    }
    private val fillPath = Path()
    private val strokePath = Path()
    private val ballRect = RectF()

    private val tailReach = 0.3f
    private val tailDepth = 0.4f

    init {
        setWillNotDraw(false)
    }

    private fun visualCx(): Float {
        val r = ballRadius
        return when (side) {
            DockSide.LEFT -> r
            DockSide.RIGHT -> width - r
            DockSide.NONE -> width / 2f
        }
    }

    private fun updateChildTranslation() {
        if (width == 0 || ballRadius <= 0f) return
        val tx = visualCx() - width / 2f
        for (i in 0 until childCount) {
            getChildAt(i).translationX = tx
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateChildTranslation()
    }

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        updateChildTranslation()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (ballRadius > 0f) {
            val cx = visualCx()
            val cy = height / 2f
            when (side) {
                DockSide.NONE -> drawCircle(canvas, cx, cy)
                DockSide.LEFT -> drawLiquid(canvas, cx, cy, edgeX = 0f, leftSide = true)
                DockSide.RIGHT -> drawLiquid(canvas, cx, cy, edgeX = width.toFloat(), leftSide = false)
            }
        }
        super.dispatchDraw(canvas)
    }

    private fun drawCircle(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, ballRadius, fillPaint)
        if (strokeWidthPx > 0f) {
            canvas.drawCircle(cx, cy, ballRadius - strokeWidthPx / 2f, strokePaint)
        }
    }

    private fun drawLiquid(canvas: Canvas, cx: Float, cy: Float, edgeX: Float, leftSide: Boolean) {
        val r = ballRadius
        val sign = if (leftSide) -1f else 1f
        val cosT = 0.5f
        val sinT = 0.866f
        val qx = cx + sign * cosT * r
        val q1y = cy - sinT * r
        val q2y = cy + sinT * r
        val reach = tailReach * r
        val depth = tailDepth * r
        val p1y = q1y - reach
        val p2y = q2y + reach

        // 上半弧控制点
        val midUpX = (qx + edgeX) * 0.5f
        val midUpY = (q1y + p1y) * 0.5f
        val dxUp = edgeX - qx
        val dyUp = p2y - q1y
        val dLenUp = sqrt(dxUp * dxUp + dyUp * dyUp)
        val nUpX = -dyUp / dLenUp
        val nUpY = dxUp / dLenUp
        val signNUp = if (nUpX * (cx - midUpX) + nUpY * (cy - midUpY) > 0f) 1f else -1f
        val ctrlUpX = midUpX + signNUp * nUpX * depth
        val ctrlUpY = midUpY + signNUp * nUpY * depth

        // 下半弧控制点
        val midDnX = (qx + edgeX) * 0.5f
        val midDnY = (q2y + p2y) * 0.5f
        val dxDn = edgeX - qx
        val dyDn = p2y - q2y
        val dLenDn = sqrt(dxDn * dxDn + dyDn * dyDn)
        val nDnX = -dyDn / dLenDn
        val nDnY = dxDn / dLenDn
        val signNDn = if (nDnX * (cx - midDnX) + nDnY * (cy - midDnY) > 0f) 1f else -1f
        val ctrlDnX = midDnX + signNDn * nDnX * depth
        val ctrlDnY = midDnY + signNDn * nDnY * depth

        // 球近侧弧
        val nearStart: Float
        val nearSweep: Float
        if (leftSide) { nearStart = 240f; nearSweep = -120f }
        else { nearStart = 300f; nearSweep = 120f }
        ballRect.set(cx - r, cy - r, cx + r, cy + r)

        // fill path
        fillPath.reset()
        fillPath.moveTo(edgeX, p1y)
        fillPath.quadTo(ctrlUpX, ctrlUpY, qx, q1y)
        fillPath.arcTo(ballRect, nearStart, nearSweep, false)
        fillPath.quadTo(ctrlDnX, ctrlDnY, edgeX, p2y)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // 球本体
        canvas.drawCircle(cx, cy, r, fillPaint)
        if (strokeWidthPx > 0f) {
            canvas.drawCircle(cx, cy, r - strokeWidthPx / 2f, strokePaint)
            strokePath.reset()
            strokePath.moveTo(edgeX, p1y)
            strokePath.quadTo(ctrlUpX, ctrlUpY, qx, q1y)
            strokePath.moveTo(qx, q2y)
            strokePath.quadTo(ctrlDnX, ctrlDnY, edgeX, p2y)
            canvas.drawPath(strokePath, strokePaint)
        }
    }
}
