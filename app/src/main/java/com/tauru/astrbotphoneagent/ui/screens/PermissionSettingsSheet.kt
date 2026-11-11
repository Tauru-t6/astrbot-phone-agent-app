package com.tauru.astrbotphoneagent.ui.screens

import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.BuildConfig
import com.tauru.astrbotphoneagent.data.ActionFeedback
import com.tauru.astrbotphoneagent.data.AppDiagnostics
import com.tauru.astrbotphoneagent.data.AppState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PermissionSettingsSheet(appState: AppState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val diagnostics by appState.diagnostics.collectAsState(initial = AppDiagnostics())
    val scope = rememberCoroutineScope()
    var feedback by remember { mutableStateOf<ActionFeedback?>(null) }
    var requesting by remember { mutableStateOf(false) }
    val requestCode = 4201
    DisposableEffect(context, appState) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { uiActionFeedback { appState.refreshStatus(); ActionFeedback(true, "") } }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    DisposableEffect(appState) {
        val listener = Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == requestCode) {
                requesting = false
                feedback = ActionFeedback(result == PackageManager.PERMISSION_GRANTED, if (result == PackageManager.PERMISSION_GRANTED) "Shizuku 权限已授予" else "未授予 Shizuku 权限")
                scope.launch { uiActionFeedback { appState.refreshStatus(); ActionFeedback(true, "") } }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }
    fun openSettings(intent: Intent) {
        feedback = try {
            context.startActivity(intent)
            null
        } catch (_: Exception) { ActionFeedback(false, "系统未提供这个设置入口，请在手机设置中手动打开。") }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("权限管理", style = MaterialTheme.typography.headlineSmall)
            Text("按需要开启手机能力，随时可在系统设置中关闭。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ConnectionDiagnostics(diagnostics)
            feedback?.let { FeedbackNotice(it) }
            Text("Shizuku", style = MaterialTheme.typography.titleMedium)
            Text("手机控制需要先安装并启动 Shizuku，再授予本应用权限。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                try {
                    when {
                        !Shizuku.pingBinder() -> feedback = ActionFeedback(false, "Shizuku 尚未启动，请先打开 Shizuku 完成启动。")
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> feedback = ActionFeedback(true, "Shizuku 权限已授予")
                        else -> { requesting = true; Shizuku.requestPermission(requestCode) }
                    }
                } catch (_: Exception) { requesting = false; feedback = ActionFeedback(false, "暂时无法申请 Shizuku 权限，请确认 Shizuku 已启动。") }
            }, enabled = !requesting, modifier = Modifier.fillMaxWidth()) { Text(if (requesting) "等待系统授权…" else "申请 Shizuku 权限") }
            OutlinedButton(onClick = {
                val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (intent == null) feedback = ActionFeedback(false, "没有找到 Shizuku，请先安装并启动。") else openSettings(intent)
            }, modifier = Modifier.fillMaxWidth()) { Text("打开 Shizuku") }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            PhoneControlInputMethodEntry()
            PermissionEntry("使用情况访问", "用于读取屏幕使用时间和应用使用统计。") {
                openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
            if (!BuildConfig.IS_BOT_DEVICE) PermissionEntry("通知读取权限", "把手机通知转发给 AstrBot，用于回答“有人找我吗”。需要单独授权。") {
                openSettings(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
            PermissionEntry("通知设置", if (BuildConfig.IS_BOT_DEVICE) "开启任务状态通知，并调整声音与锁屏显示。" else "开启提醒通知，并调整声音与锁屏显示。") {
                openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
            PermissionEntry("应用权限与设置", "管理位置、通知等系统权限。") {
                openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
        }
    }
}

@Composable
internal fun PhoneControlInputMethodEntry() {
    val context = LocalContext.current
    fun isEnabled(): Boolean = runCatching {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.enabledInputMethodList.any {
            it.serviceInfo.packageName == context.packageName &&
                it.serviceInfo.name == "com.tauru.astrbotphoneagent.logic.UiInputMethodService"
        }
    }.getOrDefault(false)
    var enabled by remember(context) { mutableStateOf(isEnabled()) }
    var feedback by remember { mutableStateOf<ActionFeedback?>(null) }
    DisposableEffect(context) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = isEnabled()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("任务文字输入 · ${if (enabled) "已启用" else "待启用"}", style = MaterialTheme.typography.titleSmall)
        Text("需要输入中文等文字时，请手动启用 Phone Control 输入法。它只处理任务已批准的文字，输入时临时切换，结束后恢复原输入法；不采集日常输入。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = {
            feedback = try {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                null
            } catch (_: Exception) { ActionFeedback(false, "无法打开输入法设置，请在系统设置中手动启用 Phone Control 输入法。") }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (enabled) "管理输入法" else "打开设置并手动启用") }
        feedback?.let { FeedbackNotice(it) }
    }
}

@Composable
private fun PermissionEntry(title: String, detail: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("打开$title") }
    }
}
