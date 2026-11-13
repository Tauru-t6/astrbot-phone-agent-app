package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tauru.astrbotphoneagent.BuildConfig
import com.tauru.astrbotphoneagent.data.*
import com.tauru.astrbotphoneagent.logic.HealthSource
import com.tauru.astrbotphoneagent.logic.healthIsFresh
import com.tauru.astrbotphoneagent.ui.components.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(appState: AppState, onQuickAction: (String) -> Unit) {
    val status by appState.deviceStatus.collectAsState(initial = null)
    val actions by appState.quickActions.collectAsState(initial = emptyList())
    val tasks by appState.tasks.collectAsState(initial = emptyList())
    val reminders by appState.reminders.collectAsState(initial = emptyList())
    val diagnostics by appState.diagnostics.collectAsState(initial = null)
    val settings by appState.connectionSettings.collectAsState(initial = null)
    val health by appState.healthConnect.collectAsState(initial = HealthConnectState())
    val autonomy by appState.autonomyOverview.collectAsState(initial = AutonomyOverview())
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    val uiScope=rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeading(if (BuildConfig.IS_BOT_DEVICE) "Phone Control Bot" else "Phone Control", SimpleDateFormat("M月d日 · EEEE", Locale.CHINA).format(Date())) {
            FilledTonalIconButton(onClick = { panel = "connection" }, enabled = settings != null, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                Icon(Icons.Outlined.Settings, contentDescription = "打开服务器连接")
            }
        }
        DeviceConnectionCard(status, diagnostics, settings) { panel = "connection" }
        if (BuildConfig.IS_BOT_DEVICE) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("让手机完成一件事", style = MaterialTheme.typography.titleLarge)
                    Text("给出目标，核对范围，再观察逐步操作与核验的结果。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val pending = autonomy.taskApprovals.count { it.status == "pending" && it.expiresAt > System.currentTimeMillis() / 1000.0 }
                    val running = autonomy.taskRuns.count { it.status in setOf("pending", "running", "executing", "verifying") }
                    if (pending > 0 || running > 0) Text("$pending 项待确认 · $running 项进行中", style = MaterialTheme.typography.labelLarge)
                    Button(onClick = { panel = "ui-task" }, modifier = Modifier.fillMaxWidth()) { Text("发起手机任务") }
                    OutlinedButton(onClick = { panel = "autonomy" }, modifier = Modifier.fillMaxWidth()) { Text("审批与执行进度") }
                }
            }
        }
        if (!BuildConfig.IS_BOT_DEVICE) ScreenTimeCard(status, diagnostics) { panel = "permissions" }
        if (!BuildConfig.IS_BOT_DEVICE && health.enabled) {
            GlassCard(Modifier.fillMaxWidth().clickable(role = Role.Button) { panel = "health" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("健康摘要", style = MaterialTheme.typography.titleMedium)
                    Text("${HealthSource.label(health.originPackage)} · ${health.latestDate.ifBlank { "暂无记录日期" }}", style = MaterialTheme.typography.bodySmall)
                    val fresh=healthIsFresh(health)
                    Text(if(health.lastUploadAtEpochMs==0L)"尚未成功上传" else "最近成功上传：${relativeTime(health.lastUploadAtEpochMs)}${if(!fresh)" · 当前数据待确认" else ""}",
                        style=MaterialTheme.typography.labelMedium,color=if(fresh)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    val pieces = listOfNotNull(
                        health.latestMetrics["steps"]?.let { "${it.toLong()} 步" },
                        health.latestMetrics["sleep_minutes"]?.let { "睡眠 ${String.format(Locale.getDefault(), "%.1f", it / 60)} 小时" },
                        health.latestMetrics["heart_rate_avg"]?.let { "心率均值 ${it.toInt()}" },
                    )
                    Text(health.error ?: health.lastAttemptError ?: pieces.joinToString(" · ").ifBlank { "等待健康数据，点击查看授权与同步" },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if(health.lastAttemptAtEpochMs>health.lastUploadAtEpochMs) Text("最近尝试：${relativeTime(health.lastAttemptAtEpochMs)}，尚未完成上传",style=MaterialTheme.typography.bodySmall)
                    TextButton(enabled=!health.syncing,onClick={uiScope.launch{appState.syncHealthConnect()}}) {
                        Text(if(health.syncing)"同步中…" else if(health.error!=null||!fresh)"重试同步" else "立即同步")
                    }
                }
            }
        }
        LifeActivityEntrances(tasks.size, reminders.size) { panel = it }
        val visibleActions = if (BuildConfig.IS_BOT_DEVICE) actions.filter { it.id !in setOf("focus_mode", "night_mode", "release_apps") } else actions
        if (visibleActions.isNotEmpty()) QuickActionsSection(visibleActions, onQuickAction)
        ForegroundRow(status)
    }
    when (panel) {
        "connection" -> settings?.let { ConnectionSettingsSheet(appState, it) { panel = null } }
        "permissions" -> PermissionSettingsSheet(appState) { panel = null }
        "health" -> if (!BuildConfig.IS_BOT_DEVICE) HealthConnectSheet(appState) { panel = null }
        "ui-task" -> UiTaskSheet(appState, onShowApprovals = { panel = "autonomy" }) { panel = null }
        "autonomy" -> AutonomySheet(appState) { panel = null }
        "tasks", "reminders" -> LifeActivitySheet(appState, panel!!) { panel = null }
    }
}

