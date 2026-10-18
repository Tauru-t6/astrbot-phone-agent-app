package com.tauru.astrbotphoneagent.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.tauru.astrbotphoneagent.data.AppState
import com.tauru.astrbotphoneagent.data.TaskStatus
import com.tauru.astrbotphoneagent.ui.components.BackdropMood
import com.tauru.astrbotphoneagent.ui.components.GlassCard
import com.tauru.astrbotphoneagent.ui.components.WaterWaveBackground
import com.tauru.astrbotphoneagent.ui.screens.*
import com.tauru.astrbotphoneagent.ui.theme.PhoneAgentTheme
import kotlinx.coroutines.launch

private data class Tab(val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("动态", Icons.Filled.History),
    Tab("生活", Icons.Filled.GridView),
    Tab("我的", Icons.AutoMirrored.Filled.Article),
)

@Composable
fun AppRoot(appState: AppState) {
    PhoneAgentTheme { PhoneAgentContent(appState) }
}

@Composable
internal fun PhoneAgentContent(appState: AppState, initialTab: Int = 1) {
    var selected by rememberSaveable { mutableIntStateOf(initialTab) }
    // R03/v4 §5: hold one saveable slot per tab. Without this, `when (selected)`
    // removes the screen from composition and its rememberSaveable values are
    // forgotten, so an unsent chat draft was lost on every tab switch.
    val tabStates = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val dark = MaterialTheme.colorScheme.background.luminance() < .3f
    val view = LocalView.current
    val inspecting = LocalInspectionMode.current
    LaunchedEffect(view, dark, inspecting) {
        if (!inspecting) (view.context as? Activity)?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val keyboardOpen by remember(ime, density) {
        derivedStateOf { ime.getBottom(density) > 0 }
    }
    val pendingConfirmation by appState.pendingConfirmation.collectAsState(initial = null)
    val tasks by appState.tasks.collectAsState(initial = emptyList())
    val mood = when (selected) {
        2 -> BackdropMood.Paper
        0 -> BackdropMood.Mist
        else -> BackdropMood.Water
    }
    WaterWaveBackground(mood) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                // Let the message field stay directly above the keyboard.
                if (!keyboardOpen) {
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(36.dp),
                            alpha = .58f,
                            elevation = 7.dp,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(5.dp).selectableGroup(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                tabs.forEachIndexed { index, tab ->
                                    val active = selected == index
                                    val tint by animateColorAsState(
                                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        label = "tab-tint",
                                    )
                                    val fill by animateColorAsState(
                                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = .13f) else Color.Transparent,
                                        label = "tab-fill",
                                    )
                                    Column(
                                        Modifier.weight(1f).heightIn(min = 54.dp)
                                            .clip(RoundedCornerShape(28.dp))
                                            .background(fill)
                                            .selectable(active, role = Role.Tab, onClick = { selected = index })
                                            .padding(vertical = 7.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                                    ) {
                                        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(23.dp))
                                        Text(tab.label, color = tint,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                tabStates.SaveableStateProvider(selected) {
                    when (selected) {
                        0 -> TimelineScreen(appState)
                        1 -> HomeScreen(appState,
                            onQuickAction = { id -> scope.launch { appState.runQuickAction(id) } })
                        else -> SettingsScreen(appState)
                    }
                }
            }
        }
        // 截图快捷操作：GLM 已交付 AgentTask.screenshotResult（类型化附件，不截断）。
        // 任务成功后若携带截图则弹出预览。本地快捷截图路径没有上传/投递回执，
        // 本对话框只承诺「已取得」，不声称已发送到聊天。
        tasks.firstOrNull { it.status == TaskStatus.SUCCESS && it.screenshotResult?.isValid == true }
            ?.let { task ->
                var dismissed by rememberSaveable(task.id) { mutableStateOf(false) }
                if (!dismissed) ScreenshotPreviewDialog(task.title, task.screenshotResult!!) { dismissed = true }
            }
        pendingConfirmation?.let { task ->
            var submitting by remember(task.id) { mutableStateOf(false) }
            var confirmationError by remember(task.id) { mutableStateOf<String?>(null) }
            fun respond(approved: Boolean) {
                if (submitting) return
                submitting = true
                confirmationError = null
                scope.launch {
                    try {
                        val result = uiActionFeedback {
                            appState.confirmAction(task.id, approved)
                            com.tauru.astrbotphoneagent.data.ActionFeedback(true, "")
                        }
                        if (!result.success) confirmationError = result.message
                    } finally { submitting = false }
                }
            }
            AlertDialog(
                onDismissRequest = { respond(false) },
                title = { Text("确认这项手机操作？") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${task.title}\n\n确认后将执行这项手机操作。")
                    task.resultSummary?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    confirmationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } },
                confirmButton = { Button(enabled = !submitting, onClick = { respond(true) }) { Text("确认执行") } },
                dismissButton = { TextButton(enabled = !submitting, onClick = { respond(false) }) { Text("取消") } },
            )
        }
    }
}

/**
 * 截图预览：从 AgentTask.screenshotResult 解码并渲染。
 * 只承诺「截图已取得」。本地快捷截图路径没有上传/投递回执，
 * 不能声称已发送到聊天；附件上传契约（media_id / delivery_status）尚未由 GLM 交付。
 * internal 供 TimelineScreen 的任务详情重开复用。
 */
@Composable
internal fun ScreenshotPreviewDialog(
    title: String,
    result: com.tauru.astrbotphoneagent.data.ScreenshotResult,
    onDismiss: () -> Unit,
) {
    val bitmap = remember(result) {
        runCatching {
            val bytes = Base64.decode(result.dataBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "刚截取的屏幕画面",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Text(
                        "原始尺寸 ${result.width}×${result.height} · 截图已取得",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("截图数据无法解析或已过期。", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "发送到聊天需要服务端投递回执，当前版本无法确认；保存到系统相册暂未支持。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
