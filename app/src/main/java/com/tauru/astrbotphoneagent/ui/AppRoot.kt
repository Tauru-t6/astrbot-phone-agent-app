package com.tauru.astrbotphoneagent.ui

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.tauru.astrbotphoneagent.data.AppState
import com.tauru.astrbotphoneagent.ui.components.BackdropMood
import com.tauru.astrbotphoneagent.ui.components.GlassCard
import com.tauru.astrbotphoneagent.ui.components.WaterWaveBackground
import com.tauru.astrbotphoneagent.ui.screens.*
import com.tauru.astrbotphoneagent.ui.theme.PhoneAgentTheme
import kotlinx.coroutines.launch

private data class Tab(val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("聊天", Icons.Filled.ChatBubble),
    Tab("发现", Icons.Filled.Explore),
    Tab("生活", Icons.Filled.GridView),
    Tab("市集", Icons.Filled.Storefront),
    Tab("纸笺", Icons.AutoMirrored.Filled.Article),
)

@Composable
fun AppRoot(appState: AppState) {
    PhoneAgentTheme { PhoneAgentContent(appState) }
}

@Composable
internal fun PhoneAgentContent(appState: AppState, initialTab: Int = 2) {
    var selected by rememberSaveable { mutableIntStateOf(initialTab) }
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
    val mood = when (selected) {
        4 -> BackdropMood.Paper
        1, 3 -> BackdropMood.Mist
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
                when (selected) {
                    0 -> ChatScreen(appState)
                    1 -> TimelineScreen(appState, onOpenChat = { selected = 0 })
                    2 -> HomeScreen(appState,
                        onQuickAction = { id -> scope.launch { appState.runQuickAction(id) } },
                        onOpenChat = { selected = 0 })
                    3 -> MarketScreen(appState)
                    else -> SettingsScreen(appState)
                }
            }
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
