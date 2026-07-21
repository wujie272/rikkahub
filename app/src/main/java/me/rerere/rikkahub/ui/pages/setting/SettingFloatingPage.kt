package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.AgentOverlay
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.overlay.FloatingBallInitializer
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingFloatingPage(vm: SettingVM = koinViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val canDrawOverlay = Settings.canDrawOverlays(context)
    val manager = FloatingBallInitializer.getManager()
    // 读取持久化配置
    val initialConfig = remember { manager?.let { runCatching { it.loadBallConfig() }.getOrNull() } }
    var ballSize by remember { mutableIntStateOf(initialConfig?.sizeDp ?: 48) }
    var snapToEdge by remember { mutableStateOf(initialConfig?.snapToEdge ?: true) }
    var autoDock by remember { mutableStateOf(initialConfig?.autoDock ?: false) }
    var dockInsetDp by remember { mutableIntStateOf(initialConfig?.dockInsetPx?.div(2) ?: 0) } // px→dp 近似
    var ballVisible by remember { mutableStateOf(manager?.triggerBall?.isShown() ?: false) }
    // 保存配置到 DataStore 并应用到触发球
    fun applyConfig() {
        val mgr = manager ?: return
        mgr.saveAndApplyBallConfig(
            mgr.BallConfig(
                sizeDp = ballSize,
                snapToEdge = snapToEdge,
                autoDock = autoDock,
                dockInsetPx = dockInsetDp * 2, // dp→px 近似
            )
        )
    }
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_floating)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 权限
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("权限") }
                ) {
                    item(
                        headlineContent = { Text("悬浮窗权限") },
                        supportingContent = {
                            Text(
                                if (canDrawOverlay) "已授予 — 浮窗功能正常"
                                else "未授予 — 浮窗功能不可用"
                            )
                        },
                        trailingContent = {
                            if (!canDrawOverlay) {
                                Button(
                                    onClick = {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) { Text("去授权") }
                            }
                        }
                    )
                }
            }
            // 触发球开关
                    title = { Text("悬浮触发球") }
                        leadingContent = { Icon(HugeIcons.Circle, null) },
                        headlineContent = { Text("启用悬浮触发球") },
                        supportingContent = { Text("在任何 App 界面显示可拖拽的悬浮球") },
                            Switch(
                                checked = ballVisible,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        FloatingBallInitializer.showBall()
                                    } else {
                                        FloatingBallInitializer.hideBall()
                                    }
                                    ballVisible = enabled
                                }
            // 球大小
                    title = { Text("外观") }
                        leadingContent = { Icon(HugeIcons.Touch04, null) },
                        headlineContent = { Text("触发球大小") },
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Slider(
                                    value = ballSize.toFloat(),
                                    onValueChange = { ballSize = it.toInt(); applyConfig() },
                                    valueRange = 28f..80f,
                                    steps = 12, // 28→32→36→...→80
                                    modifier = Modifier.weight(1f)
                                )
                                Text("${ballSize}dp")
            // 吸附行为
                    title = { Text("吸附行为") }
                        leadingContent = { Icon(HugeIcons.DragLeft04, null) },
                        headlineContent = { Text("边缘吸附") },
                            Text("松手后自动吸附到屏幕左/右边缘，半透明待机")
                                checked = snapToEdge,
                                onCheckedChange = { snapToEdge = it; applyConfig() }
                    if (snapToEdge) {
                        item(
                            leadingContent = { Icon(HugeIcons.MagicWand, null) },
                            headlineContent = { Text("自动贴边") },
                            supportingContent = { Text("3 秒无操作后自动贴边隐藏") },
                            trailingContent = {
                                Switch(
                                    checked = autoDock,
                                    onCheckedChange = { autoDock = it; applyConfig() }
                        )
                            leadingContent = { Icon(HugeIcons.CursorPointer01, null) },
                            headlineContent = { Text("边缘避让") },
                            supportingContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = dockInsetDp.toFloat(),
                                        onValueChange = { dockInsetDp = it.toInt(); applyConfig() },
                                        valueRange = 0f..20f,
                                        steps = 4,
                                        modifier = Modifier.weight(1f)
                                    Text("${dockInsetDp}dp")
                    }
            // Agent 状态浮窗
                    title = { Text("AI 状态浮窗") }
                        leadingContent = { Icon(HugeIcons.AiCloud, null) },
                        headlineContent = { Text("显示执行状态") },
                        supportingContent = { Text("AI 执行任务时显示详细步骤和进度条") },
                                checked = true,
                                onCheckedChange = { AgentOverlay.setEnabled(it) }
            // 对话浮窗
                    title = { Text("AI 对话浮窗") }
                        leadingContent = { Icon(HugeIcons.BubbleChat, null) },
                        headlineContent = { Text("打开对话浮窗") },
                        supportingContent = { Text("在悬浮窗中与 AI 对话，无需切换 App") },
                            Button(
                                onClick = { manager?.showChatWindow() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                            ) { Text("打开") }
            // 显示模式
                    title = { Text("显示模式") }
                        leadingContent = { Icon(HugeIcons.Sparkles, null) },
                        headlineContent = { Text("简单图标") },
                        supportingContent = { Text("默认的圆形图标，轻量省电") },
                            androidx.compose.material3.RadioButton(
                                selected = FloatingBallInitializer.getManager()?.triggerBall?.mode == me.rerere.rikkahub.ui.overlay.FloatingTriggerBall.Mode.ICON,
                                onClick = {
                                    FloatingBallInitializer.getManager()?.triggerBall?.setMode(me.rerere.rikkahub.ui.overlay.FloatingTriggerBall.Mode.ICON)
                        leadingContent = { Icon(HugeIcons.AiMagic, null) },
                        headlineContent = { Text("Live2D 角色") },
                            Text("显示 Live2D 动画角色，支持触摸交互")
                                selected = FloatingBallInitializer.getManager()?.triggerBall?.mode == me.rerere.rikkahub.ui.overlay.FloatingTriggerBall.Mode.LIVE2D,
                                    FloatingBallInitializer.getManager()?.triggerBall?.setMode(me.rerere.rikkahub.ui.overlay.FloatingTriggerBall.Mode.LIVE2D)
                                    // 如果有已导入的模型，自动加载第一个
                                    val models = me.rerere.rikkahub.ui.overlay.Live2DModelManager.scanModels(context)
                                    if (models.isNotEmpty()) {
                                        FloatingBallInitializer.getManager()?.triggerBall?.live2DRenderer?.loadModel(models.first().modelFile.absolutePath)
            // 提示
            // 模型管理
                // 使用 remember + mutableStateOf 管理模型列表和 launcher
                val models = remember { mutableStateOf(me.rerere.rikkahub.ui.overlay.Live2DModelManager.scanModels(context)) }
                var selectedModelPath by remember { mutableStateOf<String?>(null) }
                var previewActive by remember { mutableStateOf(false) }
                var importError by remember { mutableStateOf<String?>(null) }
                // SAF 文件选择器：导入 .zip 模型
                val importLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri != null) {
                        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                        val success = me.rerere.rikkahub.ui.overlay.Live2DModelManager.importModel(context, uri)
                        if (success) {
                            models.value = me.rerere.rikkahub.ui.overlay.Live2DModelManager.scanModels(context)
                            android.widget.Toast.makeText(context, "模型导入成功", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            importError = "导入失败，请检查文件格式"
                // 模型预览自动关闭
                if (previewActive && selectedModelPath != null) {
                    LaunchedEffect(selectedModelPath) {
                        kotlinx.coroutines.delay(5000)
                        previewActive = false
                        FloatingBallInitializer.getManager()?.triggerBall?.setMode(me.rerere.rikkahub.ui.overlay.FloatingTriggerBall.Mode.ICON)
                    title = { Text("模型管理") }
                    if (models.value.isEmpty()) {
                            headlineContent = { Text("暂无模型") },
                            supportingContent = { Text("点击下方「导入模型」按钮导入 Live2D 模型") }
                    } else {
                        models.value.forEach { model ->
                            val isCurrentModel = selectedModelPath == model.modelFile.absolutePath
                            item(
                                headlineContent = { Text(model.name) },
                                supportingContent = {
                                    Text(if (isCurrentModel && previewActive) "预览中…" else model.modelFile.name)
                                },
                                trailingContent = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Button(
                                            onClick = {
                                                selectedModelPath = model.modelFile.absolutePath
                                                previewActive = true
                                                FloatingBallInitializer.getManager()?.triggerBall?.let { ball ->
                                                    ball.setMode(me.rerere.rikkahub.ui.overlay.FloatingTriggerBall.Mode.LIVE2D)
                                                    ball.live2DRenderer?.loadModel(model.modelFile.absolutePath)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isCurrentModel && previewActive)
                                                    MaterialTheme.colorScheme.tertiary
                                                else
                                                    MaterialTheme.colorScheme.primary
                                            ),
                                            modifier = Modifier.padding(end = 4.dp)
                                        ) {
                                            Text(if (isCurrentModel && previewActive) "预览中" else "预览")
                                        }
                                        IconButton(
                                                me.rerere.rikkahub.ui.overlay.Live2DModelManager.deleteModel(model)
                                                models.value = me.rerere.rikkahub.ui.overlay.Live2DModelManager.scanModels(context)
                                                if (selectedModelPath == model.modelFile.absolutePath) {
                                                    selectedModelPath = null
                                                    previewActive = false
                                            }
                                            Icon(HugeIcons.Delete01, null, tint = MaterialTheme.colorScheme.error)
                        headlineContent = { Text("导入模型") },
                        supportingContent = { Text("支持 .zip 格式的 Live2D 模型包") },
                                    importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) { Text("导入") }
                    if (importError != null) {
                            headlineContent = { Text(importError ?: "", color = MaterialTheme.colorScheme.error) }
                        headlineContent = { Text("模型存放位置") },
                            Text("导入的模型会保存在: ${me.rerere.rikkahub.ui.overlay.Live2DModelManager.getModelsDir(context).absolutePath}")
                    title = { Text("使用提示") }
                        headlineContent = { Text("拖拽") },
                        supportingContent = { Text("拖拽悬浮球或浮窗顶部可移动位置") }
                        leadingContent = { Icon(HugeIcons.DragDropHorizontal, null) },
                        headlineContent = { Text("缩放") },
                        supportingContent = { Text("拖拽浮窗右下角可调整大小") }
                        leadingContent = { Icon(HugeIcons.Lock, null) },
                        headlineContent = { Text("锁定") },
                        supportingContent = { Text("点击浮窗左上角锁图标锁定/解锁，锁定后防误触") }
        }
}
