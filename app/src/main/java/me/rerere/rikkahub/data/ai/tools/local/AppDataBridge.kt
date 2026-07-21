package me.rerere.rikkahub.data.ai.tools.local

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.UUID

private const val TAG = "AppDataBridge"

@Serializable
data class AppDataPlugin(
    val id: String,
    val name: String,
    val packageName: String,
    val componentClass: String,
    val requiredPermission: String? = null,
    val minAppVersion: String? = null,
    val description: String = "",
    val actions: List<PluginAction>,
)

@Serializable
data class PluginAction(
    val name: String,
    val description: String,
    val intentAction: String,
    val params: Map<String, String> = emptyMap(),
)

/**
 * 查询结果封装
 */
sealed class BridgeResult {
    data class Success(
        val dataType: String,
        val data: String,
        val extras: Map<String, String> = emptyMap(),
    ) : BridgeResult()
    data object Timeout : BridgeResult()
    data class PermissionDenied(val permission: String) : BridgeResult()
    data class AppNotInstalled(val packageName: String) : BridgeResult()
    data class OtherError(val message: String) : BridgeResult()
}

/**
 * App 插件状态。用 sealed class 而非 enum，因为 NoPermission 需要携带 permission 参数。
 */
sealed class PluginState {
    data object Ready : PluginState()
    data object NotInstalled : PluginState()
    data object NotExported : PluginState()
    data class NoPermission(val permission: String) : PluginState()
}

/**
 * AppDataBridge 引擎。
 *
 * 1. 动态发现：用户手动添加包名，扫描目标 App Manifest 中声明了 app_data_bridge=v1 的 Receiver
 * 2. 检查目标 App 是否已安装 + 是否授予权限
 * 3. 通过 sendBroadcast + PendingIntent + BroadcastReceiver 查询数据
 * 4. 为每个插件生成 LLM 可调用的 Tool 列表
 */
