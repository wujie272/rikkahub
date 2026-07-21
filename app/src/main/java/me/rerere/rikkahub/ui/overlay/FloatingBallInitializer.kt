package me.rerere.rikkahub.ui.overlay

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 一键初始化所有浮窗组件。
 * 在 [RikkaHubApp.onCreate] 中调用即可。
 *
 * 用法：
 *   FloatingBallInitializer.init(this, appScope)
 */
object FloatingBallInitializer {
    private const val TAG = "FloatingBallInitializer"

    @Volatile
    private var overlayManager: OverlayManager? = null

    @Volatile
    private var initialized = false

    /**
     * 初始化浮窗系统。
     * 延迟 1 秒后自动显示触发球（给 App 足够时间完成启动）。
     *
     * @param context Application context
     * @param scope CoroutineScope for async operations
     * @param autoShowBall 是否自动显示触发球（默认 true）
     * @param onBallTap 单击触发球的回调。默认打开对话浮窗
     * @param onBallLongPress 长按触发球的回调。默认无操作
     */
    fun init(
        context: Context,
        scope: CoroutineScope,
        autoShowBall: Boolean = true,
        onBallTap: (() -> Unit)? = null,
        onBallLongPress: (() -> Unit)? = null,
    ) {
        if (initialized) return
        initialized = true

        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted, skipping overlay init")
            return
        }

        val manager = OverlayManager(context)
        overlayManager = manager

        // 配置触发球行为
        manager.triggerBall.apply {
            this.onTap = onBallTap ?: {
                // 默认：切换对话浮窗显示/隐藏
                if (manager.chatWindow.isShowing()) {
                    manager.chatWindow.hide()
                } else {
                    manager.chatWindow.show(context)
                }
            }
            this.onLongPress = onBallLongPress ?: {
                // 默认：显示对话浮窗快捷入口
                manager.chatWindow.show(context)
            }
        }

        // 延迟显示，确保 App 完全启动
        if (autoShowBall) {
            scope.launch {
                delay(1500)
                if (Settings.canDrawOverlays(context)) {
                    manager.showBall()
                    Log.i(TAG, "Floating ball shown")
                }
            }
        }

        Log.i(TAG, "FloatingBallInitializer initialized")
    }

    /**
     * 获取 OverlayManager 实例
     */
    fun getManager(): OverlayManager? = overlayManager

    /**
     * 显示/隐藏触发球
     */
    fun showBall() {
        overlayManager?.showBall()
    }

    fun hideBall() {
        overlayManager?.hideBall()
    }

    /**
     * 屏幕旋转时调用
     */
    fun onConfigurationChanged() {
        overlayManager?.onConfigurationChanged()
    }

    /**
     * 释放资源
     */
    fun destroy() {
        overlayManager?.destroy()
        overlayManager = null
        initialized = false
    }
}
