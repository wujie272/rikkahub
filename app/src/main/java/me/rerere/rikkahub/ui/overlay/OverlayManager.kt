package me.rerere.rikkahub.ui.overlay
import android.graphics.Rect

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 统一管理所有浮窗组件的生命周期和配置。
 */
class OverlayManager(private val context: Context) {
    companion object {
        private const val TAG = "OverlayManager"
        private const val STORE_NAME = "overlay_prefs"

        // 球位置
        private val KEY_BALL_X = intPreferencesKey("ball_x")
        private val KEY_BALL_Y = intPreferencesKey("ball_y")
        // 球配置
        private val KEY_BALL_SIZE = intPreferencesKey("ball_size")
        private val KEY_BALL_SNAP = booleanPreferencesKey("ball_snap")
        private val KEY_BALL_AUTO_DOCK = booleanPreferencesKey("ball_auto_dock")
        private val KEY_BALL_DOCK_INSET = intPreferencesKey("ball_dock_inset")
        // 浮窗位置
        private val KEY_WINDOW_X = intPreferencesKey("window_x")
        private val KEY_WINDOW_Y = intPreferencesKey("window_y")
        private val KEY_WINDOW_W = intPreferencesKey("window_w")
        private val KEY_WINDOW_H = intPreferencesKey("window_h")
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dataStore by lazy {
        context.applicationContext.overlayDataStore
    }

    private val Context.overlayDataStore by preferencesDataStore(name = STORE_NAME)

    /** 悬浮触发球 */
    val triggerBall: FloatingTriggerBall by lazy {
        FloatingTriggerBall(context.applicationContext).apply {
            ioScope = this@OverlayManager.ioScope
            onPositionChanged = { x, y -> saveBallPosition(x, y) }
        }
    }

    /** 对话浮窗 */
    val chatWindow: FloatingChatWindow by lazy {
        FloatingChatWindow
    }

    // ==================== 配置 Flow ====================

    data class BallConfig(
        val sizeDp: Int = 48,
        val snapToEdge: Boolean = true,
        val autoDock: Boolean = false,
        val dockInsetPx: Int = 0
    )

    val ballConfigFlow: Flow<BallConfig> = dataStore.data.map { prefs ->
        BallConfig(
            sizeDp = prefs[KEY_BALL_SIZE] ?: 48,
            snapToEdge = prefs[KEY_BALL_SNAP] ?: true,
            autoDock = prefs[KEY_BALL_AUTO_DOCK] ?: false,
            dockInsetPx = prefs[KEY_BALL_DOCK_INSET] ?: 0,
        )
    }

    // ==================== 配置读写 ====================

    /** 读取当前球配置 */
    suspend fun loadBallConfig(): BallConfig = ballConfigFlow.first()

    /** 保存并应用球配置 */
    fun saveAndApplyBallConfig(config: BallConfig) {
        ioScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_BALL_SIZE] = config.sizeDp
                prefs[KEY_BALL_SNAP] = config.snapToEdge
                prefs[KEY_BALL_AUTO_DOCK] = config.autoDock
                prefs[KEY_BALL_DOCK_INSET] = config.dockInsetPx
            }
            // 同步到触发球实例
            triggerBall.sizeDp = config.sizeDp
            triggerBall.snapToEdge = config.snapToEdge
            triggerBall.autoDock = config.autoDock
            triggerBall.dockInsetPx = config.dockInsetPx
            // 触发球已显示时动态调整大小（View 操作必须切回主线程）
            if (triggerBall.isShown()) {
                withContext(Dispatchers.Main) {
                    triggerBall.applySize()
                }
            }
        }
    }

    /** 恢复球位置 */
    suspend fun restoreBallPosition() {
        val prefs = dataStore.data.first()
        val x = prefs[KEY_BALL_X] ?: return
        val y = prefs[KEY_BALL_Y] ?: return
        triggerBall.initialX = x
        triggerBall.initialY = y
    }

    /** 屏幕旋转 */
    fun onConfigurationChanged() {
        if (triggerBall.isShown()) triggerBall.onConfigurationChanged()
    }

    /** 显示/隐藏触发球 */
    fun showBall() {
        ioScope.launch {
            // 先加载配置再显示
            val config = loadBallConfig()
            withContext(Dispatchers.Main) {
                triggerBall.sizeDp = config.sizeDp
                triggerBall.snapToEdge = config.snapToEdge
                triggerBall.autoDock = config.autoDock
                triggerBall.dockInsetPx = config.dockInsetPx
                restoreBallPosition()
                triggerBall.show()
            }
        }
    }

    fun hideBall() {
        triggerBall.hide()
    }

    fun showChatWindow() {
        chatWindow.show(context)
    }

    fun hideChatWindow() {
        chatWindow.hide()
    }

    /** 释放所有资源 */
    fun showRegionPicker(
        onConfirm: (Rect) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val picker = RegionPickerOverlay(context)
        picker.show(
            onConfirm = { rect ->
                onConfirm(rect)
                picker.dismiss()
            },
            onCancel = {
                onCancel()
                picker.dismiss()
            }
        )
    }

    fun destroy() {
        triggerBall.hide()
        chatWindow.hide()
    }

    private fun saveBallPosition(x: Int, y: Int) {
        ioScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_BALL_X] = x
                prefs[KEY_BALL_Y] = y
            }
        }
    }
}
