package me.rerere.rikkahub.ui.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * RikkaHub 触感反馈引擎
 *
 * 使用 Android Vibrator API + VibrationEffect 实现类 MiHaptic 效果。
 * 无需 MiHaptic SDK，兼容所有 Android 12+ 设备。
 *
 * 提供 5 种预设波形：
 * - tick: 轻点（哒）
 * - heavyClick: 重点（嗒！）
 * - success: 成功组合（哒～哒～）
 * - error: 错误反馈（嗡嗡）
 * - notification: 通知（嗒嗒嗒）
 */
class RikkaHapticFeedback(private val context: Context) {

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** 是否可用 */
    val isAvailable: Boolean
        get() = vibrator?.hasVibrator() == true

    /** 是否支持振幅控制 */
    val hasAmplitudeControl: Boolean
        get() = vibrator?.hasAmplitudeControl() == true

    // ===== 预设波形 (时长ms, 振幅0-255) =====

    /** 轻触 — "哒" */
    private val PATTERN_TICK = longArrayOf(0, 8)
    private val AMP_TICK = intArrayOf(0, 80)

    /** 重点 — "嗒！" */
    private val PATTERN_HEAVY = longArrayOf(0, 15)
    private val AMP_HEAVY = intArrayOf(0, 200)

    /** 双击 — "哒哒" */
    private val PATTERN_DOUBLE_TICK = longArrayOf(0, 6, 30, 6)
    private val AMP_DOUBLE_TICK = intArrayOf(0, 70, 0, 70)

    /** 成功 — "哒～哒～"（递增） */
    private val PATTERN_SUCCESS = longArrayOf(0, 10, 40, 15, 60, 20)
    private val AMP_SUCCESS = intArrayOf(0, 100, 0, 150, 0, 200)

    /** 错误 — "嗡嗡～" */
    private val PATTERN_ERROR = longArrayOf(0, 30, 50, 50)
    private val AMP_ERROR = intArrayOf(0, 180, 0, 220)

    /** 通知 — "嗒嗒嗒" */
    private val PATTERN_NOTIFICATION = longArrayOf(0, 10, 25, 10, 25, 10)
    private val AMP_NOTIFICATION = intArrayOf(0, 120, 0, 120, 0, 120)

    /** 下拉刷新 — "嗖～哒" */
    private val PATTERN_REFRESH = longArrayOf(0, 5, 20, 12)
    private val AMP_REFRESH = intArrayOf(0, 50, 0, 180)

    // ===== 公开 API =====

    /** 轻触 */
    fun tick() = vibrate(PATTERN_TICK, AMP_TICK)

    /** 重点 */
    fun heavyClick() = vibrate(PATTERN_HEAVY, AMP_HEAVY)

    /** 双击 */
    fun doubleTick() = vibrate(PATTERN_DOUBLE_TICK, AMP_DOUBLE_TICK)

    /** 成功 */
    fun success() = vibrate(PATTERN_SUCCESS, AMP_SUCCESS)

    /** 错误 */
    fun error() = vibrate(PATTERN_ERROR, AMP_ERROR)

    /** 通知 */
    fun notification() = vibrate(PATTERN_NOTIFICATION, AMP_NOTIFICATION)

    /** 下拉刷新 */
    fun refresh() = vibrate(PATTERN_REFRESH, AMP_REFRESH)

    /** 开关切换 */
    fun toggle() = tick()

    /**
     * 自定义振动
     * @param timings 时长数组（偶数位=停顿，奇数位=振动）
     * @param amplitudes 振幅数组（0-255, 0=停）
     */
    fun vibrate(timings: LongArray, amplitudes: IntArray) {
        if (!isAvailable) return

        try {
            val effect = if (hasAmplitudeControl) {
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            } else {
                val totalMs = timings.filterIndexed { i, _ -> i % 2 == 1 }.sum()
                VibrationEffect.createOneShot(
                    totalMs.coerceAtLeast(1),
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            }
            vibrator?.vibrate(effect)
        } catch (e: Exception) {
            // 静默降级
        }
    }

    /** 单次振动（无振幅控制时用） */
    fun oneShot(durationMs: Long = 20) {
        try {
            val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator?.vibrate(effect)
        } catch (_: Exception) {
        }
    }
}

/**
 * Compose CompositionLocal — 在 Composable 中获取触感引擎
 */
val LocalRikkaHaptic = staticCompositionLocalOf<RikkaHapticFeedback> {
    error("RikkaHapticFeedback not provided. Wrap your composable with RikkaHapticWrapper.")
}

/**
 * 获取当前 RikkaHapticFeedback 实例的便捷方法
 */
@Composable
fun rememberRikkaHaptic(): RikkaHapticFeedback {
    val context = LocalContext.current
    return RikkaHapticFeedback(context)
}

/**
 * 在 Composable 树中提供 RikkaHapticFeedback
 */
@Composable
fun RikkaHapticWrapper(content: @Composable () -> Unit) {
    val haptic = rememberRikkaHaptic()
    CompositionLocalProvider(LocalRikkaHaptic provides haptic) {
        content()
    }
}
