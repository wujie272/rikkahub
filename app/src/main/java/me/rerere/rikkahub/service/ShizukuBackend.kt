package me.rerere.rikkahub.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku 特权级执行后端。同时支持官方 Shizuku 和 AXManager。
 *
 * 通过反射调用 Shizuku.newProcess 来执行 shell 命令。
 * Shizuku 权限后端，通过 ContentProvider 执行特权命令。
 */
class ShizukuBackend(private val appContext: Context) {

    private var onStateChanged: (() -> Unit)? = null
    private var listenersRegistered = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "binder received")
        onStateChanged?.invoke()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "binder died")
        onStateChanged?.invoke()
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            Log.i(TAG, "permission result code=$requestCode grant=${grantResult == PackageManager.PERMISSION_GRANTED}")
            onStateChanged?.invoke()
        }

    /** 是否安装了任意 Shizuku 兼容的管理器 */
    fun isInstalled(): Boolean = installedManagerPackage() != null

    /** 已安装的管理器包名，优先返回官方 Shizuku */
    fun installedManagerPackage(): String? = SHIZUKU_COMPATIBLE_PACKAGES.firstOrNull { pkg ->
        runCatching { appContext.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    fun isBinderAlive(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isPermissionGranted(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun snapshot(): ShizukuManager.Snapshot {
        if (!isInstalled()) return ShizukuManager.Snapshot(ShizukuManager.State.NOT_INSTALLED)
        if (!isBinderAlive()) return ShizukuManager.Snapshot(ShizukuManager.State.NOT_RUNNING)
        return if (isPermissionGranted()) {
            ShizukuManager.Snapshot(
                state = ShizukuManager.State.READY,
                version = versionOrUnknown(),
                uid = uidOrUnknown(),
            )
        } else {
            ShizukuManager.Snapshot(ShizukuManager.State.NEED_PERMISSION)
        }
    }

    fun registerListeners(onStateChanged: () -> Unit) {
        this.onStateChanged = onStateChanged
        if (listenersRegistered) return
        listenersRegistered = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }.onFailure { Log.w(TAG, "registerListeners failed: ${it.message}") }
    }

    fun openManagerApp(context: Context) {
        val pkg = installedManagerPackage()
        if (pkg != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .onFailure { Log.w(TAG, "open $pkg failed: ${it.message}") }
                return
            }
        }
        openInstallPage(context, SHIZUKU_GITHUB_URL)
    }

    fun openInstallPage(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "openInstallPage($url) failed: ${it.message}") }
    }

    fun requestPermission() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            Log.w(TAG, "permission permanently denied — user must enable in the manager app")
            return
        }
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { Log.w(TAG, "requestPermission failed: ${it.message}") }
    }

    private fun versionOrUnknown(): Int = runCatching {
        if (Shizuku.pingBinder()) Shizuku.getVersion() else -1
    }.getOrDefault(-1)

    private fun uidOrUnknown(): Int = runCatching {
        if (Shizuku.pingBinder()) Shizuku.getUid() else -1
    }.getOrDefault(-1)

    /**
     * 通过 Shizuku 执行 shell 命令。
     * 使用反射调用 Shizuku.newProcess（隐藏 API）。
     */
    fun runProcess(
        argv: Array<String>,
        env: Array<String>?,
        cwd: String?,
        timeoutMs: Long,
    ): ShizukuManager.ProcessResult {
        if (!isPermissionGranted()) {
            return ShizukuManager.ProcessResult(
                exitCode = 126, stdout = "", stderr = "shizuku not ready",
            )
        }
        val procAny = runCatching {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            m.isAccessible = true
            m.invoke(null, argv, env, cwd)
        }.getOrElse {
            Log.w(TAG, "newProcess reflection failed: ${it.message}")
            return ShizukuManager.ProcessResult(
                exitCode = 1, stdout = "", stderr = "shizuku.newProcess unavailable: ${it.message}",
            )
        } ?: return ShizukuManager.ProcessResult(
            exitCode = 1, stdout = "", stderr = "shizuku.newProcess returned null",
        )

        val proc = procAny as? Process ?: return ShizukuManager.ProcessResult(
            exitCode = 1, stdout = "", stderr = "unexpected type: ${procAny.javaClass.name}",
        )

        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = Thread {
            runCatching {
                proc.inputStream?.use { s ->
                    s.bufferedReader().forEachLine { l -> synchronized(out) { out.append(l).append('\n') } }
                }
            }
        }
        val errThread = Thread {
            runCatching {
                proc.errorStream?.use { s ->
                    s.bufferedReader().forEachLine { l -> synchronized(err) { err.append(l).append('\n') } }
                }
            }
        }
        outThread.isDaemon = true; errThread.isDaemon = true
        outThread.start(); errThread.start()

        val exited: Boolean = try {
            proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: IllegalArgumentException) {
            // Shizuku v13 RemoteProcess.waitFor 有 bug，fallback 到轮询
            if (polledFallbackLogged.compareAndSet(false, true)) {
                Log.i(TAG, "Using polling fallback for Shizuku RemoteProcess.waitFor")
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            var done = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    proc.exitValue()
                    done = true
                    break
                } catch (e: RuntimeException) {
                    if (e is IllegalThreadStateException || e is IllegalArgumentException) {
                        Thread.sleep(50)
                    } else {
                        throw e
                    }
                }
            }
            done
        } catch (t: Throwable) {
            Log.w(TAG, "waitFor failed: ${t.message}")
            runCatching { proc.destroy() }
            runCatching { outThread.join(2000) }
            runCatching { errThread.join(2000) }
            return ShizukuManager.ProcessResult(
                exitCode = 1,
                stdout = out.toString().trimEnd('\n'),
                stderr = (err.toString().trimEnd('\n') + "\nwaitFor failed: ${t.message}").trim(),
            )
        }

        if (!exited) {
            runCatching { proc.destroy() }
            runCatching { outThread.join(2000) }
            runCatching { errThread.join(2000) }
            return ShizukuManager.ProcessResult(
                exitCode = 124, stdout = out.toString().trimEnd('\n'), stderr = err.toString().trimEnd('\n'),
            )
        }

        runCatching { outThread.join(2000) }
        runCatching { errThread.join(2000) }
        val rc = runCatching { proc.exitValue() }.getOrDefault(-1)
        return ShizukuManager.ProcessResult(rc, out.toString().trimEnd('\n'), err.toString().trimEnd('\n'))
    }

    companion object {
        private const val TAG = "ShizukuBackend"

        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val AXMANAGER_PACKAGE = "frb.axeron.manager"
        val SHIZUKU_COMPATIBLE_PACKAGES = listOf(SHIZUKU_PACKAGE, AXMANAGER_PACKAGE)

        const val SHIZUKU_GITHUB_URL = "https://github.com/RikkaApps/Shizuku/releases"

        const val AXMANAGER_GITHUB_URL = "https://github.com/fahrez256/AXManager/releases"
        const val PERMISSION_REQUEST_CODE = 0xC1A4D

        private val polledFallbackLogged = AtomicBoolean(false)
    }
}
