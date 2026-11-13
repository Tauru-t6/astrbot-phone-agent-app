package com.tauru.astrbotphoneagent.ui.screens

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.tauru.astrbotphoneagent.HealthPrivacyActivity
import com.tauru.astrbotphoneagent.data.*
import com.tauru.astrbotphoneagent.logic.HealthConnectReader
import com.tauru.astrbotphoneagent.logic.HealthSource
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HealthConnectSheet(appState: AppState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by appState.healthConnect.collectAsState(initial = HealthConnectState())
    val scope = rememberCoroutineScope()
    var feedback by remember { mutableStateOf<ActionFeedback?>(null) }
    var changing by remember { mutableStateOf(false) }
    var sourceDraft by rememberSaveable(state.originPackage) { mutableStateOf(state.originPackage) }
    val names = mapOf("steps" to "步数", "sleep" to "睡眠", "heart_rate" to "心率", "oxygen_saturation" to "血氧", "calories" to "活动热量")
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch {
            appState.refreshHealthConnect()
            feedback = if (state.enabled) appState.syncHealthConnect() else ActionFeedback(true, "授权状态已更新，可按需要开启同步")
        }
    }
    DisposableEffect(context, appState) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { appState.refreshHealthConnect() }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(appState) { appState.refreshHealthConnect() }
    fun open(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: Exception) { feedback = ActionFeedback(false, "无法打开，请在系统设置中搜索 Health Connect") }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Health Connect", style = MaterialTheme.typography.headlineSmall)
            Text("选择一个写入 Health Connect 的健康 App。Phone Control 只读取该来源最近 7 天的按日摘要。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (state.available) "Health Connect 已就绪" else "Health Connect 不可用", style = MaterialTheme.typography.titleMedium)
            Text("当前来源：${HealthSource.label(state.originPackage)}", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = sourceDraft,
                onValueChange = { sourceDraft = it.take(255) },
                label = { Text("健康 App 包名") },
                supportingText = { Text("默认：com.mi.health。仅选一个来源；切换会先清除旧快照。") },
                singleLine = true,
                enabled = !changing && !state.syncing,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    changing = true
                    scope.launch { try { feedback = appState.setHealthConnectSource(sourceDraft) } finally { changing = false } }
                },
                enabled = !changing && !state.syncing && sourceDraft.trim() != state.originPackage,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("应用健康来源") }
            Text(if (state.permissionsKnown) "已授权：" + state.grantedTypes.mapNotNull(names::get).joinToString("、").ifBlank { "尚未授权" }
                else "健康读取权限暂未确认")
            Button(onClick = { permissionLauncher.launch(HealthConnectReader.permissions) }, enabled = state.available && !state.syncing,
                modifier = Modifier.fillMaxWidth()) { Text("授权健康读取") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("同步健康摘要到服务器", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = state.enabled, enabled = !changing, onCheckedChange = { enabled ->
                    changing = true
                    scope.launch { try { feedback = appState.setHealthConnectEnabled(enabled) } finally { changing = false } }
                })
            }
            Text("开启后发送到已配置的 AstrBot，用于健康查询与关怀。约每 15 分钟尝试一次；系统拒绝后台读取时，请打开 App 手动同步。当前仅小米运动健康在此设备完成验证。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.latestDate.isNotBlank()) {
                Text("最近读到：${state.latestDate}", style = MaterialTheme.typography.titleSmall)
                val labels = mapOf("steps" to "步数", "sleep_minutes" to "睡眠分钟", "heart_rate_avg" to "平均心率", "spo2_avg" to "平均血氧 %", "active_calories_kcal" to "活动热量 kcal")
                labels.forEach { (key, label) -> state.latestMetrics[key]?.let { Text("$label：${String.format(Locale.getDefault(), "%.1f", it)}") } }
            }
            val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
            Text("最近成功上传：" + if (state.lastUploadAtEpochMs > 0) dateFormat.format(Date(state.lastUploadAtEpochMs)) else "尚未成功上传")
            if (state.serverSourceMode == "health_db") Text("服务器当前固定使用 SQLite；本次上传已保存，可在服务端选择 auto 或 health_connect。", style = MaterialTheme.typography.bodySmall)
            if(state.lastAttemptError!=null&&state.lastAttemptError!=state.error) Text(state.lastAttemptError.orEmpty(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            if(state.lastAttemptAtEpochMs>0) Text("最近同步尝试：${relativeTime(state.lastAttemptAtEpochMs)}",style=MaterialTheme.typography.bodySmall)
            if(state.lastUploadAtEpochMs>0&&System.currentTimeMillis()-state.lastUploadAtEpochMs>3*60*60_000)
                Text("服务器摘要已超过 3 小时，请打开来源健康 App 同步后再重试。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            state.error?.let { FeedbackNotice(ActionFeedback(false, it)) }
            feedback?.let { FeedbackNotice(it) }
            Button(onClick = { scope.launch { feedback = appState.syncHealthConnect() } }, enabled = state.enabled && !state.syncing && !changing,
                modifier = Modifier.fillMaxWidth()) { Text(if (state.syncing) "正在同步…" else "立即同步健康摘要") }
            if (state.clearPending) OutlinedButton(onClick = { scope.launch { feedback = appState.setHealthConnectEnabled(false) } }, modifier = Modifier.fillMaxWidth()) { Text("重试清除服务器快照") }
            OutlinedButton(onClick = { open(Intent(if (Build.VERSION.SDK_INT >= 34) "android.health.connect.action.HEALTH_HOME_SETTINGS" else "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")) }, modifier = Modifier.fillMaxWidth()) { Text("打开 Health Connect") }
            TextButton(onClick = { open(Intent(context, HealthPrivacyActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("健康数据使用说明") }
        }
    }
}