@Composable
private fun DeviceConnectionCard(
    status: DeviceStatus?, diagnostics: AppDiagnostics?, settings: ConnectionSettings?, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val needsSetup = settings != null && (settings.pluginUrl.isBlank() || !settings.pluginTokenConfigured)
    val title = when {
        needsSetup -> "连接你的 AstrBot"
        diagnostics?.pluginConnected == true -> "AstrBot 已连接"
        diagnostics == null -> "正在读取连接状态"
        else -> "AstrBot 尚未连接"
    }
    val detail = when {
        needsSetup -> "填入服务器地址，让手机与助手保持联系"
        diagnostics?.pluginConnected != true -> "查看连接设置与诊断"
        diagnostics.shizukuReady -> "手机控制已就绪"
        else -> "手机控制待就绪 · 前往查看权限"
    }
    val shape = RoundedCornerShape(22.dp)
    GlassCard(Modifier.fillMaxWidth().clip(shape).clickable(enabled = settings != null, role = Role.Button, onClick = onClick), shape = shape, alpha = .80f, elevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(colors.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Wifi, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .55f))
            FlowStatus(status, diagnostics)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowStatus(status: DeviceStatus?, diagnostics: AppDiagnostics?) {
    val colors = MaterialTheme.colorScheme
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BatteryIcon(Modifier.size(18.dp), colors.primary, ((status?.batteryPercent ?: 0) / 100f).coerceIn(0f, 1f))
            Text(status?.batteryPercent?.let { "$it%${if (status?.charging == true) " · 充电中" else ""}" } ?: "电量待同步", style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
        }
        Text(diagnostics?.lastSyncAtEpochMs?.takeIf { it > 0 }?.let { "同步于 ${relativeTime(it)}" } ?: "尚未同步", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
}

/** Unknown timestamps are handled by callers; no invented last-sync time. */
internal fun relativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - epochMs).coerceAtLeast(0L) / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 1440 -> "${minutes / 60} 小时前"
        else -> "${minutes / 1440} 天前"
    }
}

@Composable
private fun warningColor(): Color = MaterialTheme.colorScheme.error

/* ---------- screen time ---------- */

private enum class ScreenTimeAvailability { WAITING, NO_PERMISSION, READY }

