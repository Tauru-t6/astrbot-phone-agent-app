package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.BuildConfig
import com.tauru.astrbotphoneagent.data.AppDiagnostics
import com.tauru.astrbotphoneagent.data.AppState
import com.tauru.astrbotphoneagent.data.ConnectionState
import com.tauru.astrbotphoneagent.data.TaskApproval
import kotlinx.coroutines.launch

/** Server-reported task, approval and care status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutonomySheet(appState: AppState, onDismiss: () -> Unit) {
    val diagnostics by appState.diagnostics.collectAsState(initial = AppDiagnostics())
    val deviceStatus by appState.deviceStatus.collectAsState(initial = null)
    val overview by appState.autonomyOverview.collectAsState(initial = null)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val scope = rememberCoroutineScope()
    var decisionNotice by remember { mutableStateOf("") }
    var showUiTask by remember { mutableStateOf(false) }
    var decisionBusy by remember { mutableStateOf(false) }

    if (showUiTask) {
        UiTaskSheet(appState) { showUiTask = false }
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("自主功能", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (BuildConfig.IS_BOT_DEVICE) "查看手机任务的执行范围与结果，管理审批。" else "查看手机任务的执行范围与结果，管理审批，并了解夜间守护与关怀状态。",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )

            HorizontalDivider()
            Text("界面任务", style = MaterialTheme.typography.titleMedium)
            val uiTask = overview?.uiTask
            CapabilityRow(
                title = "逐步操作与核验",
                value = when {
                    !diagnostics.pluginConnected -> "未连接"
                    uiTask?.supported != true -> "待服务器支持"
                    !uiTask.enabled || overview?.taskExecuteEnabled != true -> "未开启"
                    else -> "可发起"
                },
                detail = "选择一台手机和一个 App，设置允许输入的文字与步骤上限。执行前需批准；关键步骤和无法确认的结果会停下交给你。",
            )
            Button(onClick = { showUiTask = true }, modifier = Modifier.fillMaxWidth()) { Text("发起手机界面任务") }

            HorizontalDivider()
            Text("能力状态", style = MaterialTheme.typography.titleMedium)
            CapabilityRow(
                title = "连接通道",
                value = when (deviceStatus?.connection) {
                    ConnectionState.ONLINE_DIRECT -> "设备直连"
                    ConnectionState.ONLINE_RELAY -> "Relay 中转"
                    ConnectionState.OFFLINE -> "离线"
                    ConnectionState.UNKNOWN, null -> "未知（等待首次状态）"
                },
                detail = "Relay 中转可用不等于手机可执行命令；离线时停止/撤销等操作无法确认送达。",
            )
            CapabilityRow(
                title = "插件连接",
                value = if (diagnostics.pluginConnected) "已连接" else "未连接",
                detail = if (diagnostics.pluginConnected) "与 AstrBot 插件的通道正常。"
                else "聊天、提醒和命令下发均不可用，请先检查服务器连接。",
            )
            CapabilityRow(
                title = "Shizuku 执行能力",
                value = if (diagnostics.shizukuReady) "就绪" else "未就绪",
                detail = if (diagnostics.shizukuReady) "可执行点击、输入、暂停应用等操作。"
                else "未就绪时读取屏幕文字、UI 自动化和应用限制都不可用。可在权限管理中处理。",
            )
            diagnostics.error?.takeIf { it.isNotBlank() }?.let {
                CapabilityRow(title = "最近错误", value = it, detail = null)
            }

            if (!BuildConfig.IS_BOT_DEVICE) {
            HorizontalDivider()
            Text("夜间自动守护", style = MaterialTheme.typography.titleMedium)
            // R05：运行状态与残留限制分开。when 只负责显示未知/开启/关闭；
            // lastError 与已暂停列表在分支外按数据是否存在显示，不随开关隐藏。
            val overviewLoaded = overview != null && overview != com.tauru.astrbotphoneagent.data.AutonomyOverview()
            val ng = overview?.nightGuard
            when {
                !overviewLoaded -> CapabilityRow(
                    title = "守护状态",
                    value = "未知",
                    detail = "尚未收到服务器状态，无法判断守护是否开启。请确认连接后下拉刷新。",
                )
                ng != null && ng.enabled -> CapabilityRow(
                    title = "守护状态",
                    value = when {
                        ng.dryRun -> "试运行（只警告，不暂停）"
                        ng.inWindow -> "窗口内值守中"
                        else -> "已开启（窗口外）"
                    },
                    detail = buildString {
                        append("时段 ${ng.window.ifBlank { "未配置" }}")
                        if (ng.phase.isNotBlank()) append(" · 阶段 ${ng.phase}")
                        if (ng.foregroundPackage.isNotBlank()) append(" · 前台 ${ng.foregroundPackage}")
                        if (ng.enforcementCount > 0) append(" · 已执行 ${ng.enforcementCount} 次")
                        append("\n本端持续上传前台应用心跳作为观察来源。快捷入口的「守夜」是立即限用 8 小时，与守护不是同一功能。")
                    },
                )
                else -> CapabilityRow(
                    title = "守护状态",
                    value = "未开启",
                    detail = "在服务器配置中开启后，将在设定时段观察前台应用，先警告再暂停。快捷入口的「守夜」是立即限用 8 小时，与守护不是同一功能。",
                )
            }
            // 关闭不代表残留已解除：lastError 与已暂停列表按数据存在与否显示。
            if (ng != null && ng.lastError.isNotBlank()) {
                CapabilityRow(title = "守护最近错误", value = ng.lastError, detail = null)
            }
            if (ng != null && ng.ownedSuspended.isNotEmpty()) {
                CapabilityRow(
                    title = "守护已暂停",
                    value = "${ng.ownedSuspended.size} 个应用",
                    detail = ng.ownedSuspended.joinToString("、"),
                )
            }

            overview?.let { ov ->
                if (ov.sleepModeActive) {
                    CapabilityRow(
                        title = "手动限用（守夜）",
                        value = "生效中",
                        detail = buildString {
                            ov.sleepModeUntil?.let { append("至 $it") }
                            if (ov.suspendedPackages.isNotEmpty()) {
                                if (isNotEmpty()) append(" · ")
                                append("已暂停 ${ov.suspendedPackages.size} 个应用")
                            }
                        },
                    )
                }
            }

            HorizontalDivider()
            Text("主动关怀", style = MaterialTheme.typography.titleMedium)
            val care = overview?.care
            when {
                care == null -> CapabilityRow(
                    title = "关怀状态",
                    value = "未知",
                    detail = "尚未收到服务端快照。需要 companion 插件与手机插件同时运行后才会出现。",
                )
                !care.autonomyEnabled -> CapabilityRow(
                    title = "关怀状态",
                    value = "未开启",
                    detail = "服务端未开启自主决策，或关系模式处于关闭状态。",
                )
                else -> CapabilityRow(
                    title = "关怀状态",
                    value = when (care.mode) {
                        "preview" -> "预览（不发送）"
                        "care_auto", "low_risk_auto" -> "发送模式"
                        else -> care.mode.ifBlank { "未知" }
                    },
                    detail = buildString {
                        append("安静时段 ${care.quietHours.ifBlank { "未配置" }}")
                        if (care.inQuietHours) append(" · 当前处于安静时段")
                        append(" · 今日已用 ${care.sentToday}/${care.dailyLimit}，剩 ${care.remaining}")
                        if (care.evalToday > 0) append(" · 今日评估 ${care.evalToday} 次")
                        if (care.lastSentAt.isNotBlank()) append("\n最近发送 ${care.lastSentAt}")
                    },
                )
            }
            care?.recent?.takeIf { it.isNotEmpty() }?.let { recent ->
                Text("最近决策", style = MaterialTheme.typography.labelMedium, color = muted)
                recent.take(5).forEach { item ->
                    CapabilityRow(
                        title = careOutcomeLabel(item.outcome),
                        value = careReasonLabel(item.reason),
                        detail = item.detail.takeIf { it.isNotBlank() },
                    )
                }
            }

            }

            val pendingApprovals = overview?.taskApprovals.orEmpty().filter {
                it.status == "pending" && it.expiresAt > System.currentTimeMillis() / 1000.0
            }
            if (pendingApprovals.isNotEmpty()) {
                HorizontalDivider()
                Text("待你决定", style = MaterialTheme.typography.titleMedium)
                pendingApprovals.forEach { approval ->
                    CapabilityRow(
                        title = approval.goal.ifBlank { approval.actions.joinToString("、") { taskActionLabel(it) } },
                        value = approvalCountdown(approval.expiresAt),
                        detail = approvalDetails(approval, overview?.devices.orEmpty()),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(enabled = !decisionBusy && approvalReviewable(approval), onClick = {
                            decisionBusy = true
                            scope.launch {
                                try { decisionNotice = appState.approveTask(approval.approvalId).message }
                                finally { decisionBusy = false }
                            }
                        }) { Text(if (decisionBusy) "正在提交…" else "允许这一次") }
                        TextButton(enabled = !decisionBusy, onClick = {
                            decisionBusy = true
                            scope.launch {
                                try { decisionNotice = appState.rejectTask(approval.approvalId, "用户在 App 里拒绝").message }
                                finally { decisionBusy = false }
                            }
                        }) { Text("拒绝") }
                    }
                }
                if (decisionNotice.isNotBlank()) {
                    Text(decisionNotice, style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
            if (decisionNotice.isNotBlank() && pendingApprovals.isEmpty()) Text(decisionNotice,
                style = MaterialTheme.typography.bodySmall, color = muted)

            val context = androidx.compose.ui.platform.LocalContext.current
            val canOverlay = com.tauru.astrbotphoneagent.logic.ApprovalOverlay.canDraw(context)
            val overlayError = com.tauru.astrbotphoneagent.logic.ApprovalOverlay.lastError
            val overlayAttempts = com.tauru.astrbotphoneagent.logic.ApprovalOverlay.attempts
            CapabilityRow(
                title = "悬浮弹窗",
                value = when {
                    !canOverlay -> "未开启"
                    overlayError.isNotBlank() -> "失败"
                    overlayAttempts == 0 -> "已开启 · 还没弹过"
                    else -> "已开启 · 弹出 $overlayAttempts 次"
                },
                detail = overlayError.ifBlank {
                    if (!canOverlay) "普通任务可用悬浮窗提示。界面任务需在 App 内核对完整范围后批准。" else "界面任务在 App 内确认；通知用于提醒你查看。"
                },
            )
            if (!canOverlay) {
                OutlinedButton(onClick = {
                    com.tauru.astrbotphoneagent.logic.ApprovalOverlay.requestPermission(context)
                }) { Text("去开启「显示在其他应用的上层」") }
            }

            HorizontalDivider()
            Text("最近任务", style = MaterialTheme.typography.titleMedium)
            val taskRuns = overview?.taskRuns.orEmpty()
            if (taskRuns.isEmpty()) {
                CapabilityRow(
                    title = "任务记录",
                    value = "暂无",
                    detail = "还没有执行过任务。试跑与真实执行都会记在这里。",
                )
            } else {
                taskRuns.take(3).forEach { taskRun ->
                    CapabilityRow(
                        title = taskRun.goal.ifBlank { "未命名任务" },
                        value = taskStatusLabel(taskRun),
                        detail = buildString {
                            if (taskRun.mode == "ui") {
                                val device = overview?.devices?.firstOrNull { it.deviceId == taskRun.deviceId }
                                append("界面任务 · ${device?.label ?: taskRun.deviceId} · ${taskRun.packageName}")
                                append("\n最多 ${taskRun.maxSteps} 步 · 模型评估 ${taskRun.modelCalls} 次")
                            }
                            if (taskRun.dryRun) append("试跑")
                            if (taskRun.executed > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("执行 ${taskRun.executed} · 核验通过 ${taskRun.verified}")
                            }
                            if (taskRun.failed > 0) append(" · 失败 ${taskRun.failed}")
                            if (taskRun.blocked > 0) append(" · 阻止 ${taskRun.blocked}")
                            if (taskRun.refused.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append("被拒：${taskRefusedLabel(taskRun.refused)}")
                            }
                            taskRun.steps.forEach { step -> append("\n${taskStepLabel(step)}") }
                            if (taskRun.lastError.isNotBlank()) append("\n${taskRefusedLabel(taskRun.lastError)}")
                        },
                    )
                    if (taskRun.status in setOf("planning", "running", "executing", "verifying", "waiting_approval")) {
                        TextButton(enabled = !decisionBusy, onClick = {
                            decisionBusy = true
                            scope.launch {
                                try { decisionNotice = appState.cancelServerTask(taskRun.runId).message }
                                finally { decisionBusy = false }
                            }
                        }) { Text("停止这项任务") }
                    }
                }
            }
            if (overview?.taskExecuteEnabled == false) {
                Text(
                    "真实执行当前关闭（服务端 enable_task_execute=false），这里只会有试跑记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }

            HorizontalDivider()
            Text("操作与审批说明", style = MaterialTheme.typography.titleMedium)
            CapabilityRow(
                title = "已提供的操作",
                value = "按授权执行",
                detail = "支持点击、输入、滑动、返回、打开或暂停应用等结构化动作。界面任务会根据当前页面选择下一步；实际可用范围以服务器开关、设备权限和本次审批为准。",
            )
            Text("目前通知会打开 App，随后可进入自主功能处理任务；界面任务的确认会展示完整范围。若页面未暴露可定位的控件、涉及关键操作或结果无法核验，任务会停止。",
                style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable
private fun CapabilityRow(title: String, value: String, detail: String?) {    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Text(
            value,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.primary,
        )
    }
}

private fun approvalReviewable(approval: TaskApproval): Boolean = approval.mode != "ui" ||
    (approval.goal.isNotBlank() && approval.deviceId.isNotBlank() && approval.packageName.isNotBlank() &&
        approval.deviceRole in setOf("personal", "bot") && approval.successTexts.size in 1..5 &&
        approval.allowedTexts.size <= 5 && (approval.allowedTexts + approval.successTexts).all { it.length in 1..200 && it.none(Char::isISOControl) } &&
        approval.completionMode in setOf("visible_text", "search_results") && approval.maxSteps in 1..12)

private fun approvalDetails(approval: TaskApproval, devices: List<com.tauru.astrbotphoneagent.data.RegisteredPhone>): String {
    if (approval.mode != "ui") return "批准后才执行；不点就自动放弃。"
    if (!approvalReviewable(approval)) return "任务范围不完整，暂不可批准。请刷新或重新创建任务。"
    val device = devices.firstOrNull { it.deviceId == approval.deviceId }
    return buildString {
        append("执行设备：${device?.label ?: approval.deviceId} · ${deviceRoleLabel(approval.deviceRole)}")
        append("\n目标 App：${approval.packageName}")
        append("\n允许输入：${approval.allowedTexts.joinToString("、").ifBlank { "不输入文字" }}")
        append("\n完成页面须同时出现：${approval.successTexts.joinToString("、")}")
        if (approval.completionMode == "search_results") append("；结果列表还须出现包含搜索词的标题")
        append("\n最多 ${approval.maxSteps} 步；只操作这一个 App。页面内容会交给你配置的模型用于规划。")
        append("\n登录、支付、消息发布等关键步骤会暂停。允许本次任务不代表允许这些后续操作。")
    }
}

/** Plain-language labels for the care ledger; unknown codes are shown as-is. */
private fun careOutcomeLabel(outcome: String): String = when (outcome) {
    "sent" -> "已发送"
    "preview" -> "预览拦截"
    "no_action" -> "决定不打扰"
    "invalid" -> "输出不合法"
    "gated" -> "未通过门槛"
    "failed" -> "失败"
    else -> outcome.ifBlank { "未知" }
}

