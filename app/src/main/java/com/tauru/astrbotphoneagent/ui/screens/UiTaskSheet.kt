package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.data.*
import kotlinx.coroutines.launch

/** Creates a bounded proposal; approval remains a separate, reviewable action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UiTaskSheet(appState: AppState, onShowApprovals: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val overview by appState.autonomyOverview.collectAsState(initial = AutonomyOverview())
    val diagnostics by appState.diagnostics.collectAsState(initial = AppDiagnostics())
    val automation by appState.automationSettings.collectAsState(initial = AutomationSettings())
    val scope = rememberCoroutineScope()
    var goal by rememberSaveable { mutableStateOf("") }
    var targetPackage by rememberSaveable { mutableStateOf("") }
    var allowedText by rememberSaveable { mutableStateOf("") }
    var successText by rememberSaveable { mutableStateOf("") }
    var stepCount by rememberSaveable { mutableStateOf("8") }
    var selectedDeviceId by rememberSaveable { mutableStateOf("") }
    var devicesExpanded by remember { mutableStateOf(false) }
    var packagesExpanded by remember { mutableStateOf(false) }
    var changingRole by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<ActionFeedback?>(null) }
    var submitted by remember { mutableStateOf(false) }
    var searchTemplate by rememberSaveable { mutableStateOf(true) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showAutomation by rememberSaveable { mutableStateOf(false) }
    val capability = overview.uiTask
    val selectedDevice = overview.devices.firstOrNull { it.deviceId == selectedDeviceId }
    val packageLabel = capability.packages.firstOrNull { it.packageName == targetPackage }?.name
    val serverReady = diagnostics.pluginConnected && capability.supported && capability.enabled && overview.taskExecuteEnabled

    LaunchedEffect(overview.devices, overview.currentDeviceId, overview.defaultDeviceId) {
        if (selectedDeviceId.isBlank()) {
            selectedDeviceId = overview.devices.firstOrNull { it.deviceId == overview.currentDeviceId }?.deviceId
                ?: overview.defaultDeviceId.takeIf { id -> overview.devices.any { it.deviceId == id } }.orEmpty()
        }
    }
    LaunchedEffect(capability.maxSteps) {
        stepCount = (stepCount.toIntOrNull() ?: 8).coerceIn(1, capability.maxSteps).toString()
    }
    fun edited() { submitted = false; feedback = null }
    fun lines(value: String) = value.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()

    if (showAutomation) {
        AutomationSettingsSheet(appState) { showAutomation = false }
        return
    }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy })) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
            .padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("让手机完成一件事", style = MaterialTheme.typography.headlineSmall)
            Text("Phone Control 会读取当前页面，逐步操作并重新检查结果。提交后先核对任务范围，再允许执行。",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!serverReady) {
                val message = when {
                    !diagnostics.pluginConnected -> "服务器尚未连接，请先检查连接设置。"
                    !capability.supported -> "服务器尚未支持界面任务，请升级 Phone Control 插件。"
                    !capability.enabled -> "界面任务尚未在服务器开启。"
                    else -> "服务器已关闭任务执行。"
                }
                FeedbackNotice(ActionFeedback(false, message))
            }

            Text("执行设备", style = MaterialTheme.typography.titleSmall)
            Box {
                OutlinedButton(onClick = { devicesExpanded = true }, enabled = !busy && overview.devices.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(selectedDevice?.let { "${it.label} · ${deviceRoleLabel(it.role)}${if (it.deviceId == overview.currentDeviceId) " · 本机" else ""}" } ?: "选择一台注册设备")
                }
                DropdownMenu(expanded = devicesExpanded, onDismissRequest = { devicesExpanded = false }) {
                    overview.devices.forEach { phone ->
                        DropdownMenuItem(text = { Text("${phone.label} · ${deviceRoleLabel(phone.role)} · ${if (phone.online) "在线" else "离线"}") },
                            onClick = { selectedDeviceId = phone.deviceId; devicesExpanded = false; edited() })
                    }
                }
            }
            if (selectedDevice != null) {
                Text("${selectedDevice.deviceId} · App ${selectedDevice.appVersion.ifBlank { "版本未知" }}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (selectedDevice.role == "unassigned") Text("这台设备尚未分配用途，请先明确用途再发起任务。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { changingRole = true }, enabled = !busy) { Text("设置这台设备的用途") }
            }
            if (selectedDevice?.deviceId == overview.currentDeviceId) {
                PhoneControlInputMethodEntry()
                val continuousReady = automation.autoApproveActions.containsAll(setOf("ui_snapshot", "ui_action"))
                Text(if (continuousReady) "连续操作已获本机授权；提交的每次任务仍需你核对完整范围。"
                    else "连续操作尚未授权。请在本机隐私与自动化中手动开启「读取界面节点」和「操作界面」免确认；否则每步会弹窗，可能因页面变化或超时停止。",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showAutomation = true }, enabled = !busy) { Text("管理本机连续操作授权") }
            } else Text("请在执行设备上启用任务输入法，并在「隐私与自动化」手动允许读取界面节点、操作界面免确认，才能连续执行。远程设备的本地授权无法在这里代为开启。", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text("任务范围", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = searchTemplate, onClick = {
                    searchTemplate = true; targetPackage = "tv.danmaku.bili"; edited()
                }, label = { Text("B 站搜视频") }, enabled = !busy)
                FilterChip(selected = !searchTemplate, onClick = { searchTemplate = false; edited() }, label = { Text("自定义任务") }, enabled = !busy)
            }
            if (searchTemplate) {
                OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it.take(200); edited() },
                    label = { Text("想搜索什么") }, placeholder = { Text("例如：哆啦A梦") }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("打开哔哩哔哩，输入搜索词并进入视频结果页。看到结果中的相关标题后才算完成。", style = MaterialTheme.typography.bodySmall)
            }
            if (!searchTemplate && capability.packages.isNotEmpty()) Box {
                OutlinedButton(onClick = { packagesExpanded = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(packageLabel ?: "从服务器应用列表选择")
                }
                DropdownMenu(expanded = packagesExpanded, onDismissRequest = { packagesExpanded = false }) {
                    capability.packages.forEach { item ->
                        DropdownMenuItem(text = { Text(item.name) }, onClick = { targetPackage = item.packageName; packagesExpanded = false; edited() })
                    }
                }
            }
            if (!searchTemplate) OutlinedTextField(value = targetPackage, onValueChange = { targetPackage = it.take(255); edited() },
                label = { Text("只操作这个 App") }, placeholder = { Text("例如 tv.danmaku.bili") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            if (!searchTemplate) OutlinedTextField(value = goal, onValueChange = { goal = it.take(500); edited() },
                label = { Text("你想完成什么") }, placeholder = { Text("打开哔哩哔哩并搜索哆啦A梦") },
                minLines = 2, maxLines = 4, enabled = !busy, modifier = Modifier.fillMaxWidth())
            if (!searchTemplate) OutlinedTextField(value = allowedText, onValueChange = { allowedText = it.take(1004); edited() },
                label = { Text("允许输入的文字") }, supportingText = { Text("每行一项，最多 5 项；不需要输入时可留空。") },
                placeholder = { Text("哆啦A梦") }, minLines = 1, maxLines = 5, enabled = !busy, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "收起完成条件与步骤上限" else "完成条件与步骤上限") }
            if (showAdvanced && !searchTemplate) OutlinedTextField(value = successText, onValueChange = { successText = it.take(1004); edited() },
                label = { Text("完成时页面需要出现的文字") }, supportingText = { Text("每行一项，最多 5 项。输入框里的文字不能单独证明任务完成。") },
                placeholder = { Text("例如搜索结果页上的内容或分类名称") }, minLines = 1, maxLines = 5,
                enabled = !busy, modifier = Modifier.fillMaxWidth())
            if (showAdvanced && searchTemplate) Text("完成时需同时出现搜索词、综合和视频分类，且结果中有相关标题。", style = MaterialTheme.typography.bodySmall)
            if (showAdvanced) OutlinedTextField(value = stepCount, onValueChange = { stepCount = it.filter(Char::isDigit).take(2); edited() },
                label = { Text("最多执行几步") }, supportingText = { Text("服务器允许 1–${capability.maxSteps} 步；每步之后重新观察。") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                enabled = !busy, modifier = Modifier.fillMaxWidth())
            Text("遇到登录、支付、发送消息、评论、删除或系统授权等关键步骤会停下来交给你。页面变化或结果无法确认时会停止，不会把未知结果当成成功。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selectedDevice != null && selectedDevice.deviceId != overview.currentDeviceId) {
                Text("这次操作发生在 ${selectedDevice.label}。请在待确认任务中核对设备，不会在当前手机执行。", style = MaterialTheme.typography.bodySmall)
            }
            feedback?.let { FeedbackNotice(it) }
            Button(onClick = {
                busy = true
                scope.launch {
                    try {
                        val count = stepCount.toIntOrNull()
                        feedback = when {
                            count == null -> ActionFeedback(false, "请填写整数步骤上限")
                            searchTemplate && searchQuery.isBlank() -> ActionFeedback(false, "请填写搜索词")
                            !searchTemplate && successText.isBlank() -> {
                                showAdvanced = true
                                ActionFeedback(false, "请填写完成页面需要出现的文字")
                            }
                            searchTemplate -> appState.proposeUiTask(UiTaskRequest(
                                "打开哔哩哔哩并搜索${searchQuery.trim()}，显示相关视频结果",
                                "tv.danmaku.bili", listOf(searchQuery.trim()), listOf(searchQuery.trim(), "综合", "视频"),
                                count, selectedDeviceId, "search_results"))
                            else -> appState.proposeUiTask(UiTaskRequest(goal.trim(), targetPackage.trim(), lines(allowedText),
                                lines(successText), count, selectedDeviceId))
                        }
                        submitted = feedback?.success == true
                    } finally { busy = false }
                }
            }, enabled = serverReady && !busy && !submitted && selectedDevice?.role in setOf("personal", "bot"), modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "正在提交…" else if (submitted) "已提交待确认" else "生成待确认任务")
            }
            if (submitted) TextButton(onClick = onShowApprovals ?: onDismiss, modifier = Modifier.fillMaxWidth()) { Text("查看待确认任务") }
        }
    }
    if (changingRole && selectedDevice != null) DeviceRoleDialog(selectedDevice, saving = busy, onDismiss = { changingRole = false }) { role, label ->
        busy = true
        scope.launch {
            try { feedback = appState.setDeviceRole(selectedDevice.deviceId, role, label) }
            finally { busy = false; changingRole = false }
        }
    }
}

internal fun deviceRoleLabel(role: String): String = when (role) {
    "personal" -> "个人手机"
    "bot" -> "Bot 手机"
    else -> "待分配用途"
}

@Composable
private fun DeviceRoleDialog(phone: RegisteredPhone, saving: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var role by remember(phone.deviceId) { mutableStateOf(phone.role) }
    var label by remember(phone.deviceId) { mutableStateOf(phone.label) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("设置设备用途") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("为 ${phone.deviceId} 明确分工。设置用途不会批准任务，也不会改变另一台手机的用途。")
                OutlinedTextField(value = label, onValueChange = { label = it.take(60) }, label = { Text("设备名称") }, singleLine = true, enabled = !saving)
                if (phone.role == "personal") {
                    Text("这台个人手机的用途已固定。可修改名称；健康数据与日常关怀继续属于这台手机。", style = MaterialTheme.typography.bodySmall)
                } else if (phone.deviceKind == "bot" || phone.role == "bot") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = role == "bot", onClick = { role = "bot" }, label = { Text("Bot 手机") }, enabled = !saving)
                        FilterChip(selected = role == "unassigned", onClick = { role = "unassigned" }, label = { Text("暂停分配") }, enabled = !saving)
                    }
                    Text("同时只启用一台专用 Bot 手机。暂停分配后，该设备不能执行新界面任务。", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("新增个人版设备不会覆盖已有个人手机。若要新增专用执行设备，请安装独立 Bot 版。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = !saving && role in setOf("personal", "bot", "unassigned"), onClick = { onSave(role, label) }) { Text(if (saving) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}
