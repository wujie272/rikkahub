package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
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

private const val TAG = "AppDataBridge"

@Serializable
data class AppDataPlugin(
    val id: String,
    val name: String,
    val packageName: String,
    val authority: String,
    val requiredPermission: String? = null,
    val description: String = "",
    val actions: List<PluginAction>,
)

@Serializable
data class PluginAction(
    val name: String,
    val description: String,
    /** ContentProvider call() 的 method 参数 */
    val method: String,
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
 * App 插件状态。
 */
sealed class PluginState {
    data object Ready : PluginState()
    data object NotInstalled : PluginState()
    data object NotExported : PluginState()
    data class NoPermission(val permission: String) : PluginState()
}

/**
 * AppDataBridge 引擎 — 基于 ContentProvider 的跨进程数据查询。
 *
 * 1. 动态发现：用户手动添加包名，扫描目标 App Manifest 中声明了 app_data_bridge=v1 的 Provider
 * 2. 检查目标 App 是否已安装 + 是否授予权限
 * 3. 通过 ContentResolver.call() 直接查询数据（系统自动启动目标进程）
 * 4. 为每个插件生成 LLM 可调用的 Tool 列表
 */
class AppDataBridge(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_data_bridge", Context.MODE_PRIVATE)

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

    fun invalidateCache() {
        loadedPlugins.clear()
    }

    /**
     * 根据包名自动发现 App 的数据查询 Provider。
     * 扫描目标 App Manifest 中带 app_data_bridge=v1 metadata 且 exported=true 的 Provider。
     */
    fun discoverByPackage(packageName: String): AppDataPlugin? {
        val pm = context.packageManager

        val appInfo = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val appLabel = pm.getApplicationLabel(appInfo).toString()

        val pkgInfo = try {
            pm.getPackageInfo(packageName, PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }

        val providers = pkgInfo.providers ?: return null
        for (pi in providers) {
            if (!pi.exported) continue
            val meta = pi.metaData ?: continue
            if (meta.getString("app_data_bridge") != "v1") continue

            val authority = pi.authority ?: continue
            // 多 authority 用分号分隔，取第一个
            val primaryAuthority = authority.split(";").first().trim()

            val standardMethods = listOf(
                "query_logs" to "查询所有记录",
                "query_settings" to "查询当前设置",
                "query_month" to "查询某月汇总（需传 year, month 参数）",
                "query_today" to "查询今日状态",
            )

            val pluginActions = standardMethods.map { (method, desc) ->
                PluginAction(
                    name = method,
                    description = desc,
                    method = method,
                    params = if (method == "query_month") mapOf(
                        "year" to "int",
                        "month" to "int"
                    ) else emptyMap(),
                )
            }

            val permission = pi.readPermission

            return AppDataPlugin(
                id = packageName,
                name = appLabel,
                packageName = packageName,
                authority = primaryAuthority,
                requiredPermission = permission?.takeIf { it.isNotBlank() },
                description = "通过 AppDataBridge (ContentProvider) 自动发现",
                actions = pluginActions,
            )
        }
        return null
    }

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

        // 检查 Provider 是否存在
        val providerInfo = try {
            pm.resolveContentProvider(plugin.authority, 0)
        } catch (_: Exception) {
            null
        }
        if (providerInfo == null || providerInfo.packageName != plugin.packageName) {
            return PluginState.NotExported
        }

        return PluginState.Ready
    }

    /**
     * 通过 ContentResolver.call() 查询数据。
     * 系统会自动启动目标 App 进程，无需手动保活。
     */
    suspend fun query(
        plugin: AppDataPlugin,
        action: PluginAction,
        args: Map<String, Any> = emptyMap(),
        timeoutMs: Long = 10000,
    ): BridgeResult {
        val state = checkPluginState(plugin)
        when (state) {
            is PluginState.NotInstalled -> return BridgeResult.AppNotInstalled(plugin.packageName)
            is PluginState.NoPermission -> return BridgeResult.PermissionDenied(state.permission)
            is PluginState.NotExported -> return BridgeResult.OtherError("Provider not found or not exported")
            is PluginState.Ready -> {}
        }

        val extras = Bundle().apply {
            args.forEach { (key, value) ->
                when (value) {
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Double -> putDouble(key, value)
                }
            }
        }

        val uri = Uri.parse("content://${plugin.authority}")

        return try {
            val bundle = withTimeoutOrNull(timeoutMs) {
                context.contentResolver.call(uri, action.method, null, extras)
            }

            if (bundle == null) {
                BridgeResult.Timeout
            } else {
                val error = bundle.getString("error")
                if (error != null) {
                    BridgeResult.OtherError(error)
                } else {
                    BridgeResult.Success(
                        dataType = bundle.getString("data_type", ""),
                        data = bundle.getString("data", "")
                    )
                }
            }
        } catch (t: SecurityException) {
            BridgeResult.PermissionDenied(plugin.requiredPermission ?: "unknown")
        } catch (t: Throwable) {
            BridgeResult.OtherError(t.message ?: t::class.java.simpleName)
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
                        "Returns structured JSON data from the app via ContentProvider IPC.",
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
                                put("recovery", "The app did not respond within timeout. " +
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
