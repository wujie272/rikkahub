package me.rerere.rikkahub.ui.pages.setting.appdatabridge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.AppDataBridge
import me.rerere.rikkahub.data.ai.tools.local.AppDataPlugin
import me.rerere.rikkahub.data.ai.tools.local.PluginState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * Settings -> AppDataBridge 插件管理页面。
 */
@Composable
fun SettingAppDataBridgePage() {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val bridge: AppDataBridge = koinInject()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var plugins by remember { mutableStateOf<List<AppDataPlugin>>(emptyList()) }
    var pluginStates by remember { mutableStateOf<Map<String, PluginState>>(emptyMap()) }
    var appLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var pkgInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun reloadPlugins() {
        val loaded = bridge.loadPlugins()
        plugins = loaded
        val pm = ctx.packageManager
        val labels = loaded.associate { p ->
            p.id to runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(p.packageName, 0)).toString()
            }.getOrElse { p.name }
        }
        appLabels = labels
        pluginStates = loaded.associate { p ->
            p.id to bridge.checkPluginState(p)
        }
    }

    LaunchedEffect(Unit) { reloadPlugins() }

    fun refreshPlugin(plugin: AppDataPlugin) {
        val pm = ctx.packageManager
        val newState = bridge.checkPluginState(plugin)
        pluginStates = pluginStates + (plugin.id to newState)
        appLabels = appLabels + (plugin.id to runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(plugin.packageName, 0)).toString()
        }.getOrElse { plugin.name })
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_app_data_bridge_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_app_data_bridge_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (plugins.isEmpty()) {
                Text(
                    text = stringResource(R.string.setting_app_data_bridge_no_plugins),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 32.dp),
                )
            } else {
                CardGroup {
                    // forEach is NOT composable, so all @Composable calls must be inside
                    // item()'s lambda parameters.
                    plugins.forEach { plugin ->
                        val state = pluginStates[plugin.id]
                        val label = appLabels[plugin.id] ?: plugin.name
                        val actionsCount = plugin.actions.size

                        item(
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val dotColor = when (state) {
                                        is PluginState.Ready -> MaterialTheme.colorScheme.primary
                                        is PluginState.NotInstalled -> MaterialTheme.colorScheme.error
                                        is PluginState.NoPermission -> MaterialTheme.colorScheme.tertiary
                                        is PluginState.NotExported -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                    Canvas(modifier = Modifier.size(10.dp)) {
                                        drawCircle(color = dotColor)
                                    }
                                    Text(
                                        text = "  $label",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                            },
                            supportingContent = {
                                Text(
                                    text = when (state) {
                                        is PluginState.Ready ->
                                            stringResource(R.string.setting_app_data_bridge_status_ready, actionsCount)
                                        is PluginState.NotInstalled ->
                                            stringResource(R.string.setting_app_data_bridge_status_not_installed)
                                        is PluginState.NoPermission ->
                                            stringResource(R.string.setting_app_data_bridge_status_no_permission, state.permission)
                                        is PluginState.NotExported ->
                                            stringResource(R.string.setting_app_data_bridge_status_not_exported)
                                        else -> stringResource(R.string.setting_app_data_bridge_status_unknown)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = {
                                    bridge.removePackage(plugin.packageName)
                                    bridge.invalidateCache()
                                    reloadPlugins()
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove")
                                }
                            },
                            onClick = {
                                when (val s = state) {
                                    is PluginState.NotInstalled -> {
                                        val intent = runCatching {
                                            ctx.packageManager.getLaunchIntentForPackage(plugin.packageName)
                                        }.getOrNull()
                                        if (intent != null) ctx.startActivity(intent)
                                    }
                                    is PluginState.NoPermission -> {
                                        runCatching {
                                            val intent = android.content.Intent(
                                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                            ).apply {
                                                data = android.net.Uri.parse("package:${ctx.packageName}")
                                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            ctx.startActivity(intent)
                                            toaster.show(
                                                ctx.getString(R.string.setting_app_data_bridge_grant_hint, s.permission),
                                                type = ToastType.Warning,
                                            )
                                        }
                                    }
                                    is PluginState.Ready -> {
                                        toaster.show(
                                            ctx.getString(R.string.setting_app_data_bridge_status_ready_toast, label),
                                            type = ToastType.Success,
                                        )
                                    }
                                    else -> refreshPlugin(plugin)
                                }
                            },
                        )
                    }
                }
            }

            // 添加包名输入框
            CardGroup(
                title = { Text(stringResource(R.string.setting_app_data_bridge_add_title)) },
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = pkgInput,
                            onValueChange = { pkgInput = it },
                            placeholder = { Text("com.jaye.didadida") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            val pkg = pkgInput.trim()
                            if (pkg.isEmpty()) return@IconButton
                            scope.launch {
                                val plugin = bridge.discoverByPackage(pkg)
                                if (plugin != null) {
                                    bridge.savePackage(pkg)
                                    bridge.invalidateCache()
                                    pkgInput = ""
                                    reloadPlugins()
                                    toaster.show(
                                        ctx.getString(R.string.setting_app_data_bridge_added, plugin.name),
                                        type = ToastType.Success,
                                    )
                                } else {
                                    toaster.show(
                                        ctx.getString(R.string.setting_app_data_bridge_not_found, pkg),
                                        type = ToastType.Error,
                                    )
                                }
                            }
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add")
                        }
                    }
                }
            }

            CardGroup(
                title = { Text(stringResource(R.string.setting_app_data_bridge_help_title)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_app_data_bridge_help_item1)) },
                    supportingContent = { Text(stringResource(R.string.setting_app_data_bridge_help_item1_desc)) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_app_data_bridge_help_item2)) },
                    supportingContent = { Text(stringResource(R.string.setting_app_data_bridge_help_item2_desc)) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_app_data_bridge_help_item3)) },
                    supportingContent = { Text(stringResource(R.string.setting_app_data_bridge_help_item3_desc)) },
                )
            }
        }
    }
}