@Composable
private fun ScreenTimeCard(status: DeviceStatus?, diagnostics: AppDiagnostics?, onPermissions: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    // Without usage-access the counter reads 0 no matter what the phone did, so
    // that state is reported as "未授权" rather than a truthful-looking zero.
    val availability = when {
        diagnostics == null -> ScreenTimeAvailability.WAITING
        !diagnostics.usageAccess -> ScreenTimeAvailability.NO_PERMISSION
        status == null -> ScreenTimeAvailability.WAITING
        else -> ScreenTimeAvailability.READY
    }
    val seconds = status?.screenOnSecondsToday?.takeIf { availability == ScreenTimeAvailability.READY }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScreenTimeIcon(Modifier.size(18.dp), colors.primary)
                Text(
                    text = "今日屏幕时间",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    text = when (availability) {
                        ScreenTimeAvailability.READY -> "全天 24 小时"
                        ScreenTimeAvailability.NO_PERMISSION -> "未授权"
                        ScreenTimeAvailability.WAITING -> "等待同步"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (availability) {
                        ScreenTimeAvailability.NO_PERMISSION -> warningColor()
                        else -> colors.onSurfaceVariant
                    },
                )
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val stack = maxWidth < 250.dp || fontScale > 1.4f
                val ring: @Composable (Modifier) -> Unit = { modifier ->
                    ScreenTimeRing(seconds, availability, modifier.size(if (stack) 74.dp else 80.dp))
                }
                val value: @Composable (Modifier) -> Unit = { modifier ->
                    ScreenTimeValue(seconds, availability, modifier)
                }
                if (stack) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        value(Modifier.fillMaxWidth())
                        ring(Modifier.align(Alignment.End))
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        value(Modifier.weight(1f))
                        ring(Modifier)
                    }
                }
            }
            Text(
                text = when (availability) {
                    ScreenTimeAvailability.READY -> when {
                        (seconds ?: 0L) >= 4 * 3600L -> "今天用了挺久，留一点时间给屏幕之外。"
                        else -> "记录今天，也给生活留一点空白。"
                    }
                    ScreenTimeAvailability.NO_PERMISSION -> "开启使用情况访问，了解今天的屏幕使用时间。"
                    ScreenTimeAvailability.WAITING -> "设备连接后，今天的使用情况会显示在这里。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (availability == ScreenTimeAvailability.NO_PERMISSION) warningColor() else colors.onSurfaceVariant,
            )
            if (availability == ScreenTimeAvailability.NO_PERMISSION) {
                TextButton(onClick = onPermissions, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("开启使用情况访问") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenTimeValue(seconds: Long?, availability: ScreenTimeAvailability, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (availability != ScreenTimeAvailability.READY) {
            Text(
                text = "—",
                fontSize = if (availability == ScreenTimeAvailability.NO_PERMISSION) 30.sp else 34.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Medium,
                color = if (availability == ScreenTimeAvailability.NO_PERMISSION) warningColor() else colors.onSurface,
            )
            Text(
                text = if (availability == ScreenTimeAvailability.NO_PERMISSION) "等待开启使用情况访问" else "等待同步",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            return@Column
        }
        val safeSeconds = (seconds ?: 0L).coerceAtLeast(0L)
        val hours = safeSeconds / 3600
        val minutes = (safeSeconds % 3600) / 60
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (hours > 0) TimePart(hours.toString(), "小时")
            TimePart(minutes.toString(), "分钟")
        }
    }
}

@Composable
private fun TimePart(number: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = number,
            fontSize = 34.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-1.2).sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = unit,
            modifier = Modifier.padding(bottom = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScreenTimeRing(seconds: Long?, availability: ScreenTimeAvailability, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val usable = availability == ScreenTimeAvailability.READY
    val progress = if (usable) ((seconds ?: 0L).coerceAtLeast(0L) / 86_400f).coerceIn(0f, 1f) else 0f
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val stroke = 6.dp.toPx()
            val diameter = min(size.width, size.height) - stroke
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            drawArc(
                color = colors.primary.copy(alpha = if (usable) 0.12f else 0.06f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (progress > 0f) {
                drawArc(
                    color = colors.primary,
                    startAngle = -90f,
                    sweepAngle = progress * 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        ScreenTimeIcon(
            Modifier.size(24.dp),
            if (usable) colors.primary.copy(alpha = .8f) else colors.onSurfaceVariant.copy(alpha = .5f),
        )
    }
}

/* ---------- foreground app ---------- */

@Composable
private fun ForegroundRow(status: DeviceStatus?) {
    val colors = MaterialTheme.colorScheme
    val app = status?.foregroundAppLabel?.takeIf { it.isNotBlank() }
        ?: status?.foregroundAppPackage?.takeIf { it.isNotBlank() }
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), alpha = .54f, elevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenTimeIcon(Modifier.size(18.dp), colors.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("正在使用", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(
                    text = app ?: when (status) {
                        null -> "等待设备连接"
                        else -> "没有前台应用记录"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (app == null) colors.onSurfaceVariant else colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/* ---------- quick actions ---------- */

@Composable
private fun QuickActionsSection(actions: List<QuickAction>, onQuickAction: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "快捷操作",
            modifier = Modifier.padding(start = 2.dp, top = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < 300.dp || fontScale > 1.2f) 1 else 2
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.chunked(columns).forEach { actionRow ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        actionRow.forEach { action ->
                            QuickActionCard(
                                action = action,
                                modifier = Modifier.weight(1f),
                                onClick = { onQuickAction(action.id) },
                            )
                        }
                        if (actionRow.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionCard(action: QuickAction, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val tint = colors.primary
    GlassCard(
        modifier = modifier.clip(shape).clickable(role = Role.Button, onClick = onClick),
        shape = shape,
        alpha = if (action.active) 0.78f else 0.62f,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                modifier = Modifier.size(30.dp).clip(CircleShape)
                    .background(colors.primary.copy(alpha = if (action.active) 0.2f else 0.09f)),
                contentAlignment = Alignment.Center,
            ) {
                val iconModifier = Modifier.size(19.dp)
                when (action.iconName) {
                    "self_improvement" -> FocusIcon(iconModifier, tint)
                    "screenshot" -> ScreenshotIcon(iconModifier, tint)
                    "bedtime" -> NightIcon(iconModifier, tint)
                    "lock" -> LockIcon(iconModifier, tint)
                    "place" -> LocationIcon(iconModifier, tint)
                    "battery_std" -> BatteryIcon(iconModifier, tint)
                    else -> FocusIcon(iconModifier, tint)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // One line only: the card is an entry point, not a status board.
                // Progress and outcome live in「我的 → 手机任务记录」.
                Text(
                    text = if (action.dangerous) "操作前需要确认" else action.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
