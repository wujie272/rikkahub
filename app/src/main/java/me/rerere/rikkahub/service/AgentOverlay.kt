package me.rerere.rikkahub.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.util.DisplayMetrics
import androidx.core.view.WindowInsetsCompat
import android.view.View

/**
 * Lightweight top-of-screen pill that shows while a generation turn is active so the
 * user always knows when the agent is driving the UI. Uses TYPE_APPLICATION_OVERLAY
 * with FLAG_NOT_TOUCHABLE so it never blocks user gestures. No-ops silently if
 * SYSTEM_ALERT_WINDOW has not been granted — overlay is purely informational.
 */
object AgentOverlay {
    private const val TAG = "AgentOverlay"

    @Volatile private var view: TextView? = null
    @Volatile private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context, text: String = "正在执行自动化操作…") {
        val app = context.applicationContext
        appContext = app
        if (!canShow(app)) {
            Log.d(TAG, "show: SYSTEM_ALERT_WINDOW not granted, no-op")
            return
        }
        mainHandler.post { showInternal(app, text) }
    }

    /**
     * Update the overlay text on the fly. No-op if the overlay isn't currently showing.
     */
    fun updateText(text: String) {
        mainHandler.post {
            view?.text = text
        }
    }

    fun hide(context: Context) {
        val app = context.applicationContext
        appContext = null
        mainHandler.post { hideInternal(app) }
    }

    @SuppressLint("RtlHardcoded")
    private fun showInternal(app: Context, text: String) {
        val existing = view
        if (existing != null) {
            existing.text = text
            return
        }
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val tv = TextView(app).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val pad = (12 * app.resources.displayMetrics.density).toInt()
            val padV = (6 * app.resources.displayMetrics.density).toInt()
            setPadding(pad, padV, pad, padV)
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(0xCC202020.toInt())
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // 初始 y 设为 0，后续通过 insets 监听器拿到真实状态栏高度再校正
        // 不再用 getIdentifier 猜 status_bar_height，那玩意儿在双行状态栏上会翻车

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            // Auto-detect status bar height so the pill sits right below it on any device.
            x = (16 * app.resources.displayMetrics.density).toInt()
            y = 0 // 初始值，等 insets 回调校正
        }
        try {
            wm.addView(tv, params)
            // 使用 WindowInsets 监听器精确获取状态栏高度，兼容双行状态栏/挖孔屏/刘海屏
            tv.setOnApplyWindowInsetsListener { v, insets ->
                val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, v)
                val statusBarInsets = compat.getInsets(WindowInsetsCompat.Type.statusBars())
                val lp = v.layoutParams as WindowManager.LayoutParams
                lp.y = statusBarInsets.top + (4 * app.resources.displayMetrics.density).toInt()
                wm.updateViewLayout(v, lp)
                insets
            }
            // 触发 insets 分发，让 listener 尽快回调
            tv.requestApplyInsets()
            view = tv
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed", t)
        }
    }

    private fun hideInternal(app: Context) {
        val v = view ?: return
        view = null
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        try {
            wm.removeViewImmediate(v)
        } catch (t: Throwable) {
            Log.w(TAG, "removeView failed", t)
        }
    }
}
