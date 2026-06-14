package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.automation.ExternalAutomationConfig
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingExternalAutomationPage() {
    val config = koinInject<ExternalAutomationConfig>()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val enabled by config.enabledFlow.collectAsStateWithLifecycle(false)
    val trustedPackages by config.trustedPackagesFlow.collectAsStateWithLifecycle(emptySet())
    val recentInvocations by config.recentInvocationsFlow.collectAsStateWithLifecycle(emptyList())

    // 添加包名的状态
    var showAddPackage by remember { mutableStateOf(false) }
    var newPackageName by remember { mutableStateOf("") }
    var packageError by remember { mutableStateOf<String?>(null) }

    // 下拉刷新
    val pullRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_external_automation_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                scope.launch {
                    // 刷新就是重新 collect，已经由 Flow 自动触发
                    kotlinx.coroutines.delay(300)
                    isRefreshing = false
                }
            },
            state = pullRefreshState,
            modifier = Modifier.padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ===== 主开关 =====
                item("masterToggle") {
                    CardGroup(
                        title = {
                            Text(stringResource(R.string.setting_external_automation_master_toggle))
                        }
                    ) {
                        item(
                            leadingContent = {
                                Icon(
                                    HugeIcons.Zap,
                                    null,
                                    tint = if (enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { value ->
                                        scope.launch {
                                            config.setEnabled(value)
                                        }
                                    }
                                )
                            },
                            headlineContent = {
                                Text(stringResource(R.string.setting_external_automation_master_toggle))
                            },
                            supportingContent = {
                                Text(
                                    if (enabled) stringResource(R.string.setting_external_automation_enabled_desc)
                                    else stringResource(R.string.setting_external_automation_disabled_desc)
                                )
                            }
                        )
                    }
                }

                // ===== 可信调用者（白名单） =====
                item("trustedPackages") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.setting_external_automation_trusted_packages),
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                        )
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = CustomColors.listItemColors.containerColor
                            )
                        ) {
                            if (trustedPackages.isEmpty()) {
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            stringResource(R.string.setting_external_automation_no_trusted_packages),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    colors = ListItemDefaults.colors(containerColor = CustomColors.listItemColors.containerColor)
                                )
                            } else {
                                trustedPackages.sorted().forEachIndexed { index, pkg ->
                                    ListItem(
                                        leadingContent = {
                                            Icon(
                                                HugeIcons.Package,
                                                null,
                                                modifier = Modifier.size(24.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        trailingContent = {
                                            IconButton(
                                                onClick = {
                                                    scope.launch { config.removeTrustedPackage(pkg) }
                                                }
                                            ) {
                                                Icon(
                                                    HugeIcons.Cancel01,
                                                    stringResource(R.string.setting_external_automation_remove_package),
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        },
                                        headlineContent = {
                                            Text(
                                                text = pkg,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        colors = ListItemDefaults.colors(containerColor = CustomColors.listItemColors.containerColor)
                                    )
                                    if (index < trustedPackages.size - 1) {
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                    }
                                }
                            }

                            // 添加包名按钮/输入
                            if (showAddPackage) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = newPackageName,
                                        onValueChange = {
                                            newPackageName = it
                                            packageError = null
                                        },
                                        label = { Text(stringResource(R.string.setting_external_automation_package_name_hint)) },
                                        placeholder = { Text("net.dinglisch.android.taskerm") },
                                        isError = packageError != null,
                                        supportingText = packageError?.let { msg ->
                                            { Text(msg, color = MaterialTheme.colorScheme.error) }
                                        },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                showAddPackage = false; newPackageName = ""; packageError = null
                                            }
                                        ) {
                                            Text(stringResource(R.string.cancel))
                                        }
                                        val errEmpty = stringResource(R.string.setting_external_automation_package_empty_error)
                                        val errInvalid = stringResource(R.string.setting_external_automation_package_invalid_error)
                                        val errDuplicate = stringResource(R.string.setting_external_automation_package_duplicate_error)
                                        Button(
                                            onClick = {
                                                val trimmed = newPackageName.trim()
                                                if (trimmed.isEmpty()) {
                                                    packageError = errEmpty; return@Button
                                                }
                                                if (!trimmed.matches(Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$"""))) {
                                                    packageError = errInvalid; return@Button
                                                }
                                                if (trimmed in trustedPackages) {
                                                    packageError = errDuplicate; return@Button
                                                }
                                                scope.launch { config.addTrustedPackage(trimmed) }
                                                newPackageName = ""; showAddPackage = false; packageError = null
                                            }
                                        ) {
                                            Text(stringResource(R.string.setting_external_automation_add_package))
                                        }
                                    }
                                }
                            } else {
                                ListItem(
                                    modifier = Modifier.clickable { showAddPackage = true },
                                    leadingContent = {
                                        Icon(HugeIcons.Add01, null, tint = MaterialTheme.colorScheme.primary)
                                    },
                                    headlineContent = {
                                        Text(
                                            stringResource(R.string.setting_external_automation_add_package),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    colors = ListItemDefaults.colors(containerColor = CustomColors.listItemColors.containerColor)
                                )
                            }
                        }
                    }
                }

                // ===== 快速添加常用 App =====
                item("quickAdd") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.setting_external_automation_quick_add),
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                        )
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = CustomColors.listItemColors.containerColor
                            )
                        ) {
                            val commonApps = listOf(
                                "net.dinglisch.android.taskerm" to "Tasker",
                                "com.arlosoft.macrodroid" to "MacroDroid",
                                "com.joaomgcd.autonotification" to "AutoNotification",
                                "com.joaomgcd.join" to "Join",
                                "com.twofortyfouram.locale" to "Locale Plugin",
                                "com.termux" to "Termux",
                            )
                            commonApps.forEachIndexed { index, (pkg, label) ->
                                val alreadyTrusted = pkg in trustedPackages
                                ListItem(
                                    modifier = Modifier.clickable(
                                        enabled = !alreadyTrusted,
                                        onClick = {
                                            scope.launch { config.addTrustedPackage(pkg) }
                                        }
                                    ),
                                    leadingContent = {
                                        Icon(
                                            HugeIcons.Share01,
                                            null,
                                            modifier = Modifier.size(24.dp),
                                            tint = if (alreadyTrusted) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    trailingContent = {
                                        if (alreadyTrusted) {
                                            Text(
                                                stringResource(R.string.setting_external_automation_already_added),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    },
                                    headlineContent = { Text(label) },
                                    supportingContent = {
                                        Text(pkg, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    colors = ListItemDefaults.colors(containerColor = CustomColors.listItemColors.containerColor)
                                )
                                if (index < commonApps.size - 1) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                }
                            }
                        }
                    }
                }

                // ===== 最近调用记录 =====
                item("recentInvocations") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.setting_external_automation_recent_invocations),
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                        )
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = CustomColors.listItemColors.containerColor
                            )
                        ) {
                            if (recentInvocations.isEmpty()) {
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            stringResource(R.string.setting_external_automation_no_invocations),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    colors = ListItemDefaults.colors(containerColor = CustomColors.listItemColors.containerColor)
                                )
                            } else {
                                val dateFormat = remember {
                                    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                                }
                                recentInvocations.reversed().forEachIndexed { index, log ->
                                    ListItem(
                                        headlineContent = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(HugeIcons.Clock02, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = dateFormat.format(Date(log.timestampMs)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Text(text = "${log.callerPackage} → ${log.action}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                Text(
                                                    text = log.status,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = when (log.status) {
                                                        "accepted", "completed" -> MaterialTheme.colorScheme.primary
                                                        "failed", "rejected" -> MaterialTheme.colorScheme.error
                                                        "cancelled" -> MaterialTheme.colorScheme.tertiary
                                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        },
                                        colors = ListItemDefaults.colors(containerColor = CustomColors.listItemColors.containerColor)
                                    )
                                    if (index < recentInvocations.size - 1) {
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // ===== 说明信息 =====
                item("info") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.setting_external_automation_info_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.setting_external_automation_info_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
