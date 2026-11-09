package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.BuildConfig
import com.tauru.astrbotphoneagent.data.*
import com.tauru.astrbotphoneagent.ui.components.PageHeading
import com.tauru.astrbotphoneagent.ui.components.ProfileAvatar
import com.tauru.astrbotphoneagent.ui.components.StatusLabel

@Composable
fun SettingsScreen(appState: AppState) {
    val companion by appState.companion.collectAsState(initial = null)
    val tasks by appState.tasks.collectAsState(initial = emptyList())
    val settings by appState.connectionSettings.collectAsState(initial = null)
    val diagnostics by appState.diagnostics.collectAsState(initial = null)
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < .25f
    val paper = if (dark) Color(0xFF343A38) else Color(0xFFFFFDF5)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PageHeading("我的", "连接、权限，以及你的偏好")
        Surface(shape = RoundedCornerShape(24.dp), color = paper, contentColor = colors.onSurface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.clip(RoundedCornerShape(24.dp)).clickable(enabled = companion != null, role = Role.Button) { panel = "profile" }.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ProfileAvatar(companion?.name.orEmpty(), companion?.avatarUri, Modifier.size(52.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(companion?.name?.takeIf { it.isNotBlank() } ?: "你的陪伴", style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("编辑名字与头像", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = colors.onSurfaceVariant)
                }
                companion?.personaSummary?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        SettingsGroup("连接与设备") {
            SettingsRow(Icons.Outlined.Hub, "服务器连接", "管理服务器地址与连接凭据", enabled = settings != null, onClick = { panel = "connection" }) {
                StatusLabel(if (diagnostics?.pluginConnected == true) "已连接" else "待连接", attention = diagnostics?.error != null)
            }
            HorizontalDivider(Modifier.padding(start = 58.dp), color = colors.outlineVariant.copy(alpha = .5f))
            SettingsRow(Icons.Outlined.VerifiedUser, "权限管理", when {
                diagnostics == null -> "正在读取权限状态"
                diagnostics?.shizukuReady != true -> "Shizuku ${diagnostics?.shizukuDetail?.ifBlank { "待就绪" }}"
                diagnostics?.usageAccess != true -> "使用情况访问待开启"
                else -> "手机控制与使用统计已就绪"
            }, onClick = { panel = "permissions" })
        }
        SettingsGroup("日常与偏好") {
            SettingsRow(Icons.Outlined.TaskAlt, "手机任务记录", "${tasks.size} 条记录 · 查看进度与结果", onClick = { panel = "tasks" })
            HorizontalDivider(Modifier.padding(start = 58.dp), color = colors.outlineVariant.copy(alpha = .5f))
            SettingsRow(Icons.Outlined.PrivacyTip, "隐私与自动化", "通知转发、操作确认与授权", onClick = { panel = "automation" })
            HorizontalDivider(Modifier.padding(start = 58.dp), color = colors.outlineVariant.copy(alpha = .5f))
            SettingsRow(Icons.Outlined.AutoAwesome, "自主功能", "夜间守护与主动关怀", onClick = { panel = "autonomy" })
        }
        Text("Phone Agent  ·  ${BuildConfig.VERSION_NAME}", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
    when (panel) {
        "connection" -> settings?.let { ConnectionSettingsSheet(appState, it) { panel = null } }
        "permissions" -> PermissionSettingsSheet(appState) { panel = null }
        "tasks" -> LifeActivitySheet(appState, "tasks") { panel = null }
        "profile" -> companion?.let { ProfileSettingsSheet(appState, it) { panel = null } }
        "automation" -> AutomationSettingsSheet(appState) { panel = null }
        "autonomy" -> AutonomySheet(appState) { panel = null }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .85f), contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, detail: String, enabled: Boolean = true, onClick: () -> Unit, extra: @Composable (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 76.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(32.dp).background(colors.primary.copy(alpha = .08f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = colors.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            extra?.invoke()
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
    }
}
