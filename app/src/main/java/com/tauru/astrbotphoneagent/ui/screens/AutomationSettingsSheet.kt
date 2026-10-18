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
import androidx.compose.material3.SheetValue
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
    // committed：业务层权威值；draft：本地编辑。保存中锁定冲突操作；
    // 保存失败回滚草稿，不能让开着的 UI 冒充已保存。
    val committed by appState.automationSettings.collectAsState(initial = AutomationSettings())
    var draft by remember(committed) { mutableStateOf(committed) }
    var saving by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<ActionFeedback?>(null) }
    val scope = rememberCoroutineScope()

    fun persist(next: AutomationSettings) {
        if (saving) return
        val previous = draft
        draft = next
        saving = true
        feedback = null
        scope.launch {
            try {
                val result = appState.saveAutomationSettings(next)
                if (!result.success) draft = previous
                feedback = result
            } finally {
                saving = false
            }
        }
    }

    ModalBottomSheet(
        // 保存中禁止下滑/返回关闭：onDismissRequest 门禁 + sheetState 拒绝 Hidden 迁移。
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { value ->
                !(saving && value == SheetValue.Hidden)
            },
        ),
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
            if (saving) {
                Text(
                    "正在保存…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("转发手机通知", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "把收到的通知同步给 AstrBot。开关打开不等于可用：还需授予通知读取权限（见权限管理），并由服务器开启通知工具。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = draft.notificationForwarding,
                    enabled = !saving,
                    onCheckedChange = { persist(draft.copy(notificationForwarding = it)) },
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
                        checked = key in draft.autoApproveActions,
                        enabled = !saving,
                        onCheckedChange = { checked ->
                            val next = if (checked) draft.autoApproveActions + key
                            else draft.autoApproveActions - key
                            persist(draft.copy(autoApproveActions = next))
                        },
                    )
                }
            }
        }
    }
}
