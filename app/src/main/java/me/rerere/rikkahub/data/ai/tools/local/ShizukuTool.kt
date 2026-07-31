package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.ShizukuManager

/**
 * 通过 Shizuku 以 shell 权限执行命令的工具。
 * 比 Termux 更轻量，不需要打开 Termux 窗口。
 * 适合执行 adb 级别的系统命令：input tap、pm grant、settings put 等。
 */
fun shizukuRunCommandTool(): Tool = Tool(
    name = "shizuku_run_command",
    description = """
        通过 Shizuku 以 shell 权限执行命令（比 Termux 更快，不需要打开 Termux 窗口）。
        适合执行：input tap/swipe/text、screencap、pm grant、settings put、am start 等。
        返回 {exit_code, stdout, stderr}。
        注意：Shizuku 需要提前安装并授权，否则返回错误。
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "要执行的 shell 命令（如 'input tap 500 1000' 或 'pm grant ...'）")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "超时时间（毫秒，默认 5000）")
                })
            },
            required = listOf("command")
        )
    },
    execute = { input ->
        val command = input.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        if (command.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "command is required") }.toString()
                )
            )
        }
        if (!ShizukuManager.isReady) {
            val state = ShizukuManager.snapshot.value.state
            val recovery = when (state) {
                ShizukuManager.State.NOT_INSTALLED ->
                    "请安装 Shizuku 或 AXManager：https://github.com/RikkaApps/Shizuku/releases"
                ShizukuManager.State.NOT_RUNNING ->
                    "Shizuku 已安装但服务未启动，请打开 Shizuku 应用并启动服务"
                ShizukuManager.State.NEED_PERMISSION ->
                    "Shizuku 服务运行中但未授权 RikkaHub，请在 Shizuku 中授权"
                else ->
                    "Shizuku 未就绪（状态：$state）"
            }
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "shizuku_not_ready")
                        put("state", state.name)
                        put("recovery", recovery)
                    }.toString()
                )
            )
        }

        val timeoutMs = input.jsonObject["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5_000L
        val result = ShizukuManager.runShell(command, timeoutMs.coerceIn(1_000L, 60_000L))

        val payload = buildJsonObject {
            put("exit_code", result.exitCode)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("success", result.exitCode == 0)
            if (result.exitCode != 0) {
                put("error", "command exited with code ${result.exitCode}")
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