class AppDataBridge(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_data_bridge", Context.MODE_PRIVATE)

    /** 用户通过包名添加的插件列表 */
    private fun getSavedPackages(): List<String> =
        prefs.getStringSet("saved_packages", emptySet())?.toList() ?: emptyList()

    fun savePackage(packageName: String) {
        val set = prefs.getStringSet("saved_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(packageName)
        prefs.edit().putStringSet("saved_packages", set).apply()
    }

    fun removePackage(packageName: String) {
        val set = prefs.getStringSet("saved_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.remove(packageName)
        prefs.edit().putStringSet("saved_packages", set).apply()
    }

    private val loadedPlugins = mutableListOf<AppDataPlugin>()

    /**
     * 从用户保存的包名自动发现并加载所有插件
     */
    fun loadPlugins(): List<AppDataPlugin> {
        if (loadedPlugins.isNotEmpty()) return loadedPlugins

        for (pkg in getSavedPackages()) {
            val plugin = discoverByPackage(pkg)
            if (plugin != null) {
                loadedPlugins.add(plugin)
                Log.i(TAG, "Discovered plugin from package: ${plugin.id} (${plugin.name})")
            }
        }

        return loadedPlugins
    }

    /** 清空缓存，下次 loadPlugins 会重新扫描 */
    fun invalidateCache() {

        loadedPlugins.clear()
    }

    /**
     * 根据包名自动发现 App 的数据查询 Receiver。
     * 读取目标 App Manifest 中带 app_data_bridge metadata 且 exported=true 的 Receiver，
     * 提取其 intent-filter action 列表，自动生成插件定义。
     */
    fun discoverByPackage(packageName: String): AppDataPlugin? {
        val pm = context.packageManager

        val appInfo = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }

        val appLabel = pm.getApplicationLabel(appInfo).toString()

        // 使用 getPackageInfo(GET_RECEIVERS | GET_META_DATA) 枚举所有 Receiver
        val pkgInfo = try {
            pm.getPackageInfo(packageName, PackageManager.GET_RECEIVERS or PackageManager.GET_META_DATA)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }

        val receivers = pkgInfo.receivers ?: return null

        for (ri in receivers) {
            if (!ri.exported) continue
            val meta = ri.metaData ?: continue
            if (meta.getString("app_data_bridge") != "v1") continue

            // 协议规定：声明了 app_data_bridge=v1 的 Receiver 必须支持这 4 种 action
            // 无需动态查 IntentFilter，直接用标准协议生成
            val standardActions = listOf("QUERY_LOGS", "QUERY_SETTINGS", "QUERY_MONTH", "QUERY_TODAY")

            val pluginActions = standardActions.map { action ->
                val toolName = action.lowercase()
                val desc = when (action) {
                    "QUERY_LOGS" -> "查询所有记录"
                    "QUERY_SETTINGS" -> "查询当前设置"
                    "QUERY_MONTH" -> "查询某月汇总（需传 year, month 参数）"
                    "QUERY_TODAY" -> "查询今日状态"
                    else -> "查询数据: $action"
                }
                PluginAction(
                    name = toolName,
                    description = desc,
                    intentAction = action,
                    params = if (action == "QUERY_MONTH") mapOf(
                        "year" to "int",
                        "month" to "int"
                    ) else emptyMap(),
                )
            }

            val permission = ri.permission

            return AppDataPlugin(
                id = packageName,
                name = appLabel,
                packageName = packageName,
                componentClass = ri.name,
                requiredPermission = permission?.takeIf { it.isNotBlank() },
                description = "通过 AppDataBridge 自动发现",
                actions = pluginActions,
            )
        }

        return null
    }

    /**
     * 检查插件对应的 App 是否可用
     */
    fun checkPluginState(plugin: AppDataPlugin): PluginState {
        val pm = context.packageManager
        val installed = try {
            pm.getPackageInfo(plugin.packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) return PluginState.NotInstalled

        val perm = plugin.requiredPermission
        if (perm != null) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, perm
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return PluginState.NoPermission(perm)
        }

        val receiverDeclared = try {
            val info = pm.getReceiverInfo(
                android.content.ComponentName(plugin.packageName, plugin.componentClass),
                0
            )
            info.exported
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!receiverDeclared) return PluginState.NotExported

        return PluginState.Ready
    }

    /**
     * 执行一次数据查询
     */
    /**
     * 执行一次数据查询，如果超时则自动重试一次（应对 App 冷启动场景）。
     */
    suspend fun query(
        plugin: AppDataPlugin,
        action: PluginAction,
        args: Map<String, Any> = emptyMap(),
        timeoutMs: Long = 15000,
    ): BridgeResult {
        val first = queryOnce(plugin, action, args, timeoutMs)
        if (first is BridgeResult.Timeout) {
            Log.i(TAG, "First query timed out, retrying once (cold start?)")
            // 第二次用短超时——第一次已经触发了冷启动，进程应该活了
            return queryOnce(plugin, action, args, 5000)
        }
        return first
    }

    /**
     * 单次数据查询（不重试）。
     */
    private suspend fun queryOnce(
        plugin: AppDataPlugin,
        action: PluginAction,
        args: Map<String, Any> = emptyMap(),
        timeoutMs: Long = 15000,
    ): BridgeResult {
        val state = checkPluginState(plugin)
        when (state) {
            is PluginState.NotInstalled -> return BridgeResult.AppNotInstalled(plugin.packageName)
            is PluginState.NoPermission -> return BridgeResult.PermissionDenied(state.permission)
            is PluginState.NotExported -> return BridgeResult.OtherError("Receiver not exported or not found")
            is PluginState.Ready -> {} // 继续
        }

        val resultDeferred = CompletableDeferred<Bundle>()
        val resultAction = "${context.packageName}.APP_DATA_RESULT_${UUID.randomUUID()}"

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val bundle = intent.getBundleExtra("result")
                val keys = intent.extras?.keySet()?.joinToString(",")
                Log.i(TAG, "broadcast: action=${intent.action} keys=[$keys] hasBundle=${bundle != null}")
                if (bundle != null && resultDeferred.isActive) {
                    resultDeferred.complete(bundle)
                }
            }
        }
        val filter = IntentFilter(resultAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        val pi = try {
            val resultIntent = Intent(resultAction).setPackage(context.packageName)
            PendingIntent.getBroadcast(
                context,
                resultAction.hashCode(),
                resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        } catch (t: Throwable) {
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
            return BridgeResult.OtherError("PendingIntent failed: ${t.message}")
        }

        val intentActionStr = action.intentAction
        // 标记为前台广播，提高系统启动目标进程的意愿
        val intent = Intent().apply {
            setClassName(plugin.packageName, plugin.componentClass)
            setAction(intentActionStr)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra("callback", pi)
            args.forEach { (key, value) ->
                when (value) {
                    is Int -> putExtra(key, value)
                    is String -> putExtra(key, value)
                    is Boolean -> putExtra(key, value)
                    is Double -> putExtra(key, value)
                    else -> {} // 忽略不支持的类型
                }
            }
        }

        return try {
            context.sendBroadcast(intent)
            val bundle = withTimeoutOrNull(timeoutMs) { resultDeferred.await() }

            if (bundle == null) {
                BridgeResult.Timeout
            } else {
                val dataType = bundle.getString("data_type", "")
                val data = bundle.getString("data", "")
                val error = bundle.getString("error")
                if (error != null) {
                    BridgeResult.OtherError(error)
                } else {
                    BridgeResult.Success(dataType = dataType, data = data)
                }
            }
        } catch (t: SecurityException) {
            BridgeResult.PermissionDenied(plugin.requiredPermission ?: "unknown")
        } catch (t: Throwable) {
            BridgeResult.OtherError(t.message ?: t::class.java.simpleName)
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
            try { pi.cancel() } catch (_: Throwable) {}
        }
    }

    /**
     * 为每个插件生成 LLM Tool
     */
    fun toTools(): List<Tool> {
        val plugins = loadPlugins()
        return plugins.flatMap { plugin ->
            plugin.actions.map { act ->
                val pluginId = plugin.id
                val actionName = act.name
                val toolName = "${pluginId}_${actionName}"

                // 预计算参数 schema，避免闭包捕获循环变量
                val hasParams = act.params.isNotEmpty()
                val paramsSchema = if (hasParams) {
                    buildJsonObject {
                        act.params.forEach { (key, type) ->
                            put(key, buildJsonObject {
                                put("type", when (type) {
                                    "int" -> "integer"
                                    "string" -> "string"
                                    "boolean" -> "boolean"
                                    else -> "string"
                                })
                                put("description", key)
                            })
                        }
                    }
                } else null

                Tool(
                    name = toolName,
                    description = "[${plugin.name}] ${act.description} " +
                        "Requires: ${plugin.name} (${plugin.packageName}) installed on device. " +
                        "Returns structured JSON data from the app via local IPC.",
                    parameters = {
                        if (paramsSchema != null) {
                            InputSchema.Obj(
                                properties = paramsSchema,
                                required = act.params.keys.toList()
                            )
                        } else null
                    },
                    execute = { input ->
                        val callArgs = mutableMapOf<String, Any>()

                        // 解析参数
                        act.params.keys.forEach { key ->
                            val elem = input.jsonObject[key]
                            if (elem != null) {
                                val primitive = elem.jsonPrimitive
                                callArgs[key] = when (act.params[key]) {
                                    "int" -> primitive.contentOrNull?.toIntOrNull() ?: 0
                                    else -> primitive.contentOrNull ?: ""
                                }
                            }
                        }

                        // query_month 需要额外从 input 拿 year/month
                        if (act.name == "query_month") {
                            val year = input.jsonObject["year"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 2026
                            val month = input.jsonObject["month"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                            callArgs["year"] = year
                            callArgs["month"] = month
                        }

                        val result = runCatching {
                            kotlinx.coroutines.runBlocking {
                                query(plugin, act, callArgs)
                            }
                        }.getOrElse { BridgeResult.OtherError(it.message ?: "execution failed") }

                        val payload = when (result) {
                            is BridgeResult.Success -> buildJsonObject {
                                put("success", true)
                                put("app", plugin.name)
                                put("data_type", result.dataType)
                                put("data", result.data)
                            }
                            is BridgeResult.Timeout -> buildJsonObject {
                                put("error", "timeout")
                                put("recovery", "The app did not respond within 5s. " +
                                    "Make sure ${plugin.name} is installed and has the correct version.")
                            }
                            is BridgeResult.PermissionDenied -> buildJsonObject {
                                put("error", "permission_denied")
                                put("permission", result.permission)
                                put("recovery", "Grant the permission and restart. " +
                                    "You can also run: adb shell pm grant ${context.packageName} ${result.permission}")
                            }
                            is BridgeResult.AppNotInstalled -> buildJsonObject {
                                put("error", "app_not_installed")
                                put("package", result.packageName)
                                put("recovery", "${plugin.name} is not installed. Install it first.")
                            }
                            is BridgeResult.OtherError -> buildJsonObject {
                                put("error", "query_failed")
                                put("reason", result.message)
                            }
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }
                )
            }
        }
    }
}
