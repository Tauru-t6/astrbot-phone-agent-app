package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.data.ActionFeedback
import com.tauru.astrbotphoneagent.data.AppState
import com.tauru.astrbotphoneagent.data.AutomationSettings
import com.tauru.astrbotphoneagent.logic.DANGEROUS_ACTIONS
import kotlinx.coroutines.launch

// Which sensitive actions still prompt on the phone, and whether notifications
// are forwarded. Every toggle here narrows or widens the on-device confirmation
// gate, so the copy states the trade-off plainly.
private val ACTION_LABELS = listOf(
    Triple("screenshot", "截图", "把手机屏幕画面发给你。默认允许。"),
    Triple("screen_text", "读取屏幕文字", "读取当前界面上的文字内容。"),
    Triple("tap", "点击屏幕", "在屏幕上按坐标点击。"),
    Triple("long_press", "长按屏幕", "在屏幕上按住不放。"),
    Triple("swipe", "滑动屏幕", "在屏幕上滑动。"),
    Triple("scroll", "滚动屏幕", "上下左右滚动页面。"),
    Triple("input_text", "输入文字", "向输入框写入文字。"),
    Triple("key_event", "按键", "模拟返回、主页等按键。"),
    Triple("open_app", "打开应用", "启动指定应用。"),
    Triple("close_app", "关闭应用", "强制停止指定应用。"),
    Triple("suspend_app", "暂停应用", "暂时停用指定应用。"),
    Triple("send_notification", "发送通知", "在手机上弹出通知。"),
    Triple("location", "读取位置", "读取当前地理位置。"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutomationSettingsSheet(appState: AppState, onDismiss: () -> Unit) {
    val current by appState.automationSettings.collectAsState(initial = AutomationSettings())
    var local by remember(current) { mutableStateOf(current) }
    var feedback by remember { mutableStateOf<ActionFeedback?>(null) }
    val scope = rememberCoroutineScope()

    fun persist(next: AutomationSettings) {
        local = next
        scope.launch { feedback = appState.saveAutomationSettings(next) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("隐私与自动化", style = MaterialTheme.typography.headlineSmall)
            Text(
                "关闭某项确认后，手机不会再弹窗询问。仅在完全信任服务器时关闭。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            feedback?.let { FeedbackNotice(it) }

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("转发手机通知", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "把收到的通知同步给 AstrBot，需要单独授予通知读取权限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = local.notificationForwarding,
                    onCheckedChange = { persist(local.copy(notificationForwarding = it)) },
                )
            }

            HorizontalDivider()
            Text("免确认操作", style = MaterialTheme.typography.titleMedium)
            Text(
                "打开表示该操作不再需要手机确认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for ((key, label, detail) in ACTION_LABELS) {
                if (key !in DANGEROUS_ACTIONS) continue
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = key in local.autoApproveActions,
                        onCheckedChange = { checked ->
                            val next = if (checked) local.autoApproveActions + key
                            else local.autoApproveActions - key
                            persist(local.copy(autoApproveActions = next))
                        },
                    )
                }
            }
        }
    }
}