private fun careReasonLabel(reason: String): String = when (reason) {
    "sent" -> "已发出"
    "preview" -> "preview 模式"
    "no_action" -> "模型选择不打扰"
    "invalid_decision" -> "解析失败"
    "quiet_hours" -> "安静时段"
    "cooldown" -> "冷却中"
    "daily_limit" -> "今日额度用完"
    "eval_limit", "eval_throttled" -> "评估受限"
    "in_flight" -> "已有评估进行中"
    "budget_unavailable" -> "预算不可写"
    "authorization_revoked" -> "授权已撤销"
    else -> reason
}

private fun taskActionLabel(action: String): String = when (action) {
    "open_app" -> "打开应用"
    "close_app" -> "关闭应用"
    "suspend_app" -> "暂停应用"
    "unsuspend_app" -> "恢复应用"
    "home" -> "回到桌面"
    "back" -> "返回"
    "lock_screen" -> "锁屏"
    "tap" -> "点击"
    "input_text" -> "输入文字"
    "ui_snapshot" -> "观察当前页面"
    "ui_action" -> "在目标 App 内操作"
    "ui_tap" -> "点击页面控件"
    "ui_input" -> "输入批准的文字"
    "ui_scroll" -> "滚动页面"
    else -> action
}

private fun approvalCountdown(expiresAt: Double): String {
    if (expiresAt <= 0.0) return "等待你的决定"
    val left = (expiresAt - System.currentTimeMillis() / 1000.0).toInt()
    return when {
        left <= 0 -> "已过期"
        left < 60 -> "${left} 秒后过期"
        else -> "${left / 60} 分钟后过期"
    }
}

