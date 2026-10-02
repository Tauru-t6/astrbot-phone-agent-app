package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.data.*
import com.tauru.astrbotphoneagent.ui.components.GlassCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LifeActivityEntrances(taskCount: Int, reminderCount: Int, onOpen: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Triple("tasks", "任务", "$taskCount 条记录"), Triple("reminders", "提醒", "$reminderCount 项待提醒")).forEach { (key, title, detail) ->
            GlassCard(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable { onOpen(key) }, shape = RoundedCornerShape(20.dp), alpha = .54f, elevation = 2.dp) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (key == "tasks") Icons.Outlined.Checklist else Icons.Outlined.NotificationsNone, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LifeActivitySheet(appState: AppState, panel: String, onDismiss: () -> Unit) {
    val reminders by appState.reminders.collectAsState(initial = emptyList())
    val tasks by appState.tasks.collectAsState(initial = emptyList())
    val diagnostics by appState.diagnostics.collectAsState(initial = AppDiagnostics())
    var selected by rememberSaveable { mutableStateOf(panel) }
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var minutes by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("30")) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<ActionFeedback?>(null) }
    var pendingCancel by remember { mutableStateOf<PhoneReminder?>(null) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val delay = minutes.text.toIntOrNull()
    val valid = text.text.trim().isNotEmpty() && text.text.length <= 500 && delay != null && delay in 1..10080
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy })

    fun submit(action: suspend () -> ActionFeedback, onSuccess: () -> Unit = {}) {
        if (busy) return
        busy = true
        feedback = null
        scope.launch {
            try {
                val result = uiActionFeedback(action)
                feedback = result
                if (result.success) onSuccess()
            } finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState,
        containerColor = colors.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.90f).imePadding()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            feedback?.let { result -> Box(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) { FeedbackNotice(result) } }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("生活备忘", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { submit({ appState.refreshStatus(); ActionFeedback(true, "已请求刷新，最新状态会自动更新") }) }, enabled = !busy) { Text("刷新") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected == "tasks", { selected = "tasks"; feedback = null }, label = { Text("任务 · ${tasks.size}") }, enabled = !busy)
                    FilterChip(selected == "reminders", { selected = "reminders"; feedback = null }, label = { Text("提醒 · ${reminders.size}") }, enabled = !busy)
                }
            }
            if (!diagnostics.pluginConnected) item {
                Text("插件尚未连接；请先到纸笺配置并测试连接。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (selected == "reminders") {
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(colors.primary.copy(alpha = .06f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("给稍后的自己", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        OutlinedTextField(text, { if (it.text.length <= 500) text = it }, Modifier.fillMaxWidth(), enabled = !busy,
                            label = { Text("提醒内容") }, placeholder = { Text("例如：站起来，喝杯水") }, minLines = 2, maxLines = 4,
                            supportingText = { Text("${text.text.length}/500") }, shape = RoundedCornerShape(15.dp))
                        OutlinedTextField(minutes, { if (it.text.all(Char::isDigit) && it.text.length <= 5) minutes = it }, Modifier.fillMaxWidth(), enabled = !busy,
                            label = { Text("多少分钟后") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = minutes.text.isNotEmpty() && (delay == null || delay !in 1..10080),
                            supportingText = { Text("1–10080 分钟，最长 7 天") }, shape = RoundedCornerShape(15.dp))
                        Button(onClick = { if (valid) submit({ appState.createReminder(text.text.trim(), delay!!) }, { text = TextFieldValue() }) }, enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) { Text("新建提醒") }
                    }
                }
                item { Text("待提醒", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
                if (reminders.isEmpty()) item { EmptyActivity("还没有待提醒事项", "添加一条，把事情交给稍后的提醒。") }
                items(reminders.sortedBy { it.dueAtEpochMs }, key = { "reminder-${it.id}" }) { reminder ->
                    Surface(shape = RoundedCornerShape(18.dp), color = colors.surfaceVariant.copy(alpha = .42f)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(reminder.text, style = MaterialTheme.typography.bodyLarge)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(formatActivityTime(reminder.dueAtEpochMs), style = MaterialTheme.typography.labelMedium, color = colors.primary, modifier = Modifier.weight(1f))
                                TextButton(onClick = { pendingCancel = reminder }, enabled = !busy) { Text("取消提醒") }
                            }
                        }
                    }
                }
            } else {
                if (tasks.isEmpty()) item { EmptyActivity("还没有任务记录", "发起手机操作后，可以在这里查看进度和结果。") }
                items(tasks, key = { "task-${it.id}" }) { task ->
                    Surface(shape = RoundedCornerShape(18.dp), color = colors.surfaceVariant.copy(alpha = .42f)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(task.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text(task.status.displayLabel(), style = MaterialTheme.typography.labelSmall, color = if (task.status == TaskStatus.FAILED) colors.error else colors.primary)
                            }
                            Text(formatActivityTime(task.createdAtEpochMs), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            task.resultSummary?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            task.error?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
                            if (task.canRetry || task.canCancel) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                if (task.canRetry) TextButton(enabled = !busy, onClick = { submit({ appState.retryTask(task.id); ActionFeedback(true, "已提交重试，请查看任务状态") }) }) { Text("重试") }
                                if (task.canCancel) TextButton(enabled = !busy, onClick = { submit({ appState.cancelTask(task.id); ActionFeedback(true, "已提交取消，请查看任务状态") }) }) { Text("取消任务") }
                            }
                        }
                    }
                }
            }
        }
        }
    }
    pendingCancel?.let { reminder ->
        AlertDialog(onDismissRequest = { if (!busy) pendingCancel = null }, title = { Text("取消这条提醒？") }, text = { Text("${reminder.text}\n\n${formatActivityTime(reminder.dueAtEpochMs)}") },
            confirmButton = { TextButton(enabled = !busy, onClick = { pendingCancel = null; submit({ appState.cancelReminder(reminder.id) }) }) { Text("取消提醒") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pendingCancel = null }) { Text("保留提醒") } })
    }
}

@Composable
internal fun FeedbackNotice(feedback: ActionFeedback) {
    val color = if (feedback.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Text(feedback.message, style = MaterialTheme.typography.bodySmall, color = color,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = .08f)).padding(12.dp))
}

@Composable
private fun EmptyActivity(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal suspend fun uiActionFeedback(action: suspend () -> ActionFeedback): ActionFeedback = try {
    action()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    ActionFeedback(false, "操作没有完成，请检查连接后重试。")
}

internal fun formatActivityTime(epochMs: Long): String = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(epochMs))

private fun TaskStatus.displayLabel(): String = when (this) {
    TaskStatus.PENDING -> "等待执行"
    TaskStatus.RUNNING -> "执行中"
    TaskStatus.WAITING_CONFIRMATION -> "等待确认"
    TaskStatus.SUCCESS -> "已完成"
    TaskStatus.FAILED -> "失败"
    TaskStatus.CANCELLED -> "已取消"
}
