package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.data.*
import kotlinx.coroutines.launch
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionSettingsSheet(appState: AppState, settings: ConnectionSettings, onDismiss: () -> Unit) {
    var pluginUrl by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(settings.pluginUrl)) }
    var relayUrl by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(settings.relayUrl)) }
    // Credentials deliberately remain only in this composition, never in saved
    // state: a rotation or process death must drop whatever was typed.
    var pluginToken by remember { mutableStateOf(TextFieldValue()) }
    var sharedToken by remember { mutableStateOf(TextFieldValue()) }
    var relayToken by remember { mutableStateOf(TextFieldValue()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<ActionFeedback?>(null) }
    val diagnostics by appState.diagnostics.collectAsState(initial = AppDiagnostics())
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var relayExpanded by rememberSaveable { mutableStateOf(settings.relayUrl.isNotBlank()) }
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    var showPairing by remember { mutableStateOf(false) }
    if (showPairing) { PairingSheet(appState,onDismiss={showPairing=false},onPaired={showPairing=false;onDismiss()}); return }
    var discardDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { busy == null })
    val pluginError = connectionAddressError(pluginUrl.text)
    val relayError = connectionAddressError(relayUrl.text)
    val valid = pluginError == null && relayError == null
    val hasChanges = pluginUrl.text.trim().trimEnd('/') != settings.pluginUrl.trimEnd('/') ||
        relayUrl.text.trim().trimEnd('/') != settings.relayUrl.trimEnd('/') ||
        pluginToken.text.isNotBlank() || sharedToken.text.isNotBlank() || relayToken.text.isNotBlank()

    suspend fun saveDraft(): ActionFeedback {
        val result = appState.saveConnectionSettings(ConnectionUpdate(serverUrl = settings.serverUrl, pluginUrl = pluginUrl.text.trim(), relayUrl = relayUrl.text.trim(), chatUsername = settings.chatUsername, apiKey = "", pluginToken = pluginToken.text, sharedToken = sharedToken.text, relayToken = relayToken.text))
        if (result.success) {
            pluginToken = TextFieldValue(); sharedToken = TextFieldValue(); relayToken = TextFieldValue()
        }
        return result
    }

    fun perform(label: String, action: suspend () -> ActionFeedback) {
        if (busy != null) return
        focusManager.clearFocus()
        busy = label
        feedback = null
        scope.launch {
            try {
                feedback = uiActionFeedback(action)
            } finally { busy = null }
        }
    }

    fun close() { if (hasChanges) discardDialog = true else onDismiss() }
    ModalBottomSheet(
        onDismissRequest = { if (busy == null) close() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.96f).imePadding().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("服务器连接", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = { close() }, enabled = busy == null) { Icon(Icons.Outlined.Close, "关闭服务器连接") }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("连接 AstrBot，再按需要设置手机控制与中继。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick={showPairing=true},enabled=busy==null&&!hasChanges,modifier=Modifier.fillMaxWidth()) { Text("扫描或粘贴配对码") }
                FormSection("AstrBot 服务")
                ConnectionAddressField("插件地址", pluginUrl, { pluginUrl = it; feedback = null }, pluginError, busy == null,
                    "http://服务器:6185/api/plug/astrbot_plugin_phone_agent", "填入完整的 Phone Control 插件地址。")
                SecretField("插件 Token", pluginToken, { pluginToken = it; feedback = null }, settings.pluginTokenConfigured, busy == null,
                    "配对后使用独立设备凭据；手动连接可使用面板 JWT。不要带 Bearer 前缀。", "已设置 · 留空保留")
                FormSection("手机控制")
                SecretField("手机控制 Token", sharedToken, { sharedToken = it; feedback = null }, settings.sharedTokenConfigured, busy == null,
                    "与插件的 app_shared_token 一致，用于接收手机指令。", "已设置 · 留空保留")
                ExpandableHeading("Relay 中继 · 可选", if (settings.relayUrl.isBlank()) "未配置" else "已配置", relayExpanded, busy == null) { relayExpanded = !relayExpanded }
                if (relayExpanded) {
                    Text("直连不可用时，通过中继接收指令。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ConnectionAddressField("Relay 地址", relayUrl, { relayUrl = it; feedback = null }, relayError, busy == null,
                        "https://relay.example.com", "留空则不使用中继。")
                    SecretField("Relay Token", relayToken, { relayToken = it; feedback = null }, settings.relayTokenConfigured, busy == null,
                        "与中继服务及插件的 relay_token 一致。", "已设置 · 留空保留")
                }
                ExpandableHeading("连接诊断", if (diagnostics.pluginConnected) "插件已连接" else "待连接", diagnosticsExpanded, busy == null) { diagnosticsExpanded = !diagnosticsExpanded }
                if (diagnosticsExpanded) {
                    ConnectionDiagnostics(diagnostics, compact = true)
                    Text("设备注册：${if(diagnostics.registered)"已注册" else "未确认"} · 输入法：${if(diagnostics.inputMethodEnabled)"已启用" else "未启用"}",style=MaterialTheme.typography.bodySmall)
                    Text("Relay：${if(diagnostics.relayReachable)"可达" else if(settings.relayUrl.isBlank())"未配置" else "尚未连通"}",style=MaterialTheme.typography.bodySmall)
                    if(diagnostics.checkedAtEpochMs>0) Text("最近自检：${relativeTime(diagnostics.checkedAtEpochMs)}",style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={perform("自检中"){appState.runConnectionCheck()}},enabled=busy==null&&!hasChanges) { Text("重新注册并检查设备") }
                    Text(when {
                        settings.relayUrl.isBlank() -> "Relay 未配置"
                        !settings.relayTokenConfigured -> "Relay 地址已保存，令牌待填写"
                        else -> "Relay 已配置；下方测试仅验证 AstrBot 插件连接。"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

            }
            feedback?.let {
                Box(Modifier.fillMaxWidth().heightIn(max = 88.dp).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) { FeedbackNotice(it) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(
                onClick = { perform("测试中") {
                    val saved = if (hasChanges) saveDraft() else ActionFeedback(true, "配置已保存")
                    if (saved.success) appState.testConnection() else saved
                } },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp),
                enabled = valid && busy == null,
            ) { Text(if (busy == "测试中") "正在测试…" else if (hasChanges) "保存并测试插件连接" else "测试插件连接") }
            TextButton(onClick = { perform("保存中") { saveDraft() } }, enabled = valid && hasChanges && busy == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (busy == "保存中") "正在保存…" else "仅保存配置")
            }
        }
    }
    if (discardDialog) AlertDialog(
        onDismissRequest = { discardDialog = false; scope.launch { sheetState.show() } },
        title = { Text("放弃本次修改？") },
        text = { Text("尚未保存的地址和凭据将被清除，已保存的配置不受影响。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discardDialog = false; scope.launch { sheetState.show() } }) { Text("继续编辑") } },
    )
}

@Composable
private fun ExpandableHeading(title: String, summary: String, expanded: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceVariant.copy(alpha = .5f)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起$title" else "展开$title")
    }
}

/* ---------- shared form pieces ---------- */

@Composable
private fun FormSection(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        )
    }
}

