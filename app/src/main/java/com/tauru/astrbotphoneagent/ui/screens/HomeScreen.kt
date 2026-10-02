package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tauru.astrbotphoneagent.data.AppState
import com.tauru.astrbotphoneagent.data.ConnectionState
import com.tauru.astrbotphoneagent.data.DeviceStatus
import com.tauru.astrbotphoneagent.data.QuickAction
import com.tauru.astrbotphoneagent.ui.components.BatteryIcon
import com.tauru.astrbotphoneagent.ui.components.FocusIcon
import com.tauru.astrbotphoneagent.ui.components.GlassCard
import com.tauru.astrbotphoneagent.ui.components.LocationIcon
import com.tauru.astrbotphoneagent.ui.components.LockIcon
import com.tauru.astrbotphoneagent.ui.components.NightIcon
import com.tauru.astrbotphoneagent.ui.components.ScreenTimeIcon
import com.tauru.astrbotphoneagent.ui.components.ScreenshotIcon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** A quiet daily overview, using only the device values exposed by AppState. */
@Composable
fun HomeScreen(
    appState: AppState,
    onQuickAction: (String) -> Unit,
    onOpenChat: () -> Unit = {},
) {
    val status by appState.deviceStatus.collectAsState(initial = null)
    val actions by appState.quickActions.collectAsState(initial = emptyList())
    val tasks by appState.tasks.collectAsState(initial = emptyList())
    val reminders by appState.reminders.collectAsState(initial = emptyList())
    var activityPanel by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 22.dp, top = 18.dp, end = 22.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        HomeHeader(status?.connection ?: ConnectionState.UNKNOWN)
        LifeActivityEntrances(tasks.size, reminders.size) { activityPanel = it }
        ScreenTimeHero(status)
        DeviceDetails(status)
        ChatInvitation(onOpenChat)
        if (actions.isNotEmpty()) {
            QuickActionsSection(actions, onQuickAction)
        }
    }
    activityPanel?.let { panel ->
        LifeActivitySheet(appState, panel, onDismiss = { activityPanel = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeHeader(connection: ConnectionState) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "生活",
            style = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.1).sp,
            ),
            color = colors.onSurface,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = SimpleDateFormat("M月d日 · EEEE", Locale.CHINA).format(Date()),
                modifier = Modifier.padding(end = 12.dp, top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            ConnectionBadge(connection)
        }
    }
}

