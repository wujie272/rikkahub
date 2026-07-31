package me.rerere.rikkahub.workflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.formatRelativeAgo
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.workflow.model.ConditionSpec
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowRun
import me.rerere.rikkahub.workflow.repository.WorkflowRepository.Loaded
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Code
import me.rerere.rikkahub.ui.components.settings.SettingsCard
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Switch as RikkaSwitch
import me.rerere.rikkahub.ui.components.ui.SwitchSize
import kotlinx.serialization.json.jsonObject

@Composable
fun WorkflowDetailScreen(
    workflowId: String,
    initialEditMode: Boolean = false,
    vm: WorkflowsViewModel = koinViewModel(),
) {
    val nav = LocalNavController.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }

    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var history by remember { mutableStateOf<List<WorkflowRun>>(emptyList()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    val editFormState = remember { EditFormState() }
    var isSaving by remember { mutableStateOf(false) }
    var hasUnsavedChanges by remember { mutableStateOf(false) }
    var showCancelConfirm by remember { mutableStateOf(false) }

    val currentLoaded = loaded

    LaunchedEffect(workflowId) {
        loaded = vm.get(workflowId)
        history = vm.history(workflowId)
    }

    // If this is a new workflow, open edit mode after loading
    if (initialEditMode) {
        LaunchedEffect(currentLoaded) {
            if (currentLoaded != null) {
                isEditing = true
            }
        }
    }
    if (currentLoaded == null) {
        Scaffold(
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.setting_page_workflows)) },
                    navigationIcon = { BackButton() },
                    colors = CustomColors.topBarColors,
                )
            },
            containerColor = CustomColors.topBarColors.containerColor,
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                Text(
                    text = stringResource(R.string.setting_page_workflows_empty),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text(stringResource(R.string.setting_page_workflow_detail_edit_cancel_confirm_title)) },
            text = { Text(stringResource(R.string.setting_page_workflow_detail_edit_cancel_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirm = false
                    isEditing = false
                    hasUnsavedChanges = false
                }) {
                    Text(stringResource(R.string.setting_page_workflow_detail_edit_cancel_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text(stringResource(R.string.setting_page_workflow_detail_edit_cancel_confirm_no))
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.setting_page_workflow_detail_delete_confirm_title)) },
            text = { Text(stringResource(R.string.setting_page_workflow_detail_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.delete(currentLoaded.entity.id) { nav.popBackStack() }
                }) {
                    Text(stringResource(R.string.setting_page_workflow_detail_delete_confirm_yes), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.setting_page_workflow_detail_delete_confirm_no))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(if (isEditing) stringResource(R.string.setting_page_workflow_detail_edit_title) else currentLoaded.entity.name)
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                color = CustomColors.topBarColors.containerColor,
            ) {
                if (isEditing) {
                    EditBottomBar(
                        isSaving = isSaving,
                        onSave = {
                            isSaving = true
                            scope.launch {
                                val def = buildDefinitionFromForm(currentLoaded.definition, editFormState)
                                if (def != null) {
                                    vm.save(def.copy(id = currentLoaded.entity.id))
                                    loaded = vm.get(currentLoaded.entity.id)
                                    history = vm.history(currentLoaded.entity.id)
                                    isEditing = false
                                    isSaving = false
                                    snackbarHostState.showSnackbar(ctx.getString(R.string.setting_page_workflow_detail_edit_saved))
                                } else {
                                    isSaving = false
                                    snackbarHostState.showSnackbar(ctx.getString(R.string.setting_page_workflow_detail_edit_invalid_form))
                                }
                            }
                        },
                        onCancel = {
                            if (hasUnsavedChanges) {
                                showCancelConfirm = true
                            } else {
                                isEditing = false
                            }
                        }
                    )
                } else {
                    ViewBottomBar(
                        currentLoaded = currentLoaded,
                        vm = vm,
                        onRefresh = {
                            loaded = vm.get(currentLoaded.entity.id)
                            history = vm.history(currentLoaded.entity.id)
                        },
                        snackbarHostState = snackbarHostState,
                        ctx = ctx,
                        nav = nav,
                        onDelete = { showDeleteConfirm = true },
                        onEdit = { isEditing = true }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (isEditing) {
            WorkflowEditForm(
                def = currentLoaded.definition,
                form = editFormState,
                onFormChanged = { hasUnsavedChanges = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            WorkflowViewContent(
                currentLoaded = currentLoaded,
                history = history,
                innerPadding = innerPadding
            )
        }
    }
}

// ─── View mode ───────────────────────────────────────────────────────────────

@Composable
private fun ViewBottomBar(
    currentLoaded: Loaded,
    vm: WorkflowsViewModel,
    onRefresh: suspend () -> Unit,
    snackbarHostState: SnackbarHostState,
    ctx: android.content.Context,
    nav: Navigator,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Button(onClick = {
            scope.launch {
                val outcome = vm.runNow(currentLoaded.entity.id)
                onRefresh()
                snackbarHostState.showSnackbar(ctx.getString(R.string.setting_page_workflow_detail_run_now_done, outcome.status.name))
            }
        }) {
            Text(stringResource(R.string.setting_page_workflow_detail_run_now))
        }
        TextButton(onClick = {
            nav.navigate(Screen.Chat(
                id = Uuid.random().toString(),
                text = ctx.getString(R.string.setting_page_workflow_detail_edit_prefill, currentLoaded.entity.name).base64Encode(),
            ))
        }) {
            Text(stringResource(R.string.setting_page_workflow_detail_edit))
        }
        // New: Edit form button
        TextButton(onClick = onEdit) {
            Text(stringResource(R.string.setting_page_workflow_detail_edit_form))
        }
        TextButton(onClick = onDelete) {
            Text(stringResource(R.string.setting_page_workflow_detail_delete), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun EditBottomBar(isSaving: Boolean = false, onSave: () -> Unit, onCancel: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Button(onClick = onSave, enabled = !isSaving) {
            Text(if (isSaving) stringResource(R.string.setting_page_workflow_detail_edit_saving) else stringResource(R.string.setting_page_workflow_detail_edit_save))
        }
        OutlinedButton(onClick = onCancel, enabled = !isSaving) {
            Text(stringResource(R.string.setting_page_workflow_detail_edit_cancel))
        }
    }
}

@Composable
private fun WorkflowViewContent(
    currentLoaded: Loaded,
    history: List<WorkflowRun>,
    innerPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = innerPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        currentLoaded.definition.description?.takeIf { it.isNotBlank() }?.let { desc ->
            item {
                SectionHeader(stringResource(R.string.setting_page_workflow_detail_section_description))
                Text(desc, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            SectionHeader(stringResource(R.string.setting_page_workflow_detail_section_trigger))
            Text(oneLineTriggerSummary(currentLoaded.definition))
        }
        item {
            SectionHeader(stringResource(R.string.setting_page_workflow_detail_section_conditions))
            if (currentLoaded.definition.conditions.isEmpty()) {
                Text(stringResource(R.string.setting_page_workflow_detail_no_conditions))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (c in currentLoaded.definition.conditions) {
                        Text("• ${conditionLine(c)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            SectionHeader(stringResource(R.string.setting_page_workflow_detail_section_actions))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((idx, a) in currentLoaded.definition.actions.withIndex()) {
                    ActionRow(idx + 1, a)
                }
            }
        }
        item {
            SectionHeader(stringResource(R.string.setting_page_workflow_detail_section_stats))
            StatsBlock(currentLoaded)
        }
        item {
            SectionHeader(stringResource(R.string.setting_page_workflow_detail_section_history))
            if (history.isEmpty()) {
                Text(stringResource(R.string.setting_page_workflow_detail_history_empty))
            } else {
                val rel = relativeStrings()
                val nowMs by rememberTickingNowMs()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (r in history) {
                        val ago = formatRelativeAgo(r.firedAtMs, nowMs, rel)
                        val line = "$ago — ${r.status.name}" + (r.errorMessage?.let { " — ${it.take(60)}" } ?: "")
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ─── Edit form state ─────────────────────────────────────────────────────────

private class EditFormState(
    name: String = "",
    description: String = "",
    enabled: Boolean = true,
    triggerType: String = "manual",
    triggerParams: MutableMap<String, String> = mutableMapOf(),
    conditions: MutableList<ConditionEditState> = mutableListOf(),
    actions: MutableList<ActionEditState> = mutableListOf(),
    cooldownSeconds: String = "0",
    maxRunsPerDay: String = "",
) {
    var name by mutableStateOf(name)
    var description by mutableStateOf(description)
    var enabled by mutableStateOf(enabled)
    var triggerType by mutableStateOf(triggerType)
    var triggerParams by mutableStateOf(triggerParams)
    var conditions by mutableStateOf(conditions)
    var actions by mutableStateOf(actions)
    var cooldownSeconds by mutableStateOf(cooldownSeconds)
    var maxRunsPerDay by mutableStateOf(maxRunsPerDay)
}

private class ConditionEditState(
    type: String = "is_charging",
    params: MutableMap<String, String> = mutableMapOf(),
    invert: Boolean = false,
) {
    var type by mutableStateOf(type)
    var params by mutableStateOf(params)
    var invert by mutableStateOf(invert)
}

private data class ActionEditState(
    var tool: String = "",
    var timeoutSeconds: String = "60",
    var argsJson: String = "{}",
)



private fun initFormState(form: EditFormState, def: WorkflowDefinition) {
    form.name = def.name
    form.description = def.description ?: ""
    form.enabled = def.enabled
    form.cooldownSeconds = def.cooldownSeconds.toString()
    form.maxRunsPerDay = def.maxRunsPerDay?.toString() ?: ""

    val (tType, tParams) = triggerToForm(def.trigger)
    form.triggerType = tType
    form.triggerParams = tParams.toMutableMap()

    form.conditions = def.conditions.map { conditionToForm(it) }.toMutableList()

    form.actions = def.actions.map { actionToForm(it) }.toMutableList()
}

private fun triggerToForm(t: TriggerSpec): Pair<String, Map<String, String>> = when (t) {
    is TriggerSpec.TimeCron -> "time_cron" to mapOf(
        "cron" to (t.cron ?: ""),
        "time_of_day" to (t.timeOfDay ?: ""),
        "days_of_week" to t.daysOfWeek.joinToString(","),
    )
    is TriggerSpec.WifiConnected -> "wifi_connected" to mapOf("ssid" to (t.ssid ?: ""))
    is TriggerSpec.WifiDisconnected -> "wifi_disconnected" to mapOf("ssid" to (t.ssid ?: ""))
    is TriggerSpec.BluetoothDeviceConnected -> "bluetooth_device_connected" to mapOf("device_address" to (t.deviceAddress ?: ""))
    is TriggerSpec.BluetoothDeviceDisconnected -> "bluetooth_device_disconnected" to mapOf("device_address" to (t.deviceAddress ?: ""))
    is TriggerSpec.HeadphonesPlugged -> "headphones_plugged" to emptyMap()
    is TriggerSpec.HeadphonesUnplugged -> "headphones_unplugged" to emptyMap()
    is TriggerSpec.PowerConnected -> "power_connected" to emptyMap()
    is TriggerSpec.PowerDisconnected -> "power_disconnected" to emptyMap()
    is TriggerSpec.BatteryBelow -> "battery_below" to mapOf("threshold_percent" to t.thresholdPercent.toString())
    is TriggerSpec.BatteryAbove -> "battery_above" to mapOf("threshold_percent" to t.thresholdPercent.toString())
    is TriggerSpec.GeofenceEnter -> "geofence_enter" to mapOf(
        "lat" to t.lat.toString(), "lng" to t.lng.toString(),
        "radius_m" to t.radiusM.toString(), "label" to (t.label ?: ""),
    )
    is TriggerSpec.GeofenceExit -> "geofence_exit" to mapOf(
        "lat" to t.lat.toString(), "lng" to t.lng.toString(),
        "radius_m" to t.radiusM.toString(), "label" to (t.label ?: ""),
    )
    is TriggerSpec.AppLaunched -> "app_launched" to mapOf("package_name" to t.packageName)
    is TriggerSpec.AppClosed -> "app_closed" to mapOf("package_name" to t.packageName)
    is TriggerSpec.NotificationReceived -> "notification_received" to mapOf(
        "package_name" to (t.packageName ?: ""),
        "title_contains" to (t.titleContains ?: ""),
        "text_contains" to (t.textContains ?: ""),
        "title_matches" to (t.titleMatches ?: ""),
        "text_matches" to (t.textMatches ?: ""),
    )
    is TriggerSpec.BootCompleted -> "boot_completed" to emptyMap()
    is TriggerSpec.ScreenOn -> "screen_on" to emptyMap()
    is TriggerSpec.ScreenOff -> "screen_off" to emptyMap()
    is TriggerSpec.Manual -> "manual" to emptyMap()
}

private fun conditionToForm(c: ConditionSpec): ConditionEditState = when (c) {
    is ConditionSpec.TimeBetween -> ConditionEditState("time_between", mutableMapOf("start" to c.start, "end" to c.end), c.invert)
    is ConditionSpec.TimeAfterSunset -> ConditionEditState("time_after_sunset", mutableMapOf("offset_minutes" to c.offsetMinutes.toString()), c.invert)
    is ConditionSpec.TimeBeforeSunrise -> ConditionEditState("time_before_sunrise", mutableMapOf("offset_minutes" to c.offsetMinutes.toString()), c.invert)
    is ConditionSpec.DayOfWeekIn -> ConditionEditState("day_of_week_in", mutableMapOf("days" to c.days.joinToString(",")), c.invert)
    is ConditionSpec.WifiSsidIs -> ConditionEditState("wifi_ssid_is", mutableMapOf("ssid" to c.ssid), c.invert)
    is ConditionSpec.WifiSsidIn -> ConditionEditState("wifi_ssid_in", mutableMapOf("ssids" to c.ssids.joinToString(",")), c.invert)
    is ConditionSpec.BatteryAbove -> ConditionEditState("battery_above", mutableMapOf("percent" to c.percent.toString()), c.invert)
    is ConditionSpec.BatteryBelow -> ConditionEditState("battery_below", mutableMapOf("percent" to c.percent.toString()), c.invert)
    is ConditionSpec.IsCharging -> ConditionEditState("is_charging", mutableMapOf(), c.invert)
    is ConditionSpec.IsNotCharging -> ConditionEditState("is_not_charging", mutableMapOf(), c.invert)
    is ConditionSpec.ForegroundAppIs -> ConditionEditState("foreground_app_is", mutableMapOf("package_name" to c.packageName), c.invert)
    is ConditionSpec.ForegroundAppIn -> ConditionEditState("foreground_app_in", mutableMapOf("package_names" to c.packageNames.joinToString(",")), c.invert)
    is ConditionSpec.ScreenIsOn -> ConditionEditState("screen_is_on", mutableMapOf(), c.invert)
    is ConditionSpec.ScreenIsOff -> ConditionEditState("screen_is_off", mutableMapOf(), c.invert)
}

private fun actionToForm(a: WorkflowAction): ActionEditState = ActionEditState(
    tool = a.tool,
    timeoutSeconds = a.timeoutSeconds.toString(),
    argsJson = a.args.toString(),
)

private fun buildDefinitionFromForm(original: WorkflowDefinition, form: EditFormState): WorkflowDefinition? {
    val trigger = formToTrigger(form.triggerType, form.triggerParams) ?: return null
    val conditions = form.conditions.mapNotNull { formToCondition(it) }
    val actions = form.actions.mapNotNull { formToAction(it) }
    if (actions.isEmpty()) return null
    return original.copy(
        name = form.name,
        description = form.description.takeIf { it.isNotBlank() },
        enabled = form.enabled,
        trigger = trigger,
        conditions = conditions,
        actions = actions,
        cooldownSeconds = form.cooldownSeconds.toIntOrNull() ?: 0,
        maxRunsPerDay = form.maxRunsPerDay.toIntOrNull(),
        updatedAtMs = System.currentTimeMillis(),
    )
}

private fun formToTrigger(type: String, params: Map<String, String>): TriggerSpec? = when (type) {
    "time_cron" -> TriggerSpec.TimeCron(
        cron = params["cron"]?.takeIf { it.isNotBlank() },
        timeOfDay = params["time_of_day"]?.takeIf { it.isNotBlank() },
        daysOfWeek = params["days_of_week"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.filter { it in 1..7 } ?: emptyList(),
    )
    "wifi_connected" -> TriggerSpec.WifiConnected(params["ssid"]?.takeIf { it.isNotBlank() })
    "wifi_disconnected" -> TriggerSpec.WifiDisconnected(params["ssid"]?.takeIf { it.isNotBlank() })
    "bluetooth_device_connected" -> TriggerSpec.BluetoothDeviceConnected(params["device_address"]?.takeIf { it.isNotBlank() })
    "bluetooth_device_disconnected" -> TriggerSpec.BluetoothDeviceDisconnected(params["device_address"]?.takeIf { it.isNotBlank() })
    "headphones_plugged" -> TriggerSpec.HeadphonesPlugged
    "headphones_unplugged" -> TriggerSpec.HeadphonesUnplugged
    "power_connected" -> TriggerSpec.PowerConnected
    "power_disconnected" -> TriggerSpec.PowerDisconnected
    "battery_below" -> TriggerSpec.BatteryBelow(params["threshold_percent"]?.toIntOrNull() ?: 50)
    "battery_above" -> TriggerSpec.BatteryAbove(params["threshold_percent"]?.toIntOrNull() ?: 50)
    "geofence_enter" -> TriggerSpec.GeofenceEnter(
        params["lat"]?.toDoubleOrNull() ?: 0.0,
        params["lng"]?.toDoubleOrNull() ?: 0.0,
        params["radius_m"]?.toIntOrNull() ?: 100,
        params["label"]?.takeIf { it.isNotBlank() },
    )
    "geofence_exit" -> TriggerSpec.GeofenceExit(
        params["lat"]?.toDoubleOrNull() ?: 0.0,
        params["lng"]?.toDoubleOrNull() ?: 0.0,
        params["radius_m"]?.toIntOrNull() ?: 100,
        params["label"]?.takeIf { it.isNotBlank() },
    )
    "app_launched" -> TriggerSpec.AppLaunched(params["package_name"] ?: "")
    "app_closed" -> TriggerSpec.AppClosed(params["package_name"] ?: "")
    "notification_received" -> TriggerSpec.NotificationReceived(
        packageName = params["package_name"]?.takeIf { it.isNotBlank() },
        titleContains = params["title_contains"]?.takeIf { it.isNotBlank() },
        textContains = params["text_contains"]?.takeIf { it.isNotBlank() },
        titleMatches = params["title_matches"]?.takeIf { it.isNotBlank() },
        textMatches = params["text_matches"]?.takeIf { it.isNotBlank() },
    )
    "boot_completed" -> TriggerSpec.BootCompleted
    "screen_on" -> TriggerSpec.ScreenOn
    "screen_off" -> TriggerSpec.ScreenOff
    "manual" -> TriggerSpec.Manual
    else -> null
}

private fun formToCondition(c: ConditionEditState): ConditionSpec? = when (c.type) {
    "time_between" -> ConditionSpec.TimeBetween(c.params["start"] ?: "00:00", c.params["end"] ?: "23:59", c.invert)
    "time_after_sunset" -> ConditionSpec.TimeAfterSunset(c.params["offset_minutes"]?.toIntOrNull() ?: 0, c.invert)
    "time_before_sunrise" -> ConditionSpec.TimeBeforeSunrise(c.params["offset_minutes"]?.toIntOrNull() ?: 0, c.invert)
    "day_of_week_in" -> ConditionSpec.DayOfWeekIn(
        c.params["days"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.filter { it in 1..7 } ?: emptyList(),
        c.invert,
    )
    "wifi_ssid_is" -> ConditionSpec.WifiSsidIs(c.params["ssid"] ?: "", c.invert)
    "wifi_ssid_in" -> ConditionSpec.WifiSsidIn(
        c.params["ssids"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
        c.invert,
    )
    "battery_above" -> ConditionSpec.BatteryAbove(c.params["percent"]?.toIntOrNull() ?: 50, c.invert)
    "battery_below" -> ConditionSpec.BatteryBelow(c.params["percent"]?.toIntOrNull() ?: 50, c.invert)
    "is_charging" -> ConditionSpec.IsCharging(c.invert)
    "is_not_charging" -> ConditionSpec.IsNotCharging(c.invert)
    "foreground_app_is" -> ConditionSpec.ForegroundAppIs(c.params["package_name"] ?: "", c.invert)
    "foreground_app_in" -> ConditionSpec.ForegroundAppIn(
        c.params["package_names"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
        c.invert,
    )
    "screen_is_on" -> ConditionSpec.ScreenIsOn(c.invert)
    "screen_is_off" -> ConditionSpec.ScreenIsOff(c.invert)
    else -> null
}

private fun formToAction(a: ActionEditState): WorkflowAction? {
    if (a.tool.isBlank()) return null
    val timeout = a.timeoutSeconds.toIntOrNull()?.coerceIn(1, 600) ?: 60
    val args = try {
        kotlinx.serialization.json.Json.parseToJsonElement(a.argsJson).jsonObject
    } catch (_: Exception) {
        buildJsonObject { }
    }
    return WorkflowAction(tool = a.tool, args = args, timeoutSeconds = timeout)
}

// ─── Edit form composables ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowEditForm(def: WorkflowDefinition, form: EditFormState, onFormChanged: () -> Unit = {}, modifier: Modifier = Modifier) {
    LaunchedEffect(def.id) {
        initFormState(form, def)
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Basic info card ──
        item {
            SettingsCard(title = stringResource(R.string.setting_page_workflow_detail_edit_name)) {
                FormItem(label = {}) {
                    OutlinedTextField(
                        value = form.name,
                        onValueChange = { form.name = it; onFormChanged() },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                HorizontalDivider()
                FormItem(label = { Text("Description") }) {
                    OutlinedTextField(
                        value = form.description,
                        onValueChange = { form.description = it; onFormChanged() },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                HorizontalDivider()
                FormItem(
                    label = { Text("Enabled") },
                    tail = {
                        RikkaSwitch(
                            checked = form.enabled,
                            onCheckedChange = { form.enabled = it; onFormChanged() },
                        )
                    }
                )
            }
        }

        // ── Trigger card ──
        item {
            SettingsCard(title = stringResource(R.string.setting_page_workflow_detail_section_trigger)) {
                TriggerEditor(
                    selectedType = form.triggerType,
                    params = form.triggerParams,
                    onTypeChange = { form.triggerType = it; form.triggerParams = mutableMapOf(); onFormChanged() },
                    onParamChange = { k, v -> form.triggerParams = (form.triggerParams + (k to v)).toMutableMap(); onFormChanged() },
                )
            }
        }

        // ── Conditions card ──
        item {
            SettingsCard(title = stringResource(R.string.setting_page_workflow_detail_section_conditions)) {
                form.conditions.forEachIndexed { idx, cond ->
                    ConditionEditor(
                        index = idx,
                        state = cond,
                        onRemove = { form.conditions = form.conditions.toMutableList().also { it.removeAt(idx) }; onFormChanged() },
                        onTypeChange = { newType ->
                            cond.type = newType
                            cond.params = mutableMapOf()
                            onFormChanged()
                        },
                        onParamChange = { k, v -> cond.params = (cond.params + (k to v)).toMutableMap(); onFormChanged() },
                        onInvertChange = { cond.invert = it; onFormChanged() },
                        onMoveUp = if (idx > 0) {{ val nl = form.conditions.toMutableList(); val t = nl[idx]; nl[idx] = nl[idx - 1]; nl[idx - 1] = t; form.conditions = nl; onFormChanged() }} else null,
                        onMoveDown = if (idx < form.conditions.lastIndex) {{ val nl = form.conditions.toMutableList(); val t = nl[idx]; nl[idx] = nl[idx + 1]; nl[idx + 1] = t; form.conditions = nl; onFormChanged() }} else null,
                    )
                    if (idx < form.conditions.lastIndex) HorizontalDivider()
                }
                Button(
                    onClick = {
                        form.conditions = form.conditions.toMutableList().also { it.add(ConditionEditState()) }
                        onFormChanged()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.setting_page_workflow_detail_edit_add_condition))
                }
            }
        }

        // ── Actions card ──
        item {
            SettingsCard(title = stringResource(R.string.setting_page_workflow_detail_section_actions)) {
                form.actions.forEachIndexed { idx, act ->
                    ActionEditor(
                        index = idx,
                        state = act,
                        onRemove = { form.actions = form.actions.toMutableList().also { it.removeAt(idx) }; onFormChanged() },
                        onChange = { newAct -> form.actions = form.actions.toMutableList().also { it[idx] = newAct }; onFormChanged() },
                        onMoveUp = if (idx > 0) {{ val nl = form.actions.toMutableList(); val t = nl[idx]; nl[idx] = nl[idx - 1]; nl[idx - 1] = t; form.actions = nl; onFormChanged() }} else null,
                        onMoveDown = if (idx < form.actions.lastIndex) {{ val nl = form.actions.toMutableList(); val t = nl[idx]; nl[idx] = nl[idx + 1]; nl[idx + 1] = t; form.actions = nl; onFormChanged() }} else null,
                    )
                    if (idx < form.actions.lastIndex) HorizontalDivider()
                }
                Button(
                    onClick = {
                        form.actions = form.actions.toMutableList().also { it.add(ActionEditState()) }
                        onFormChanged()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.setting_page_workflow_detail_edit_add_action))
                }
            }
        }

        // ── Cooldown card ──
        item {
            SettingsCard(title = stringResource(R.string.setting_page_workflow_detail_edit_cooldown)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = form.cooldownSeconds,
                        onValueChange = { form.cooldownSeconds = it.filter { c -> c.isDigit() }; onFormChanged() },
                        label = { Text(stringResource(R.string.setting_page_workflow_detail_edit_cooldown)) },
                        suffix = { Text(stringResource(R.string.setting_page_workflow_detail_edit_seconds)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = form.maxRunsPerDay,
                        onValueChange = { form.maxRunsPerDay = it.filter { c -> c.isDigit() }; onFormChanged() },
                        label = { Text(stringResource(R.string.setting_page_workflow_detail_edit_max_runs)) },
                        placeholder = { Text(stringResource(R.string.setting_page_workflow_detail_edit_unspecified)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ─── Trigger editor ──────────────────────────────────────────────────────────

private val triggerTypeLabels = listOf(
    "time_cron" to "Scheduled (cron)",
    "wifi_connected" to "WiFi connects",
    "wifi_disconnected" to "WiFi disconnects",
    "bluetooth_device_connected" to "Bluetooth connects",
    "bluetooth_device_disconnected" to "Bluetooth disconnects",
    "headphones_plugged" to "Headphones plugged",
    "headphones_unplugged" to "Headphones unplugged",
    "power_connected" to "Power connected",
    "power_disconnected" to "Power disconnected",
    "battery_below" to "Battery below %",
    "battery_above" to "Battery above %",
    "geofence_enter" to "Enter geofence",
    "geofence_exit" to "Exit geofence",
    "app_launched" to "App launched",
    "app_closed" to "App closed",
    "notification_received" to "Notification received",
    "boot_completed" to "Device booted",
    "screen_on" to "Screen on",
    "screen_off" to "Screen off",
    "manual" to "Manual only",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerEditor(
    selectedType: String,
    params: MutableMap<String, String>,
    onTypeChange: (String) -> Unit,
    onParamChange: (String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = triggerTypeLabels.firstOrNull { it.first == selectedType }?.second ?: selectedType

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.setting_page_workflow_detail_edit_trigger_type)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            triggerTypeLabels.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onTypeChange(key); expanded = false },
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    TriggerParamsEditor(selectedType, params, onParamChange)
}

@Composable
private fun TriggerParamsEditor(
    type: String,
    params: MutableMap<String, String>,
    onParamChange: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (type) {
            "time_cron" -> {
                ParamField("cron", "Cron expression (e.g. 0 8 * * *)", params, onParamChange)
                ParamField("time_of_day", "Time of day (HH:mm)", params, onParamChange)
                ParamField("days_of_week", "Days of week (1-7, comma)", params, onParamChange)
            }
            "wifi_connected", "wifi_disconnected" -> {
                ParamField("ssid", "SSID (leave empty = any)", params, onParamChange)
            }
            "bluetooth_device_connected", "bluetooth_device_disconnected" -> {
                ParamField("device_address", "Device address", params, onParamChange)
            }
            "battery_below", "battery_above" -> {
                ParamField("threshold_percent", "Threshold (%)", params, onParamChange, isNumber = true)
            }
            "geofence_enter", "geofence_exit" -> {
                ParamField("lat", "Latitude", params, onParamChange, isNumber = true)
                ParamField("lng", "Longitude", params, onParamChange, isNumber = true)
                ParamField("radius_m", "Radius (m)", params, onParamChange, isNumber = true)
                ParamField("label", "Label (optional)", params, onParamChange)
            }
            "app_launched", "app_closed" -> {
                ParamField("package_name", "Package name", params, onParamChange)
            }
            "notification_received" -> {
                ParamField("package_name", "Package name (optional)", params, onParamChange)
                ParamField("title_contains", "Title contains", params, onParamChange)
                ParamField("text_contains", "Text contains", params, onParamChange)
                ParamField("title_matches", "Title regex", params, onParamChange)
                ParamField("text_matches", "Text regex", params, onParamChange)
            }
            // Types with no params: headphones_*, power_*, boot_completed, screen_*, manual
            else -> {
                Text(
                    stringResource(R.string.setting_page_workflow_detail_edit_unspecified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─── Condition editor ────────────────────────────────────────────────────────

private val conditionTypeLabels = listOf(
    "time_between" to "Time between",
    "time_after_sunset" to "After sunset",
    "time_before_sunrise" to "Before sunrise",
    "day_of_week_in" to "Day of week",
    "wifi_ssid_is" to "WiFi SSID is",
    "wifi_ssid_in" to "WiFi SSID in",
    "battery_above" to "Battery above %",
    "battery_below" to "Battery below %",
    "is_charging" to "Is charging",
    "is_not_charging" to "Is not charging",
    "foreground_app_is" to "Foreground app is",
    "foreground_app_in" to "Foreground app in",
    "screen_is_on" to "Screen is on",
    "screen_is_off" to "Screen is off",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConditionEditor(
    index: Int,
    state: ConditionEditState,
    onRemove: () -> Unit,
    onTypeChange: (String) -> Unit,
    onParamChange: (String, String) -> Unit,
    onInvertChange: (Boolean) -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = conditionTypeLabels.firstOrNull { it.first == state.type }?.second ?: state.type

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}. ", style = MaterialTheme.typography.bodyMedium)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.setting_page_workflow_detail_edit_condition_type)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.weight(1f).menuAnchor(),
                    singleLine = true,
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    conditionTypeLabels.forEach { (key, lbl) ->
                        DropdownMenuItem(
                            text = { Text(lbl) },
                            onClick = { onTypeChange(key); expanded = false },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        ConditionParamsEditor(state.type, state.params, onParamChange)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.invert, onCheckedChange = onInvertChange)
            Text(stringResource(R.string.setting_page_workflow_detail_edit_invert), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            if (onMoveUp != null) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
                    Icon(HugeIcons.ArrowUp01, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
            if (onMoveDown != null) {
                IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                    Icon(HugeIcons.ArrowDown01, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.setting_page_workflow_detail_edit_remove_condition), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ConditionParamsEditor(
    type: String,
    params: MutableMap<String, String>,
    onParamChange: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (type) {
            "time_between" -> {
                ParamField("start", "Start (HH:mm)", params, onParamChange)
                ParamField("end", "End (HH:mm)", params, onParamChange)
            }
            "time_after_sunset", "time_before_sunrise" -> {
                ParamField("offset_minutes", "Offset (minutes)", params, onParamChange, isNumber = true)
            }
            "day_of_week_in" -> {
                ParamField("days", "Days (1-7, comma-separated)", params, onParamChange)
            }
            "wifi_ssid_is" -> {
                ParamField("ssid", "SSID", params, onParamChange)
            }
            "wifi_ssid_in" -> {
                ParamField("ssids", "SSIDs (comma-separated)", params, onParamChange)
            }
            "battery_above", "battery_below" -> {
                ParamField("percent", "Percent", params, onParamChange, isNumber = true)
            }
            "foreground_app_is" -> {
                ParamField("package_name", "Package name", params, onParamChange)
            }
            "foreground_app_in" -> {
                ParamField("package_names", "Package names (comma)", params, onParamChange)
            }
            // Types with no params: is_charging, is_not_charging, screen_is_on, screen_is_off
            else -> {
                Text(
                    stringResource(R.string.setting_page_workflow_detail_edit_unspecified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─── Action editor ───────────────────────────────────────────────────────────

@Composable
private fun ActionEditor(
    index: Int,
    state: ActionEditState,
    onRemove: () -> Unit,
    onChange: (ActionEditState) -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}. ", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.tool,
                onValueChange = { onChange(state.copy(tool = it)) },
                label = { Text(stringResource(R.string.setting_page_workflow_detail_edit_action_tool)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = state.timeoutSeconds,
                onValueChange = { onChange(state.copy(timeoutSeconds = it.filter { c -> c.isDigit() })) },
                label = { Text(stringResource(R.string.setting_page_workflow_detail_edit_action_timeout)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.width(80.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.argsJson,
                onValueChange = { onChange(state.copy(argsJson = it)) },
                label = { Text(stringResource(R.string.setting_page_workflow_detail_edit_action_args)) },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = {
                    try {
                        val formatted = kotlinx.serialization.json.Json { prettyPrint = true }.parseToJsonElement(state.argsJson).toString()
                        onChange(state.copy(argsJson = formatted))
                    } catch (_: Exception) { }
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(HugeIcons.Code, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (onMoveUp != null) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
                    Icon(HugeIcons.ArrowUp01, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
            if (onMoveDown != null) {
                IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                    Icon(HugeIcons.ArrowDown01, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.setting_page_workflow_detail_edit_remove_action), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ─── Param field helper ──────────────────────────────────────────────────────

@Composable
private fun ParamField(
    key: String,
    label: String,
    params: MutableMap<String, String>,
    onParamChange: (String, String) -> Unit,
    isNumber: Boolean = false,
) {
    OutlinedTextField(
        value = params[key] ?: "",
        onValueChange = { onParamChange(key, it) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (isNumber) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ─── Existing helpers (moved from original file) ─────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun ActionRow(index: Int, action: WorkflowAction) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$index. ", style = MaterialTheme.typography.bodyMedium)
            Text(action.tool, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            Text(" (${action.timeoutSeconds}s)", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }
        if (action.args.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                Text(if (expanded) "hide args" else "show args", style = MaterialTheme.typography.bodySmall)
            }
            if (expanded) {
                Text(action.args.toString(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun StatsBlock(loaded: Loaded) {
    val rel = relativeStrings()
    val nowMs by rememberTickingNowMs()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val lastRunText = loaded.entity.lastRunAtMs?.let {
            stringResource(R.string.setting_page_workflow_detail_stat_last_run, formatRelativeAgo(it, nowMs, rel))
        } ?: stringResource(R.string.setting_page_workflow_detail_stat_last_run_never)
        Text(lastRunText, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.setting_page_workflow_detail_stat_runs_today, loaded.entity.runsTodayCount),
            style = MaterialTheme.typography.bodySmall)
        Text(
            if (loaded.definition.cooldownSeconds == 0)
                stringResource(R.string.setting_page_workflow_detail_stat_cooldown_none)
            else
                stringResource(R.string.setting_page_workflow_detail_stat_cooldown, loaded.definition.cooldownSeconds),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            loaded.definition.maxRunsPerDay?.let {
                stringResource(R.string.setting_page_workflow_detail_stat_daily_cap, it)
            } ?: stringResource(R.string.setting_page_workflow_detail_stat_daily_cap_unlimited),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun conditionLine(c: ConditionSpec): String {
    val base = when (c) {
        is ConditionSpec.TimeBetween -> "between ${c.start} and ${c.end}"
        is ConditionSpec.TimeAfterSunset -> "after sunset" + if (c.offsetMinutes != 0) " (${c.offsetMinutes}m offset)" else ""
        is ConditionSpec.TimeBeforeSunrise -> "before sunrise" + if (c.offsetMinutes != 0) " (${c.offsetMinutes}m offset)" else ""
        is ConditionSpec.DayOfWeekIn -> "day(s) ${c.days.joinToString(",")}"
        is ConditionSpec.WifiSsidIs -> "WiFi is ${c.ssid}"
        is ConditionSpec.WifiSsidIn -> "WiFi in ${c.ssids.joinToString(",")}"
        is ConditionSpec.BatteryAbove -> "battery > ${c.percent}%"
        is ConditionSpec.BatteryBelow -> "battery < ${c.percent}%"
        is ConditionSpec.IsCharging -> "charging"
        is ConditionSpec.IsNotCharging -> "not charging"
        is ConditionSpec.ForegroundAppIs -> "foreground app = ${c.packageName}"
        is ConditionSpec.ForegroundAppIn -> "foreground in ${c.packageNames.size} pkgs"
        is ConditionSpec.ScreenIsOn -> "screen on"
        is ConditionSpec.ScreenIsOff -> "screen off"
    }
    return if (c.invert) "NOT ($base)" else base
}

// oneLineTriggerSummary, relativeStrings, rememberTickingNowMs
// are defined in WorkflowsScreen.kt (same package, internal visibility)
