package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shizuku 生命周期管理器。单例，统一管理 Shizuku 的状态和权限。
 *
 * 状态机：
 *   NOT_INSTALLED  → 设备上没有安装 Shizuku / AXManager
 *   NOT_RUNNING    → 已安装但服务未启动
 *   NEED_PERMISSION → 服务运行中但未授权
 *   READY          → 一切就绪，可执行命令
 */
object ShizukuManager {
    private const val TAG = "ShizukuManager"

    enum class State {
        NOT_INSTALLED,
        NOT_RUNNING,
        NEED_PERMISSION,
        READY,
    }

    data class Snapshot(
        val state: State,
        val version: Int = -1,
        val uid: Int = -1,
    )

    data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val combined: String get() = if (stderr.isEmpty()) stdout else "$stdout\n$stderr".trimEnd()
    }

    @Volatile private var initialized = false
    @Volatile private var appContext: Context? = null
    private var backend: ShizukuBackend? = null

    private val _snapshot = MutableStateFlow(Snapshot(State.NOT_INSTALLED))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    val isReady: Boolean get() = _snapshot.value.state == State.READY
    val isInstalled: Boolean get() = backend?.isInstalled() == true
    val installedManagerPackage: String? get() = backend?.installedManagerPackage()

    fun init(app: Application) {
        if (initialized) return
        initialized = true
        appContext = app.applicationContext
        backend = ShizukuBackend(app.applicationContext).also { b ->
            runCatching { b.registerListeners { recompute("listener") } }
                .onFailure { Log.w(TAG, "registerListeners failed: ${it.message}") }
        }
        recompute("init")
    }

    fun refresh() = recompute("manual-refresh")

    fun requestPermission(): Boolean {
        val b = backend ?: return false
        if (!b.isBinderAlive()) return false
        b.requestPermission()
        return true
    }

    /** 打开已安装的管理器应用 */
    fun openManagerApp(context: Context) {
        backend?.openManagerApp(context)
    }

    /** 打开 Shizuku 安装页面 */
    fun openInstallPage(context: Context) {
        backend?.openInstallPage(context, ShizukuBackend.SHIZUKU_GITHUB_URL)
    }

    /** 打开指定 URL 的安装页面 */
    fun openInstallPage(context: Context, url: String) {
        backend?.openInstallPage(context, url)
    }

    /**
     * 通过 Shizuku 执行 shell 命令。
     * @param command 要执行的命令
     * @param timeoutMs 超时时间（毫秒），默认 5 秒
     */
    fun runShell(
        command: String,
        timeoutMs: Long = 5_000L,
    ): ProcessResult {
        if (!isReady) {
            return ProcessResult(
                exitCode = 126,
                stdout = "",
                stderr = "shizuku not ready (state=${_snapshot.value.state})",
            )
        }
        // 用 sh -c 包装命令，支持管道/重定向
        return backend!!.runProcess(
            argv = arrayOf("sh", "-c", command),
            env = null,
            cwd = null,
            timeoutMs = timeoutMs,
        )
    }

    private fun recompute(reason: String) {
        val b = backend ?: run {
            _snapshot.value = Snapshot(State.NOT_INSTALLED)
            return
        }
        val snap = b.snapshot()
        val prev = _snapshot.value
        _snapshot.value = snap
        if (snap.state != prev.state || snap.version != prev.version || snap.uid != prev.uid) {
            Log.i(TAG, "state=${snap.state} ver=${snap.version} uid=${snap.uid} manager=${b.installedManagerPackage() ?: "none"} ($reason)")
        }
    }
}
