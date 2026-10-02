package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.LockPerson
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PrivacyTip
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tauru.astrbotphoneagent.data.AppDiagnostics
import com.tauru.astrbotphoneagent.data.AppState
import com.tauru.astrbotphoneagent.data.ConnectionState
import com.tauru.astrbotphoneagent.ui.components.ProfileAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(appState: AppState) {
    val companion by appState.companion.collectAsState(initial = null)
    val deviceStatus by appState.deviceStatus.collectAsState(initial = null)
    val timeline by appState.timeline.collectAsState(initial = emptyList())
    val tasks by appState.tasks.collectAsState(initial = emptyList())
    val settings by appState.connectionSettings.collectAsState(initial = null)
    val diagnostics by appState.diagnostics.collectAsState(initial = AppDiagnostics())
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.25f
    val page = if (dark) Color(0xFF252726) else Color(0xFFECECE5)
    val paper = if (dark) Color(0xFF343530) else Color(0xFFFFFDF3)
    val paperEdge = if (dark) Color(0xFF515249) else Color(0xFFE1DFD3)
    val muted = colors.onSurfaceVariant
    val connection = when (deviceStatus?.connection) {
        ConnectionState.ONLINE_DIRECT -> "设备直连"
        ConnectionState.ONLINE_RELAY -> "Relay 中转连接"
        ConnectionState.OFFLINE -> "当前离线"
        ConnectionState.UNKNOWN, null -> "等待连接状态"
    }
    val connected = deviceStatus?.connection == ConnectionState.ONLINE_DIRECT ||
        deviceStatus?.connection == ConnectionState.ONLINE_RELAY
    val updatedAt = deviceStatus?.updatedAtEpochMs?.takeIf { it > 0L }
        ?.let { SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(it)) }

    Column(
        modifier = Modifier.fillMaxSize().background(page)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp).padding(top = 18.dp, bottom = 24.dp),
    ) {
        Text("纸笺", style = MaterialTheme.typography.displayLarge, color = colors.onBackground)
        Text(
            "把重要的连接，妥帖收藏。",
            modifier = Modifier.padding(top = 6.dp, bottom = 25.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )

        Column(
            modifier = Modifier.fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(8.dp), ambientColor = Color.Black.copy(alpha = 0.04f), spotColor = Color.Black.copy(alpha = 0.05f))
                .clip(RoundedCornerShape(8.dp))
                .background(paper)
                .border(1.dp, paperEdge, RoundedCornerShape(8.dp))
                .padding(horizontal = 23.dp, vertical = 25.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "A LITTLE NOTE",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Serif, letterSpacing = 2.sp),
                    color = muted.copy(alpha = 0.72f),
                )
                Text("关于陪伴", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(companion?.name.orEmpty(), companion?.avatarUri, Modifier.size(58.dp).clickable { panel = "profile" })
                Spacer(Modifier.width(15.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        companion?.name?.ifBlank { "未设置名称" } ?: "未设置名称",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium),
                        color = colors.onSurface,
                    )
                    Text(
                        "名字与头像 · 由你设置",
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
            }
            Text(
                companion?.personaSummary?.takeIf { it.isNotBlank() } ?: "暂时没有角色介绍。",
                modifier = Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp),
                color = colors.onSurface,
            )

            PaperDivider(paperEdge, Modifier.padding(top = 24.dp, bottom = 20.dp))
            PaperSectionHeading("01", "连接")
            PaperInfoRow(
                icon = Icons.Outlined.Hub,
                title = "服务器连接",
                detail = updatedAt?.let { "$connection\n更新于 $it" } ?: connection,
                state = "配置 ›",
                stateColor = if (connected) colors.primary else muted,
                onClick = { panel = "connection" },
            )
            ConnectionDiagnostics(diagnostics)
            PaperInfoRow(
                icon = Icons.Outlined.LockPerson,
                title = "权限管理",
                detail = "管理 Shizuku、使用情况访问和通知权限。",
                state = "打开 ›",
                onClick = { panel = "permissions" },
            )

            PaperDivider(paperEdge, Modifier.padding(top = 4.dp, bottom = 20.dp))
            PaperSectionHeading("02", "数据与角色")
            PaperInfoRow(
                icon = Icons.Outlined.PrivacyTip,
                title = "手机任务记录",
                detail = "查看操作进度、执行结果和失败原因。",
                state = "查看 ›",
                onClick = { panel = "tasks" },
            )
            PaperInfoRow(
                icon = Icons.Outlined.Person,
                title = "名字与头像",
                detail = "选择本地头像，设置聊天中的显示名称。",
                state = "编辑 ›",
                onClick = { panel = "profile" },
            )
            PaperDivider(paperEdge, Modifier.padding(top = 6.dp, bottom = 18.dp))
            Text(
                "已载入 ${timeline.size} 条动态 · ${tasks.size} 项任务",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
        Text(
            "每一份连接，都值得认真对待。",
            modifier = Modifier.padding(top = 22.dp, start = 3.dp),
            style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.4.sp),
            color = muted.copy(alpha = 0.76f),
        )
    }
    when (panel) {
        "connection" -> settings?.let { ConnectionSettingsSheet(appState, it) { panel = null } }
        "permissions" -> PermissionSettingsSheet(appState) { panel = null }
        "tasks" -> LifeActivitySheet(appState, "tasks") { panel = null }
        "profile" -> companion?.let { ProfileSettingsSheet(appState, it) { panel = null } }
    }
}

@Composable
private fun PaperSectionHeading(number: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            number,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Serif, letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PaperInfoRow(
    icon: ImageVector,
    title: String,
    detail: String,
    state: String,
    stateColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(vertical = 17.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(18.dp), tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = colors.onSurface,
                )
                Text(state, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = stateColor)
            }
            Text(
                detail,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 21.sp),
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PaperDivider(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color.copy(alpha = 0.80f)))
}
