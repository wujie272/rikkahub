package me.rerere.rikkahub.data.ai.tools.local

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

/**
 * 插件描述文件，放在 assets/app_data_plugins/ 下。
 */
@Serializable
data class AppDataPlugin(
    val id: String,
    val name: String,
    val packageName: String,
    val serviceClass: String,
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
 * 1. 从 assets/app_data_plugins/ 加载插件描述文件
 * 2. 检查目标 App 是否已安装 + 是否授予权限
 * 3. 通过 startService + PendingIntent + BroadcastReceiver 查询数据
 * 4. 为每个插件生成 LLM 可调用的 Tool 列表
 */
class AppDataBridge(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val loadedPlugins = mutableListOf<AppDataPlugin>()

    /**
     * 从 assets/app_data_plugins/ 目录加载所有插件描述文件
     */
    fun loadPlugins(): List<AppDataPlugin> {
        if (loadedPlugins.isNotEmpty()) return loadedPlugins

        try {
            val assets = context.assets
            val files = assets.list("app_data_plugins") ?: return emptyList()

            for (file in files) {
                if (!file.endsWith(".json")) continue
                try {
                    val text = assets.open("app_data_plugins/$file")
                        .bufferedReader()
                        .use { it.readText() }
                    val plugin = json.decodeFromString<AppDataPlugin>(text)
                    loadedPlugins.add(plugin)
                    Log.i(TAG, "Loaded plugin: ${plugin.id} (${plugin.name})")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load plugin $file", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list plugin files", e)
        }

        return loadedPlugins
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

        val serviceDeclared = try {
            val info = pm.getServiceInfo(
                android.content.ComponentName(plugin.packageName, plugin.serviceClass),
                0
            )
            info.exported
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!serviceDeclared) return PluginState.NotExported

        return PluginState.Ready
    }

    /**
     * 执行一次数据查询
     */
    suspend fun query(
        plugin: AppDataPlugin,
        action: PluginAction,
        args: Map<String, Any> = emptyMap(),
        timeoutMs: Long = 5000,
    ): BridgeResult {
        val state = checkPluginState(plugin)
        when (state) {
            is PluginState.NotInstalled -> return BridgeResult.AppNotInstalled(plugin.packageName)
            is PluginState.NoPermission -> return BridgeResult.PermissionDenied(state.permission)
            is PluginState.NotExported -> return BridgeResult.OtherError("Service not exported")
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
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
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
        val intent = Intent().apply {
            setClassName(plugin.packageName, plugin.serviceClass)
            setAction(intentActionStr)
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
            context.startService(intent)
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
