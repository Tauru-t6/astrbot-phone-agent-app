package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.data.AppDiagnostics
import com.tauru.astrbotphoneagent.data.AppState
import com.tauru.astrbotphoneagent.data.ConnectionState

/**
 * 自主功能总览与诊断。
 *
 * 消费 GLM 已交付的真实契约：
 *  - 连接层（直连 / Relay / 离线 / 未知）
 *  - AppDiagnostics（插件连接、Shizuku、最近同步、待提醒、最近错误）
 *  - AutonomyOverview（夜间守护 phase/dry_run/window、手动睡眠、受限包列表、features）
 *
 * 仍未交付：CareSettings、ScopedGrant、DecisionRecord、TaskRun、ApprovalRequest、
 * ObservationRef。本页不编造这些状态，逐项标注「当前版本暂不支持」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutonomySheet(appState: AppState, onDismiss: () -> Unit) {
    val diagnostics by appState.diagnostics.collectAsState(initial = AppDiagnostics())
    val deviceStatus by appState.deviceStatus.collectAsState(initial = null)
    val overview by appState.autonomyOverview.collectAsState(initial = null)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

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
                "主动关怀与自主手机任务的业务契约由后端统一交付。以下展示当前真实可用的能力，未交付的能力明确标注，不以开关或预览冒充已生效。",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )

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
                        },
                    )
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
            Text("后续版本", style = MaterialTheme.typography.titleMedium)
            UnsupportedRow(
                title = "自主手机任务（第 4 段）",
                detail = "后台审批（系统通知与深链）、点击/输入类动作尚未开放。现阶段执行器只跑 open_app/home/back 这类可核验动作，且默认拒绝在用户正在使用手机时执行。",
            )
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

@Composable
private fun UnsupportedRow(title: String, detail: String) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(
                "当前版本暂不支持",
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Text(
            detail,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant.copy(alpha = 0.85f),
        )
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

private fun taskStatusLabel(run: com.tauru.astrbotphoneagent.data.TaskRunSummary): String = when (run.status) {
    "done" -> "完成"
    "failed" -> if (run.refused.isNotBlank()) "被拒绝" else "失败"
    "cancelled" -> "已取消"
    "running", "executing", "verifying" -> "执行中"
    "planning" -> "规划中"
    else -> run.status.ifBlank { "未知" }
}

private fun taskRefusedLabel(reason: String): String = when (reason) {
    "user_active" -> "你当时正在用手机"
    "stale_observation" -> "观察过期"
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
        else -> step.action
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
