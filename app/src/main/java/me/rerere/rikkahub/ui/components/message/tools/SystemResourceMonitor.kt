package me.rerere.rikkahub.ui.components.message.tools

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

/**
 * 轻量级 CPU/内存采样器，用于 Shell 工具实时 HUD。
 *
 * 系统资源监控组件，实时显示 CPU/MEM 使用率。
 *
 * - **CPU**: 本进程的 CPU 使用率，top 风格 IRIX 百分比。
 *   100% = 一个核心满载，8 核设备满载可达 800%。
 *   从 `/proc/self/stat` 读取 utime + stime 计算 delta。
 * - **内存**: Debug.getMemoryInfo().totalPss 为已用，
 *   ActivityManager.MemoryInfo.totalMem 为总量。
 */
class SystemResourceMonitor {
    var cpuUsage: Float = 0f
        private set
    var memUsedBytes: Long = 0L
        private set
    var memTotalBytes: Long = 0L
        private set

    private var prevCpuTicks: Long? = null
    private var prevSampleNanos: Long? = null
    private val recentDeltas = ArrayDeque<Pair<Long, Long>>()
    private val windowSize = 5

    private val clockTicksPerSec: Long = runCatching {
        Os.sysconf(OsConstants._SC_CLK_TCK)
    }.getOrNull()?.takeIf { it > 0 } ?: 100L

    fun sampleOnce(context: Context) {
        sampleCpu()
        sampleMemory(context)
    }

    fun reset() {
        prevCpuTicks = null
        prevSampleNanos = null
        recentDeltas.clear()
        cpuUsage = 0f
    }

    private fun sampleCpu() {
        val ticks = readSelfCpuTicks() ?: return
        val nowNanos = System.nanoTime()
        val prevTicks = prevCpuTicks
        val prevNanos = prevSampleNanos
        if (prevTicks != null && prevNanos != null) {
            val tickDelta = ticks - prevTicks
            val wallNanos = nowNanos - prevNanos
            if (tickDelta >= 0 && wallNanos > 0) {
                recentDeltas.addLast(tickDelta to wallNanos)
                while (recentDeltas.size > windowSize) recentDeltas.removeFirst()

                var sumTicks = 0L
                var sumNanos = 0L
                for ((t, n) in recentDeltas) { sumTicks += t; sumNanos += n }
                val sumWallSec = sumNanos / 1_000_000_000.0
                if (sumWallSec > 0) {
                    val raw = sumTicks.toDouble() /
                        (clockTicksPerSec.toDouble() * sumWallSec)
                    cpuUsage = (raw * 100.0).toFloat()
                }
            }
        }
        prevCpuTicks = ticks
        prevSampleNanos = nowNanos
    }

    private fun readSelfCpuTicks(): Long? = try {
        val raw = File("/proc/self/stat").readText()
        val rparen = raw.lastIndexOf(')')
        if (rparen < 0 || rparen + 2 >= raw.length) {
            null
        } else {
            val tail = raw.substring(rparen + 2)
                .split(' ')
                .filter { it.isNotEmpty() }
            val utime = tail[11].toLong()
            val stime = tail[12].toLong()
            utime + stime
        }
    } catch (_: Throwable) { null }

    private fun sampleMemory(context: Context) {
        try {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            memUsedBytes = mi.totalPss.toLong() * 1024L
        } catch (_: Throwable) { }
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val sysMem = ActivityManager.MemoryInfo()
                am.getMemoryInfo(sysMem)
                memTotalBytes = sysMem.totalMem
            }
        } catch (_: Throwable) { }
    }

    fun formattedCpu(): String =
        String.format("CPU %.0f%%", cpuUsage.coerceAtLeast(0f))

    fun formattedMem(): String {
        val usedGB = memUsedBytes / 1_073_741_824.0
        val totalGB = memTotalBytes / 1_073_741_824.0
        return String.format("MEM %.1f/%.1f GB", usedGB, totalGB)
    }
}

/**
 * Compose 入口。当 [active] 为 true 时每 2s 采样一次，触发重组。
 * 停止时重置基线。
 */
@Composable
fun rememberSystemResourceMonitor(active: Boolean): SystemResourceMonitor {
    val context = LocalContext.current
    val monitor = remember { SystemResourceMonitor() }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(active) {
        if (!active) {
            monitor.reset()
            return@LaunchedEffect
        }
        monitor.sampleOnce(context)
        tick++
        while (isActive) {
            delay(2000)
            monitor.sampleOnce(context)
            tick++
        }
    }
    @Suppress("UNUSED_VARIABLE") val t = tick
    return monitor
}