private fun taskStatusLabel(run: com.tauru.astrbotphoneagent.data.TaskRunSummary): String = when (run.status) {    "done" -> "完成"
    "failed" -> if (run.refused.isNotBlank()) "被拒绝" else "失败"
    "cancelled" -> "已取消"
    "running", "executing", "verifying" -> "执行中"
    "planning" -> "规划中"
    "waiting_approval" -> if (run.lastError.isBlank()) "等待确认" else "需要你处理"
    "needs_user", "paused" -> "需要你处理"
    else -> run.status.ifBlank { "未知" }
}

private fun taskRefusedLabel(reason: String): String = when (reason) {
    "user_active" -> "你当时正在用手机"
    "stale_observation" -> "观察过期"
    "snapshot_stale", "stale_snapshot" -> "页面已变化，需要重新观察"
    "sensitive_action", "sensitive_screen", "needs_user" -> "遇到关键步骤，请由你处理"
    "step_limit", "budget_exhausted" -> "已达到本次步骤上限"
    "no_progress", "repeated_state" -> "页面没有进展，任务已停止"
    "completion_not_observed" -> "尚未观察到完成条件"
    "device_changed" -> "执行设备状态已变化"
    else -> reason
}

private fun taskStepLabel(step: com.tauru.astrbotphoneagent.data.TaskRunStep): String {
    val action = when (step.action) {
        "observe" -> "观察"
        "home" -> "回桌面"
        "back" -> "返回"
        "open_app" -> "打开应用"
        "close_app" -> "关闭应用"
        "suspend_app" -> "暂停应用"
        "unsuspend_app" -> "恢复应用"
        "screenshot" -> "截图"
        else -> taskActionLabel(step.action)
    }
    val status = when (step.status) {
        "done" -> "通过"
        "failed" -> "失败"
        "skipped" -> "跳过"
        "executed_unverified" -> "已执行·未核验"
        "unknown" -> "结果未知"
        else -> step.status
    }
    val suffix = if (step.status == "done") "" else "（${taskRefusedLabel(step.reason.ifBlank { step.status })}）"
    return "· $action $status$suffix"
}