@Composable
private fun ConnectionBadge(state: ConnectionState) {
    val colors = MaterialTheme.colorScheme
    val dotColor = when (state) {
        ConnectionState.ONLINE_DIRECT, ConnectionState.ONLINE_RELAY -> Color(0xFF3FA98B)
        ConnectionState.UNKNOWN -> colors.onSurfaceVariant
        ConnectionState.OFFLINE -> colors.error
    }
    val label = when (state) {
        ConnectionState.ONLINE_DIRECT -> "直连在线"
        ConnectionState.ONLINE_RELAY -> "中继在线"
        ConnectionState.UNKNOWN -> "等待连接"
        ConnectionState.OFFLINE -> "设备离线"
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.surface.copy(alpha = 0.42f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun ScreenTimeHero(status: DeviceStatus?) {
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScreenTimeIcon(Modifier.size(24.dp), colors.primary)
                Text(
                    text = "今日屏幕时间",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth < 260.dp || fontScale > 1.2f) {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        ScreenTimeValue(status?.screenOnSecondsToday)
                        ScreenTimeRing(
                            seconds = status?.screenOnSecondsToday,
                            modifier = Modifier.size(110.dp).align(Alignment.End),
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.weight(1f)) { ScreenTimeValue(status?.screenOnSecondsToday) }
                        ScreenTimeRing(status?.screenOnSecondsToday, Modifier.size(110.dp))
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth().height(1.dp)
                    .background(colors.onSurface.copy(alpha = 0.07f)),
            )
            Text(
                text = when {
                    status == null -> "设备连接后，今天的使用情况会显示在这里。"
                    status.screenOnSecondsToday >= 4 * 3600L -> "今天用了挺久，留一点时间给屏幕之外。"
                    else -> "记录今天，也给生活留一点空白。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenTimeValue(seconds: Long?) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("累计使用", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        if (seconds == null) {
            Text("—", fontSize = 48.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
        } else {
            val safeSeconds = seconds.coerceAtLeast(0L)
            val hours = safeSeconds / 3600
            val minutes = (safeSeconds % 3600) / 60
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (hours > 0) TimePart(hours.toString(), "小时")
                TimePart(minutes.toString(), "分钟")
            }
        }
        Text(
            text = if (seconds == null) "等待同步" else "圆环表示占全天 24 小时的比例",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimePart(number: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = number,
            fontSize = 46.sp,
            lineHeight = 52.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = (-1.8).sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = unit,
            modifier = Modifier.padding(bottom = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScreenTimeRing(seconds: Long?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val progress = ((seconds ?: 0L).coerceAtLeast(0L) / 86_400f).coerceIn(0f, 1f)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val stroke = 8.dp.toPx()
            val diameter = min(size.width, size.height) - stroke
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            drawArc(
                color = colors.primary.copy(alpha = 0.12f),
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
        ScreenTimeIcon(Modifier.size(35.dp), colors.primary.copy(alpha = 0.8f))
    }
}

@Composable
private fun DeviceDetails(status: DeviceStatus?) {
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stackCards = maxWidth < 300.dp || fontScale > 1.2f
        val battery: @Composable (Modifier) -> Unit = { modifier ->
            CompactInfoCard(
                label = "设备电量",
                value = status?.batteryPercent?.let { "$it%" } ?: "待同步",
                detail = when {
                    status == null -> "等待设备连接"
                    status.charging -> "正在充电"
                    else -> "未在充电"
                },
                modifier = modifier,
            ) {
                BatteryIcon(
                    Modifier.size(26.dp),
                    colors.primary,
                    ((status?.batteryPercent ?: 0) / 100f).coerceIn(0f, 1f),
                )
            }
        }
        val foreground: @Composable (Modifier) -> Unit = { modifier ->
            CompactInfoCard(
                label = "正在使用",
                value = status?.foregroundAppLabel?.takeIf { it.isNotBlank() } ?: "暂无记录",
                detail = "前台应用",
                modifier = modifier,
            ) { ScreenTimeIcon(Modifier.size(26.dp), colors.primary) }
        }
        if (stackCards) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                battery(Modifier.fillMaxWidth())
                foreground(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                battery(Modifier.weight(1f))
                foreground(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CompactInfoCard(
    label: String,
    value: String,
    detail: String,
    modifier: Modifier,
    icon: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    GlassCard(modifier = modifier, shape = RoundedCornerShape(23.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                icon()
                Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChatInvitation(onOpenChat: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(shape)
            .background(colors.primary.copy(alpha = 0.11f))
            .border(1.dp, colors.primary.copy(alpha = 0.12f), shape)
            .clickable(onClick = onOpenChat)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.AutoMirrored.Outlined.Chat, null, tint = colors.primary, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("和搭子聊聊", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text("今天过得怎么样？", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = colors.primary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun QuickActionsSection(actions: List<QuickAction>, onQuickAction: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "快捷操作",
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < 300.dp || fontScale > 1.2f) 1 else 2
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.chunked(columns).forEach { actionRow ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
    val shape = RoundedCornerShape(21.dp)
    val tint = colors.primary
    GlassCard(
        modifier = modifier.clip(shape).clickable(onClick = onClick),
        shape = shape,
        alpha = if (action.active) 0.78f else 0.62f,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(35.dp).clip(CircleShape)
                    .background(colors.primary.copy(alpha = if (action.active) 0.2f else 0.09f)),
                contentAlignment = Alignment.Center,
            ) {
                val iconModifier = Modifier.size(25.dp)
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    text = if (action.dangerous) "需要确认" else action.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