@Composable
private fun ConnectionAddressField(
    label: String,
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    error: String?,
    enabled: Boolean,
    placeholder: String,
    supporting: String,
) {
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        isError = error != null,
        supportingText = {
            Text(error ?: supporting, style = MaterialTheme.typography.bodySmall)
        },
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun SecretField(
    label: String,
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    configured: Boolean,
    enabled: Boolean,
    supporting: String,
    placeholderHold: String,
) {
    var revealed by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(if (configured) placeholderHold else "尚未设置") },
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }, enabled = enabled && value.text.isNotEmpty()) {
                Icon(
                    imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (revealed) "隐藏$label" else "显示$label",
                    tint = colors.onSurfaceVariant,
                )
            }
        },
        leadingIcon = if (configured) {
            {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = "已设置",
                    tint = colors.primary.copy(alpha = .8f),
                    modifier = Modifier.size(18.dp),
                )
            }
        } else null,
        supportingText = { Text(supporting, style = MaterialTheme.typography.bodySmall) },
        shape = RoundedCornerShape(14.dp),
    )
}

/**
 * Per-layer status. Each line reports one dependency independently so a failure
 * can be attributed to the address/plugin auth, Relay, or Shizuku — never
 * collapsed into a single unreadable line.
 */
@Composable
internal fun ConnectionDiagnostics(diagnostics: AppDiagnostics, compact: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = colors.primary.copy(alpha = .06f)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                "设备与权限",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            DiagnosticsRow("插件", diagnostics.pluginConnected, if (diagnostics.pluginConnected) "已连接" else "未连接")
            DiagnosticsRow(
                "Shizuku",
                diagnostics.shizukuReady,
                if (diagnostics.shizukuReady) "可用" else diagnostics.shizukuDetail.ifBlank { "未就绪" },
            )
            DiagnosticsRow(
                "使用情况访问",
                diagnostics.usageAccess,
                if (diagnostics.usageAccess) "已授权" else "未授权 · 暂无使用统计",
            )
            DiagnosticsRow(
                "最近同步",
                diagnostics.lastSyncAtEpochMs > 0L,
                if (diagnostics.lastSyncAtEpochMs > 0L) formatActivityTime(diagnostics.lastSyncAtEpochMs) else "尚未同步",
            )
            diagnostics.error?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
            if (compact) return@Column
            Text(
                "手机控制需要 Shizuku；使用统计需要使用情况访问权限。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiagnosticsRow(label: String, ok: Boolean, detail: String) {
    val colors = MaterialTheme.colorScheme
    val tint = if (ok) colors.primary.copy(alpha = .85f) else colors.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(tint))
        Text(
            label,
            modifier = Modifier.weight(.42f),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        Text(
            detail,
            modifier = Modifier.weight(.58f),
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

private fun connectionAddressError(value: String): String? {
    if (value.isBlank()) return null
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return "地址格式不正确"
    val schemes = setOf("http", "https")
    if (uri.scheme?.lowercase() !in schemes || uri.host.isNullOrBlank()) return "请输入以 http:// 或 https:// 开头的完整地址"
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return "地址中不要包含账号、密码、查询参数或 # 片段"
    if (uri.port > 65535 || uri.port == 0) return "端口须为 1–65535"
    return null
}
