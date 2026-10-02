package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.data.*
import kotlinx.coroutines.launch
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionSettingsSheet(appState: AppState, settings: ConnectionSettings, onDismiss: () -> Unit) {
    var serverUrl by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(settings.serverUrl)) }
    var pluginUrl by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(settings.pluginUrl)) }
    var relayUrl by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(settings.relayUrl)) }
    var username by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(settings.chatUsername)) }
    // Credentials deliberately remain only in this composition, never in saved state.
    var apiKey by remember { mutableStateOf(TextFieldValue()) }
    var pluginToken by remember { mutableStateOf(TextFieldValue()) }
    var sharedToken by remember { mutableStateOf(TextFieldValue()) }
    var relayToken by remember { mutableStateOf(TextFieldValue()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<ActionFeedback?>(null) }
    val diagnostics by appState.diagnostics.collectAsState(initial = AppDiagnostics())
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { busy == null })
    val serverError = connectionAddressError(serverUrl.text)
    val pluginError = connectionAddressError(pluginUrl.text)
    val relayError = connectionAddressError(relayUrl.text)
    val usernameError = if (username.text.length > 128) "身份名称最多 128 个字符" else null
    val valid = serverError == null && pluginError == null && relayError == null && usernameError == null

    fun perform(label: String, action: suspend () -> ActionFeedback, afterSuccess: () -> Unit = {}) {
        if (busy != null) return
        busy = label
        feedback = null
        scope.launch {
            try {
                val result = uiActionFeedback(action)
                feedback = result
                if (result.success) afterSuccess()
            } finally { busy = null }
        }
    }

    ModalBottomSheet(onDismissRequest = { if (busy == null) onDismiss() }, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding().padding(horizontal = 22.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("服务器连接", style = MaterialTheme.typography.headlineSmall)
            Text("填入你自己的服务地址和连接凭据。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ConnectionDiagnostics(diagnostics)
            ConnectionAddressField("AstrBot 聊天地址", serverUrl, { serverUrl = it }, serverError, busy == null, "http://服务器:端口")
            ConnectionAddressField("Phone Agent 插件地址", pluginUrl, { pluginUrl = it }, pluginError, busy == null, "http://服务器:端口")
            ConnectionAddressField("Relay 地址 · 可选", relayUrl, { relayUrl = it }, relayError, busy == null, "留空则不使用中继")
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), enabled = busy == null, singleLine = true,
                label = { Text("聊天身份") }, placeholder = { Text("填写你在服务端使用的身份") }, isError = usernameError != null,
                supportingText = { Text(usernameError ?: "用于关联服务端对话和记忆，须与你的配置一致。") }, shape = RoundedCornerShape(15.dp))
            Text("连接凭据", style = MaterialTheme.typography.titleMedium)
            Text("仅填写需要替换的项；留空保留原凭据。已保存的内容不会回显。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SecretField("聊天 API Key", apiKey, { apiKey = it }, settings.apiKeyConfigured, busy == null)
            SecretField("插件 Token", pluginToken, { pluginToken = it }, settings.pluginTokenConfigured, busy == null)
            SecretField("手机控制 Token", sharedToken, { sharedToken = it }, settings.sharedTokenConfigured, busy == null)
            SecretField("Relay Token", relayToken, { relayToken = it }, settings.relayTokenConfigured, busy == null)
            }
            if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            feedback?.let { FeedbackNotice(it) }
            Button(onClick = {
                perform("保存中", { appState.saveConnectionSettings(ConnectionUpdate(serverUrl = serverUrl.text.trim(), pluginUrl = pluginUrl.text.trim(), relayUrl = relayUrl.text.trim(), chatUsername = username.text.trim(), apiKey = apiKey.text, pluginToken = pluginToken.text, sharedToken = sharedToken.text, relayToken = relayToken.text)) }) {
                    apiKey = TextFieldValue(); pluginToken = TextFieldValue(); sharedToken = TextFieldValue(); relayToken = TextFieldValue()
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = valid && busy == null) { Text(if (busy == "保存中") "保存中…" else "保存配置") }
            OutlinedButton(onClick = { perform("测试中", { appState.testConnection() }) }, modifier = Modifier.fillMaxWidth(), enabled = busy == null) { Text(if (busy == "测试中") "测试中…" else "测试已保存的连接") }
            Text("测试使用最近保存的配置；修改后请先保存。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ConnectionDiagnostics(diagnostics: AppDiagnostics) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .06f)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("插件 · ${if (diagnostics.pluginConnected) "已连接" else "未连接"}    Shizuku · ${if (diagnostics.shizukuReady) "可用" else "未就绪"}", style = MaterialTheme.typography.labelLarge)
            Text(if (diagnostics.lastSyncAtEpochMs > 0) "最近同步 ${formatActivityTime(diagnostics.lastSyncAtEpochMs)}" else "尚未完成同步", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            diagnostics.error?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ConnectionAddressField(label: String, value: TextFieldValue, onChange: (TextFieldValue) -> Unit, error: String?, enabled: Boolean, placeholder: String) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, label = { Text(label) }, placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = error != null,
        supportingText = if (error == null) null else ({ Text(error) }), shape = RoundedCornerShape(15.dp))
}

@Composable
private fun SecretField(label: String, value: TextFieldValue, onChange: (TextFieldValue) -> Unit, configured: Boolean, enabled: Boolean) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        label = { Text(label) }, placeholder = { Text(if (configured) "已设置 · 留空保留" else "尚未设置") },
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(15.dp))
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
