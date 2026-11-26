package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.ShizukuManager
import me.rerere.rikkahub.service.ShizukuBackend
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Shizuku 专用设置页。
 * 状态驱动的引导页面：根据 Shizuku 状态显示不同的操作按钮。
 *
 *   NOT_INSTALLED  → 安装按钮（Shizuku / AXManager）
 *   NOT_RUNNING    → 打开管理器启动服务
 *   NEED_PERMISSION → 授权 RikkaHub
 *   READY          → 绿色就绪状态 + 版本信息
 */
@Composable
fun SettingShizukuPage() {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snap by ShizukuManager.snapshot.collectAsState()

    // 每次切回页面刷新状态
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ShizukuManager.refresh()
                resumeTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }



    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_shizuku_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 状态卡片
            CardGroup {
                item(
                    headlineContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Canvas(modifier = Modifier.size(12.dp)) {
                                drawCircle(color = stateColor(snap.state))
                            }
                            Text(text = stringResource(stateTitle(snap.state)))
                        }
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(stateSubtitle(snap.state)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = if (snap.state == ShizukuManager.State.READY) {
                        {
                            Text(
                                text = "v${snap.version} · uid=${snap.uid}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else null,
                )
            }

            // 操作按钮
            when (snap.state) {
                ShizukuManager.State.NOT_INSTALLED -> {
                    Text(
                        text = stringResource(R.string.setting_shizuku_action_header),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                    CardGroup {
                        item(
                            onClick = { ShizukuManager.openInstallPage(context) },
                            headlineContent = { Text(stringResource(R.string.setting_shizuku_install_shizuku_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_shizuku_install_shizuku_desc)) },
                        )
                        item(
                            onClick = { ShizukuManager.openInstallPage(context, ShizukuBackend.AXMANAGER_GITHUB_URL) },
                            headlineContent = { Text(stringResource(R.string.setting_shizuku_install_axmanager_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_shizuku_install_axmanager_desc)) },
                        )
                    }
                }

                ShizukuManager.State.NOT_RUNNING -> {
                    Text(
                        text = stringResource(R.string.setting_shizuku_action_header),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                    CardGroup {
                        item(
                            onClick = { ShizukuManager.openManagerApp(context) },
                            headlineContent = { Text(stringResource(R.string.setting_shizuku_open_manager)) },
                            supportingContent = { Text(stringResource(R.string.setting_shizuku_open_manager_desc)) },
                        )
                        item(
                            onClick = { ShizukuManager.refresh() },
                            headlineContent = { Text(stringResource(R.string.setting_shizuku_recheck)) },
                            supportingContent = { Text(stringResource(R.string.setting_shizuku_recheck_desc)) },
                        )
                    }
                }

                ShizukuManager.State.NEED_PERMISSION -> {
                    Text(
                        text = stringResource(R.string.setting_shizuku_action_header),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                    CardGroup {
                        item(
                            onClick = { ShizukuManager.requestPermission() },
                            headlineContent = { Text(stringResource(R.string.setting_shizuku_grant)) },
                            supportingContent = { Text(stringResource(R.string.setting_shizuku_grant_desc)) },
                        )
                        item(
                            onClick = { ShizukuManager.openManagerApp(context) },
                            headlineContent = { Text(stringResource(R.string.setting_shizuku_open_manager)) },
                            supportingContent = { Text(stringResource(R.string.setting_shizuku_open_manager_desc)) },
                        )
                    }
                }

                ShizukuManager.State.READY -> {
                    Text(
                        text = stringResource(R.string.setting_shizuku_capabilities_header),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                    CardGroup {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_shizuku_cap_shell)) },
                            supportingContent = { Text(stringResource(R.string.setting_shizuku_cap_shell_desc)) },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_shizuku_cap_input)) },
                            supportingContent = { Text(stringResource(R.string.setting_shizuku_cap_input_desc)) },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_shizuku_cap_screenshot)) },
                            supportingContent = { Text(stringResource(R.string.setting_shizuku_cap_screenshot_desc)) },
                        )
                        item(
                            onClick = { ShizukuManager.openManagerApp(context) },
                            headlineContent = { Text(stringResource(R.string.setting_shizuku_open_manager)) },
                            supportingContent = { Text(stringResource(R.string.setting_shizuku_open_manager_desc)) },
                        )
                    }

                    Button(
                        onClick = { ShizukuManager.refresh() },
                        modifier = Modifier.padding(top = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    ) {
                        Text(stringResource(R.string.setting_shizuku_recheck))
                    }
                }
            }
        }
    }
}

private fun stateTitle(s: ShizukuManager.State): Int = when (s) {
    ShizukuManager.State.NOT_INSTALLED -> R.string.setting_shizuku_state_not_installed
    ShizukuManager.State.NOT_RUNNING -> R.string.setting_shizuku_state_not_running
    ShizukuManager.State.NEED_PERMISSION -> R.string.setting_shizuku_state_need_permission
    ShizukuManager.State.READY -> R.string.setting_shizuku_state_ready
}

private fun stateSubtitle(s: ShizukuManager.State): Int = when (s) {
    ShizukuManager.State.NOT_INSTALLED -> R.string.setting_shizuku_state_not_installed_desc
    ShizukuManager.State.NOT_RUNNING -> R.string.setting_shizuku_state_not_running_desc
    ShizukuManager.State.NEED_PERMISSION -> R.string.setting_shizuku_state_need_permission_desc
    ShizukuManager.State.READY -> R.string.setting_shizuku_state_ready_desc
}

private fun stateColor(s: ShizukuManager.State): androidx.compose.ui.graphics.Color = when (s) {
    ShizukuManager.State.READY -> androidx.compose.ui.graphics.Color(0xFF22C55E)
    ShizukuManager.State.NEED_PERMISSION -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
    ShizukuManager.State.NOT_RUNNING -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
    ShizukuManager.State.NOT_INSTALLED -> androidx.compose.ui.graphics.Color(0xFFEF4444)
}
