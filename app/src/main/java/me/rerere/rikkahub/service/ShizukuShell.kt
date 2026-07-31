package me.rerere.rikkahub.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import android.util.Log

/**
 * Shizuku Shell 工具类。封装常用 shell 命令，通过 ShizukuManager 执行。
 *
 * 提供的方法：
 * - inputTap(x, y)         — 点击坐标
 * - inputSwipe(sx, sy, ex, ey, duration) — 滑动
 * - inputText(text)        — 输入文本（仅 ASCII，中文不支持）
 * - captureScreencap()     — 截图（返回 Bitmap）
 * - pressKey(keycode)      — 按键事件
 * - run(command)           — 通用 shell 执行
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"

    /**
     * 通过 Shizuku 执行点击。
     * 返回 true 表示命令成功发送（exit code = 0）。
     */
    suspend fun inputTap(x: Int, y: Int): Boolean {
        val result = ShizukuManager.runShell("input tap $x $y")
        return result.exitCode == 0
    }

    /**
     * 通过 Shizuku 执行滑动。
     * @param durationMs 滑动持续时间（毫秒）
     */
    suspend fun inputSwipe(
        sx: Int, sy: Int,
        ex: Int, ey: Int,
        durationMs: Long = 300L,
    ): Boolean {
        val result = ShizukuManager.runShell("input swipe $sx $sy $ex $ey $durationMs")
        return result.exitCode == 0
    }

    /**
     * 通过 Shizuku 输入文本。
     * 注意：input text 不支持中文，中文会变成乱码或空格。
     * 建议仅用于 ASCII 文本输入。
     */
    suspend fun inputText(text: String): Boolean {
        // 转义单引号等特殊字符
        val escaped = text
            .replace("'", "'\\\\''")
            .replace("\"", "\\\"")
        val result = ShizukuManager.runShell("input text '$escaped'")
        return result.exitCode == 0
    }

    /**
     * 通过 Shizuku 截图（screencap）。
     * 返回 Bitmap 或 null（失败时）。
     * 
     * 流程：screencap 输出 PNG → base64 编码（通过 sh -c 管道）→ 解码为 Bitmap。
     * 比 AccessibilityService 截图 API 更稳定，不受 ~1次/秒 限流。
     * 使用 base64 避免二进制数据通过文本管道时损坏。
     */
    suspend fun captureScreencap(): Bitmap? {
        // screencap -p 输出 PNG，用 base64 编码后传回，避免二进制损坏
        val result = ShizukuManager.runShell(
            command = "screencap -p 2>/dev/null | base64 -w0",
            timeoutMs = 10_000L,
        )
        if (result.exitCode != 0 || result.stdout.isBlank()) return null

        return try {
            val base64Data = result.stdout.trim()
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            Log.w(TAG, "captureScreencap decode failed: ${t.message}")
            null
        }
    }

    /**
     * 通过 Shizuku 发送按键事件。
     * @param keycode Android 键码（如 KeyEvent.KEYCODE_BACK = 4）
     */
    suspend fun pressKey(keycode: Int): Boolean {
        val result = ShizukuManager.runShell("input keyevent $keycode")
        return result.exitCode == 0
    }

    /**
     * 通用 shell 执行。直接运行任意命令。
     * @param command shell 命令字符串
     * @param timeoutMs 超时时间
     */
    suspend fun run(
        command: String,
        timeoutMs: Long = 5_000L,
    ): ShizukuManager.ProcessResult {
        return ShizukuManager.runShell(command, timeoutMs)
    }

    /**
     * Shizuku 是否可用（已安装 + 已运行 + 已授权）
     */
    val isAvailable: Boolean get() = ShizukuManager.isReady
}
