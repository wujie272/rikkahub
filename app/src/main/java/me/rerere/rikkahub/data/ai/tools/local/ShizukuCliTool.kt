package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.ShizukuManager

/**
 * Shizuku 结构化 CLI 工具。
 * 提供分组子命令，比裸 shizuku_run_command 更稳定，返回结构化 JSON。
 *
 * 支持的分组（11组）：
 *   package     — 应用管理
 *   permission  — 权限管理
 *   activity    — Activity 管理
 *   settings    — 系统设置
 *   display     — 显示设置（分辨率/DPI）
 *   input       — 输入模拟
 *   notification— 通知管理
 *   file        — 文件操作
 *   device      — 设备信息（电池等）
 *   service     — Shizuku 状态
 *   exec        — 裸 shell 执行（fallback）
 */
fun shizukuCliTool(): Tool = Tool(
    name = "shizuku_cli",
    description = """
        Shizuku 结构化 CLI。通过 Shizuku 特权执行系统命令，返回结构化 JSON。
        优先使用此工具而非 shizuku_run_command，因为返回的是结构化数据。

        支持的分组（11组）：
        - package list [--system|--third-party|--disabled] [--filter X]
          package info <pkg>
          package enable|disable <pkg>
          package clear <pkg>
          package path <pkg>
        - permission list <pkg> [--granted|--denied]
          permission grant|revoke <pkg> <permission>
        - activity start [-c component] [-a action] [-p pkg]
          activity force-stop <pkg>
          activity top
        - settings get|set|delete|list <ns> <key> [value]
        - display list|set [--density DPI] [--width W] [--height H]|reset
        - input tap <x> <y>
          input swipe <x1> <y1> <x2> <y2>
          input key <keycode>
          input text <text>
        - notification list [--package pkg]|dismiss [--all|--pkg|--id]
        - file ls <path> [-l]|rm <path> [-r]
        - device info|battery
        - service status|ping
        - exec <command...>  (raw shell passthrough)

        参数：
        - group: 分组名（必填）
        - subcommand: 子命令（必填，exec 除外）
        - args: 附加参数对象，key=value 形式
        - flags: 标志数组，如 ["--system", "--granted"]
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("group", buildJsonObject {
                    put("type", "string")
                    put("description", "分组名：package / permission / activity / settings / display / input / notification / file / device / service / exec")
                })
                put("subcommand", buildJsonObject {
                    put("type", "string")
                    put("description", "子命令（exec 分组不需要此字段）")
                })
                put("args", buildJsonObject {
                    put("type", "object")
                    put("description", "命令参数，key=value 形式。如 {\"pkg\": \"com.example\", \"permission\": \"android.permission.CAMERA\"}")
                })
                put("flags", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "标志数组，如 [\"--system\", \"--granted\"]")
                })
            },
            required = listOf("group")
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val group = obj["group"]?.jsonPrimitive?.contentOrNull ?: return@Tool error("group is required")
        val subcommand = obj["subcommand"]?.jsonPrimitive?.contentOrNull ?: ""
        val argsObj = obj["args"]?.jsonObject ?: buildJsonObject { }
        val flags = obj["flags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

        if (!ShizukuManager.isReady) {
            val state = ShizukuManager.snapshot.value.state
            return@Tool error("shizuku not ready (state=$state)")
        }

        val result = try {
            when (group) {
                "package" -> handlePackage(subcommand, argsObj, flags)
                "permission" -> handlePermission(subcommand, argsObj, flags)
                "activity" -> handleActivity(subcommand, argsObj, flags)
                "settings" -> handleSettings(subcommand, argsObj, flags)
                "display" -> handleDisplay(subcommand, argsObj, flags)
                "input" -> handleInput(subcommand, argsObj, flags)
                "notification" -> handleNotification(subcommand, argsObj, flags)
                "file" -> handleFile(subcommand, argsObj, flags)
                "device" -> handleDevice(subcommand, argsObj, flags)
                "service" -> handleService(subcommand, argsObj, flags)
                "exec" -> handleExec(subcommand, argsObj, flags)
                else -> buildJsonObject {
                    put("error", "unknown group: $group")
                    put("valid_groups", "package, permission, activity, settings, display, input, notification, file, device, service, exec")
                }
            }
        } catch (t: Throwable) {
            buildJsonObject {
                put("error", t.message ?: "unknown error")
                put("exit_code", 1)
            }
        }

        listOf(UIMessagePart.Text(result.toString()))
    }
)

// ─── Package ───────────────────────────────────────────────────────────────

private fun handlePackage(sub: String, args: kotlinx.serialization.json.JsonObject, flags: List<String>): kotlinx.serialization.json.JsonObject = buildJsonObject {
    when (sub) {
        "list" -> {
            val cmd = mutableListOf("pm", "list", "packages")
            if ("--system" in flags) cmd.add("-s")
            if ("--third-party" in flags || "-3" in flags) cmd.add("-3")
            if ("--disabled" in flags) cmd.add("-d")
            val filter = args["filter"]?.jsonPrimitive?.contentOrNull
            if (filter != null) cmd.add(filter)
            val r = ShizukuManager.runShell(cmd.joinToString(" "), 10_000)
            put("exit_code", r.exitCode)
            put("packages", JsonArray(r.stdout.lineSequence()
                .map { it.removePrefix("package:") }
                .filter { it.isNotBlank() }
                .toList()
                .map { JsonPrimitive(it) }))
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        "info" -> {
            val pkg = args["pkg"]?.jsonPrimitive?.contentOrNull
            if (pkg.isNullOrBlank()) { put("error", "pkg is required"); return@buildJsonObject }
            val r = ShizukuManager.runShell("dumpsys package $pkg", 10_000)
            put("exit_code", r.exitCode)
            val info = buildJsonObject {
                put("packageName", pkg)
                Regex("""versionName=([^\s]+)""").find(r.stdout)?.groupValues?.get(1)?.let { put("versionName", it) }
                Regex("""versionCode=(\d+)""").find(r.stdout)?.groupValues?.get(1)?.let { put("versionCode", it) }
                Regex("""targetSdk=(\d+)""").find(r.stdout)?.groupValues?.get(1)?.let { put("targetSdk", it) }
                Regex("""firstInstallTime=([^\n]+)""").find(r.stdout)?.groupValues?.get(1)?.let { put("firstInstallTime", it) }
                Regex("""dataDir=([^\s]+)""").find(r.stdout)?.groupValues?.get(1)?.let { put("dataDir", it) }
                Regex("""codePath=([^\s]+)""").find(r.stdout)?.groupValues?.get(1)?.let { put("codePath", it) }
            }
            put("info", info)
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        "enable" -> {
            val pkg = args["pkg"]?.jsonPrimitive?.contentOrNull
            if (pkg.isNullOrBlank()) { put("error", "pkg is required"); return@buildJsonObject }
            val r = ShizukuManager.runShell("pm enable $pkg")
            put("exit_code", r.exitCode)
            put("enabled", pkg)
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        "disable" -> {
            val pkg = args["pkg"]?.jsonPrimitive?.contentOrNull
            if (pkg.isNullOrBlank()) { put("error", "pkg is required"); return@buildJsonObject }
            val r = ShizukuManager.runShell("pm disable-user $pkg")
            put("exit_code", r.exitCode)
            put("disabled", pkg)
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        "clear" -> {
            val pkg = args["pkg"]?.jsonPrimitive?.contentOrNull
            if (pkg.isNullOrBlank()) { put("error", "pkg is required"); return@buildJsonObject }
            val r = ShizukuManager.runShell("pm clear $pkg", 15_000)
            put("exit_code", r.exitCode)
            put("cleared", pkg)
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        "path" -> {
            val pkg = args["pkg"]?.jsonPrimitive?.contentOrNull
            if (pkg.isNullOrBlank()) { put("error", "pkg is required"); return@buildJsonObject }
            val r = ShizukuManager.runShell("pm path $pkg")
            put("exit_code", r.exitCode)
            put("paths", JsonArray(r.stdout.lineSequence()
                .map { it.removePrefix("package:") }
                .filter { it.isNotBlank() }
                .toList()
                .map { JsonPrimitive(it) }))
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        else -> put("error", "unknown package subcommand: $sub, valid: list, info, enable, disable, clear, path")
    }
}

// ─── Permission ────────────────────────────────────────────────────────────

private fun handlePermission(sub: String, args: kotlinx.serialization.json.JsonObject, flags: List<String>): kotlinx.serialization.json.JsonObject = buildJsonObject {
    when (sub) {
        "list" -> {
            val pkg = args["pkg"]?.jsonPrimitive?.contentOrNull
            if (pkg.isNullOrBlank()) { put("error", "pkg is required"); return@buildJsonObject }
            val r = ShizukuManager.runShell("dumpsys package $pkg", 10_000)
            put("exit_code", r.exitCode)
            if (r.exitCode != 0) { put("stderr", r.stderr); return@buildJsonObject }
            val perms = mutableListOf<kotlinx.serialization.json.JsonObject>()
            var inRuntime = false
            for (line in r.stdout.lineSequence()) {
                val t = line.trim()
                if (t.startsWith("runtime permissions:")) { inRuntime = true; continue }
                if (t.isEmpty()) { inRuntime = false; continue }
                if (!inRuntime) continue
                val colon = t.indexOf(':')
                val name = if (colon > 0) t.substring(0, colon).trim() else t
                val granted = t.contains("granted=true")
                val denied = flags.contains("--denied") && !granted
                val onlyGranted = flags.contains("--granted") && granted
                if ((flags.contains("--granted") && !granted) || (flags.contains("--denied") && granted)) continue
                if (flags.contains("--granted") || flags.contains("--denied") || (!flags.contains("--granted") && !flags.contains("--denied"))) {
                    perms.add(buildJsonObject {
                        put("name", name)
                        put("granted", granted)
                    })
                }
            }
            put("permissions", JsonArray(perms))
        }
        "grant" -> {
            val pkg = args["pkg"]?.jsonPrimitive?.contentOrNull
            val perm = args["permission"]?.jsonPrimitive?.contentOrNull
            if (pkg.isNullOrBlank() || perm.isNullOrBlank()) { put("error", "pkg and permission are required"); return@buildJsonObject }
            val r = ShizukuManager.runShell("pm grant $pkg $perm")
            put("exit_code", r.exitCode)
            put("granted", buildJsonObject { put("package", pkg); put("permission", perm) })
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        "revoke" -> {
            val pkg = args["pkg"]?.jsonPrimitive?.contentOrNull
            val perm = args["permission"]?.jsonPrimitive?.contentOrNull
            if (pkg.isNullOrBlank() || perm.isNullOrBlank()) { put("error", "pkg and permission are required"); return@buildJsonObject }
            val r = ShizukuManager.runShell("pm revoke $pkg $perm")
            put("exit_code", r.exitCode)
            put("revoked", buildJsonObject { put("package", pkg); put("permission", perm) })
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        else -> put("error", "unknown permission subcommand: $sub, valid: list, grant, revoke")
    }
}

// ─── Activity ──────────────────────────────────────────────────────────────

private fun handleActivity(sub: String, args: kotlinx.serialization.json.JsonObject, flags: List<String>): kotlinx.serialization.json.JsonObject = buildJsonObject {
    when (sub) {
        "start" -> {
            val cmd = mutableListOf("am", "start", "-W")
            args["component"]?.jsonPrimitive?.contentOrNull?.let { cmd.addAll(listOf("-n", it)) }
            args["action"]?.jsonPrimitive?.contentOrNull?.let { cmd.addAll(listOf("-a", it)) }
            args["pkg"]?.jsonPrimitive?.contentOrNull?.let { cmd.addAll(listOf("-p", it)) }
            args["data"]?.jsonPrimitive?.contentOrNull?.let { cmd.addAll(listOf("-d", it)) }
            val r = ShizukuManager.runShell(cmd.joinToString(" "), 15_000)
            put("exit_code", r.exitCode)
            put("output", r.stdout.trim())
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        "force-stop" -> {
            val pkg = args["pkg"]?.jsonPrimitive?.contentOrNull
            if (pkg.isNullOrBlank()) { put("error", "pkg is required"); return@buildJsonObject }
            val r = ShizukuManager.runShell("am force-stop $pkg")
            put("exit_code", r.exitCode)
            put("stopped", pkg)
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        "top" -> {
            val r = ShizukuManager.runShell("dumpsys activity activities", 6_000)
            put("exit_code", r.exitCode)
            if (r.exitCode == 0) {
                val m = Regex("""topResumedActivity=ActivityRecord\{[^ ]+ \d+ ([^/]+)/([^ ]+)""").find(r.stdout)
                if (m != null) {
                    put("packageName", m.groupValues[1])
                    put("activityName", m.groupValues[2])
                } else {
                    put("packageName", "unknown")
                    put("activityName", "unknown")
                }
            } else {
                put("stderr", r.stderr)
            }
        }
        else -> put("error", "unknown activity subcommand: $sub, valid: start, force-stop, top")
    }
}

// ─── Settings ──────────────────────────────────────────────────────────────

private fun handleSettings(sub: String, args: kotlinx.serialization.json.JsonObject, flags: List<String>): kotlinx.serialization.json.JsonObject = buildJsonObject {
    val ns = args["ns"]?.jsonPrimitive?.contentOrNull
    val key = args["key"]?.jsonPrimitive?.contentOrNull
    val value = args["value"]?.jsonPrimitive?.contentOrNull

    if (ns !in listOf("global", "secure", "system")) {
        put("error", "ns must be global, secure, or system"); return@buildJsonObject
    }

    when (sub) {
        "get" -> {
            if (key.isNullOrBlank()) { put("error", "key is required"); return@buildJsonObject }
            val r = ShizukuManager.runShell("settings get $ns $key")
            put("exit_code", r.exitCode)
            put("key", key)
            put("value", r.stdout.trim())
            if (r.exitCode != 0) put("stderr", r.stderr.ifBlank { "key not found" })
        }
        "set" -> {
            if (key.isNullOrBlank() || value == null) { put("error", "key and value are required"); return@buildJsonObject }
            val r = ShizukuManager.runShell("settings put $ns $key $value")
            put("exit_code", r.exitCode)
            put("key", key)
            put("value", value)
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        "delete" -> {
            if (key.isNullOrBlank()) { put("error", "key is required"); return@buildJsonObject }
            val r = ShizukuManager.runShell("settings delete $ns $key")
            put("exit_code", r.exitCode)
            put("deleted", key)
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        "list" -> {
            val filter = args["filter"]?.jsonPrimitive?.contentOrNull
            val r = ShizukuManager.runShell("settings list $ns", 8_000)
            put("exit_code", r.exitCode)
            if (r.exitCode == 0) {
                val entries = mutableMapOf<String, String>()
                for (line in r.stdout.lineSequence()) {
                    val eq = line.indexOf('=')
                    if (eq <= 0) continue
                    val k = line.substring(0, eq)
                    val v = line.substring(eq + 1)
                    if (filter == null || k.contains(filter)) {
                        entries[k] = v
                    }
                }
                put("entries", JsonObject(entries.entries.associate { it.key to kotlinx.serialization.json.JsonPrimitive(it.value) }))
            } else {
                put("stderr", r.stderr)
            }
        }
        else -> put("error", "unknown settings subcommand: $sub, valid: get, set, delete, list")
    }
}

// ─── Display ────────────────────────────────────────────────────────────────

private fun handleDisplay(sub: String, args: kotlinx.serialization.json.JsonObject, flags: List<String>): kotlinx.serialization.json.JsonObject = buildJsonObject {
    when (sub) {
        "list" -> {
            val r = ShizukuManager.runShell("wm size", 5_000)
            val rd = ShizukuManager.runShell("wm density", 5_000)
            put("exit_code", if (r.exitCode == 0 && rd.exitCode == 0) 0 else 1)
            val sizeMatch = Regex("""Physical size: (\d+x\d+)""").find(r.stdout)
            val densityMatch = Regex("""Physical density: (\d+)""").find(rd.stdout)
            put("width", sizeMatch?.groupValues?.get(1)?.substringBefore("x"))
            put("height", sizeMatch?.groupValues?.get(1)?.substringAfter("x"))
            put("density", densityMatch?.groupValues?.get(1))
        }
        "set" -> {
            val density = args["density"]?.jsonPrimitive?.contentOrNull
            val width = args["width"]?.jsonPrimitive?.contentOrNull
            val height = args["height"]?.jsonPrimitive?.contentOrNull
            var ok = true
            if (density != null) {
                val r = ShizukuManager.runShell("wm density $density")
                ok = ok && r.exitCode == 0
            }
            if (width != null && height != null) {
                val r = ShizukuManager.runShell("wm size ${width}x${height}")
                ok = ok && r.exitCode == 0
            }
            put("exit_code", if (ok) 0 else 1)
            put("ok", ok)
        }
        "reset" -> {
            val r1 = ShizukuManager.runShell("wm size reset")
            val r2 = ShizukuManager.runShell("wm density reset")
            put("exit_code", if (r1.exitCode == 0 && r2.exitCode == 0) 0 else 1)
            put("reset", true)
        }
        else -> put("error", "unknown display subcommand: $sub, valid: list, set, reset")
    }
}

// ─── Notification ──────────────────────────────────────────────────────────

private fun handleNotification(sub: String, args: kotlinx.serialization.json.JsonObject, flags: List<String>): kotlinx.serialization.json.JsonObject = buildJsonObject {
    when (sub) {
        "list" -> {
            val pkgFilter = args["package"]?.jsonPrimitive?.contentOrNull
            val r = ShizukuManager.runShell("dumpsys notification --noredact", 8_000)
            put("exit_code", r.exitCode)
            if (r.exitCode != 0) { put("stderr", r.stderr); return@buildJsonObject }
            val notifications = mutableListOf<kotlinx.serialization.json.JsonObject>()
            val regex = Regex("""NotificationRecord\(.*?pkg=([^\s]+).*?id=(\d+).*?tag=([^\s]+)?""")
            for (m in regex.findAll(r.stdout)) {
                val pkg = m.groupValues[1]
                if (pkgFilter != null && pkg != pkgFilter) continue
                notifications.add(buildJsonObject {
                    put("packageName", pkg)
                    put("id", m.groupValues[2].toIntOrNull() ?: -1)
                    put("tag", m.groupValues[3].ifBlank { null })
                })
            }
            put("notifications", JsonArray(notifications))
        }
        "dismiss" -> {
            if ("--all" in flags) {
                val r = ShizukuManager.runShell("cmd notification cancel_all")
                put("exit_code", r.exitCode)
                put("cancelled", "all")
            } else {
                val pkg = args["package"]?.jsonPrimitive?.contentOrNull
                val id = args["id"]?.jsonPrimitive?.contentOrNull
                val r = if (pkg != null) ShizukuManager.runShell("cmd notification cancel $pkg")
                else if (id != null) ShizukuManager.runShell("cmd notification cancel $id")
                else { put("error", "requires --all, --package, or --id"); return@buildJsonObject }
                put("exit_code", r.exitCode)
                put("cancelled", pkg ?: id)
            }
        }
        else -> put("error", "unknown notification subcommand: $sub, valid: list, dismiss")
    }
}

// ─── File ──────────────────────────────────────────────────────────────────

private fun handleFile(sub: String, args: kotlinx.serialization.json.JsonObject, flags: List<String>): kotlinx.serialization.json.JsonObject = buildJsonObject {
    when (sub) {
        "ls" -> {
            val path = args["path"]?.jsonPrimitive?.contentOrNull
            if (path.isNullOrBlank()) { put("error", "path is required"); return@buildJsonObject }
            val long = "-l" in flags
            val recursive = "-r" in flags || "--recursive" in flags
            val cmd = "ls ${if (long) "-la" else "-1"}${if (recursive) " -R" else ""} $path"
            val r = ShizukuManager.runShell(cmd, 8_000)
            put("exit_code", r.exitCode)
            put("output", r.stdout.trim())
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        "rm" -> {
            val path = args["path"]?.jsonPrimitive?.contentOrNull
            if (path.isNullOrBlank()) { put("error", "path is required"); return@buildJsonObject }
            val recursive = "-r" in flags || "--recursive" in flags
            val cmd = "rm ${if (recursive) "-rf" else "-f"} $path"
            val r = ShizukuManager.runShell(cmd, 8_000)
            put("exit_code", r.exitCode)
            put("removed", path)
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        else -> put("error", "unknown file subcommand: $sub, valid: ls, rm")
    }
}

// ─── Device ────────────────────────────────────────────────────────────────

private fun handleDevice(sub: String, args: kotlinx.serialization.json.JsonObject, flags: List<String>): kotlinx.serialization.json.JsonObject = buildJsonObject {
    when (sub) {
        "info" -> {
            put("brand", android.os.Build.BRAND)
            put("model", android.os.Build.MODEL)
            put("manufacturer", android.os.Build.MANUFACTURER)
            put("androidVersion", android.os.Build.VERSION.RELEASE)
            put("sdkInt", android.os.Build.VERSION.SDK_INT)
            put("buildId", android.os.Build.ID)
        }
        "battery" -> {
            val r = ShizukuManager.runShell("dumpsys battery", 5_000)
            put("exit_code", r.exitCode)
            if (r.exitCode == 0) {
                val info = mutableMapOf<String, String>()
                for (line in r.stdout.lineSequence()) {
                    val t = line.trim()
                    val colon = t.indexOf(':')
                    if (colon <= 0) continue
                    val k = t.substring(0, colon).trim()
                    val v = t.substring(colon + 1).trim()
                    info[k.replace(' ', '_').lowercase()] = v
                }
                put("battery", JsonObject(info.entries.associate { it.key to kotlinx.serialization.json.JsonPrimitive(it.value) }))
            } else {
                put("stderr", r.stderr)
            }
        }
        else -> put("error", "unknown device subcommand: $sub, valid: info, battery")
    }
}

// ─── Input ─────────────────────────────────────────────────────────────────

private fun handleInput(sub: String, args: kotlinx.serialization.json.JsonObject, flags: List<String>): kotlinx.serialization.json.JsonObject = buildJsonObject {
    when (sub) {
        "tap" -> {
            val x = args["x"]?.jsonPrimitive?.contentOrNull
            val y = args["y"]?.jsonPrimitive?.contentOrNull
            if (x == null || y == null) { put("error", "x and y are required"); return@buildJsonObject }
            val r = ShizukuManager.runShell("input tap $x $y")
            put("exit_code", r.exitCode)
            put("tap", "$x,$y")
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        "swipe" -> {
            val x1 = args["x1"]?.jsonPrimitive?.contentOrNull
            val y1 = args["y1"]?.jsonPrimitive?.contentOrNull
            val x2 = args["x2"]?.jsonPrimitive?.contentOrNull
            val y2 = args["y2"]?.jsonPrimitive?.contentOrNull
            if (x1 == null || y1 == null || x2 == null || y2 == null) { put("error", "x1, y1, x2, y2 are required"); return@buildJsonObject }
            val duration = args["duration"]?.jsonPrimitive?.contentOrNull
            val cmd = "input swipe $x1 $y1 $x2 $y2${duration?.let { " $it" } ?: ""}"
            val r = ShizukuManager.runShell(cmd)
            put("exit_code", r.exitCode)
            put("swipe", "$x1,$y1 -> $x2,$y2")
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        "key" -> {
            val keycode = args["keycode"]?.jsonPrimitive?.contentOrNull
            if (keycode.isNullOrBlank()) { put("error", "keycode is required"); return@buildJsonObject }
            val longPress = "--longpress" in flags
            val cmd = "input keyevent${if (longPress) " --longpress" else ""} $keycode"
            val r = ShizukuManager.runShell(cmd)
            put("exit_code", r.exitCode)
            put("key", keycode)
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        "text" -> {
            val text = args["text"]?.jsonPrimitive?.contentOrNull
            if (text.isNullOrBlank()) { put("error", "text is required"); return@buildJsonObject }
            val safe = text.replace("'", "\\'")
            val r = ShizukuManager.runShell("input text '$safe'")
            put("exit_code", r.exitCode)
            put("text", text)
            if (r.exitCode != 0) put("stderr", r.stderr)
        }
        else -> put("error", "unknown input subcommand: $sub, valid: tap, swipe, key, text")
    }
}

// ─── Service ───────────────────────────────────────────────────────────────

private fun handleService(sub: String, args: kotlinx.serialization.json.JsonObject, flags: List<String>): kotlinx.serialization.json.JsonObject = buildJsonObject {
    val snap = ShizukuManager.snapshot.value
    when (sub) {
        "status" -> {
            put("state", snap.state.name)
            put("running", snap.state == ShizukuManager.State.READY || snap.state == ShizukuManager.State.NEED_PERMISSION)
            put("authorized", snap.state == ShizukuManager.State.READY)
            put("version", snap.version)
            put("uid", snap.uid)
            put("startup_type", when (snap.uid) { 0 -> "root"; 2000 -> "adb"; else -> "unknown" })
        }
        "ping" -> {
            val ready = snap.state == ShizukuManager.State.READY
            put("ok", ready)
            put("message", if (ready) "Shizuku is running" else "Shizuku is not ready (state=${snap.state})")
        }
        else -> put("error", "unknown service subcommand: $sub, valid: status, ping")
    }
}

// ─── Exec (raw shell) ─────────────────────────────────────────────────────

private fun handleExec(sub: String, args: kotlinx.serialization.json.JsonObject, flags: List<String>): kotlinx.serialization.json.JsonObject = buildJsonObject {
    val command = args["command"]?.jsonPrimitive?.contentOrNull
    if (command.isNullOrBlank()) {
        put("error", "command is required")
        return@buildJsonObject
    }
    val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 30_000L
    val r = ShizukuManager.runShell(command, timeoutMs.coerceIn(1_000L, 120_000L))
    put("exit_code", r.exitCode)
    put("stdout", r.stdout)
    put("stderr", r.stderr)
    put("success", r.exitCode == 0)
}

private fun error(msg: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(buildJsonObject { put("error", msg) }.toString())
)
