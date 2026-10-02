package com.tauru.astrbotphoneagent.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake implementation so every screen renders in @Preview and in a demo build.
 * GLM replaces the DI binding with a real implementation; UI code never changes.
 */
class FakeAppState : AppState {

    override val deviceStatus = MutableStateFlow(
        DeviceStatus(
            connection = ConnectionState.ONLINE_DIRECT,
            batteryPercent = 73,
            charging = false,
            foregroundAppLabel = "哔哩哔哩",
            foregroundAppPackage = "tv.danmaku.bili",
            screenOnSecondsToday = 3 * 3600L + 42 * 60L,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    )

    override val quickActions = MutableStateFlow(
        listOf(
            QuickAction("focus_mode", "进专注", "半小时不看屏幕", "self_improvement", dangerous = false, active = false),
            QuickAction("screenshot", "截屏", "我看看你在哪", "screenshot", dangerous = false, active = false),
            QuickAction("night_mode", "守夜", "11 点后不接非急的", "bedtime", dangerous = false, active = true),
            QuickAction("locate", "你在哪", "定位发我一下", "place", dangerous = true, active = false),
            QuickAction("battery", "看电量", "报一下耗电", "battery_std", dangerous = false, active = false),
            QuickAction("lock_apps", "锁几个 app", "这一小时别碰", "lock", dangerous = true, active = false),
        )
    )

    override val tasks = MutableStateFlow(
        listOf(
            AgentTask("t3", "开哔哩哔哩", TaskStatus.SUCCESS, now() - 60_000, now() - 55_000, "开了", null),
            AgentTask("t2", "读取手机状态", TaskStatus.SUCCESS, now() - 300_000, now() - 290_000, "已同步", null),
            AgentTask("t1", "读取定位", TaskStatus.FAILED, now() - 3_600_000, now() - 3_500_000, null, "定位权限未授予", canRetry = true),
        )
    )

    override val timeline = MutableStateFlow(
        listOf(
            TimelineItem("tl5", TimelineItemType.AI_MESSAGE, now() - 30_000, "在。", "今天少用了点。继续。", null),
            TimelineItem("tl4", TimelineItemType.TASK_RESULT, now() - 60_000, "开了", "哔哩哔哩已开", "t3"),
            TimelineItem("tl3", TimelineItemType.DIARY, now() - 7_200_000, "今日小结", "下午憋了 2 小时。晚上出去走了。心情凑合。", null),
            TimelineItem("tl2", TimelineItemType.REMINDER, now() - 10_800_000, "起来倒水", "坐着 1 个半小时了。起来动一下。", null),
            TimelineItem("tl1", TimelineItemType.AI_MESSAGE, now() - 28_800_000, "早", "昨晚 7 小时多。可以。", null),
        )
    )

    override val chat = MutableStateFlow(
        listOf(
            ChatMessage("c1", fromUser = true, text = "帮我打开设置", timestampEpochMs = now() - 120_000, streaming = false),
            ChatMessage("c2", fromUser = false, text = "开了。", timestampEpochMs = now() - 115_000, streaming = false),
        )
    )

    override val companion = MutableStateFlow(
        CompanionProfile(name = "", personaSummary = "", avatarEmoji = "")
    )

    override val reminders = MutableStateFlow(listOf(PhoneReminder("demo-reminder", "喝杯水，休息一下", now() + 30 * 60_000)))
    override val connectionSettings = MutableStateFlow(ConnectionSettings())
    override val diagnostics = MutableStateFlow(AppDiagnostics())
    override val pendingConfirmation = MutableStateFlow<AgentTask?>(null)

    override suspend fun sendChat(text: String) { /* fake: no-op */ }
    override suspend fun runQuickAction(actionId: String) {
        val action = quickActions.value.find { it.id == actionId } ?: return
        val task = AgentTask("demo-${now()}", action.title, if (action.dangerous) TaskStatus.WAITING_CONFIRMATION else TaskStatus.SUCCESS,
            now(), if (action.dangerous) null else now(), if (action.dangerous) null else "预览操作完成", null, canCancel = action.dangerous)
        tasks.value = listOf(task) + tasks.value
        if (action.dangerous) pendingConfirmation.value = task
    }
    override suspend fun confirmAction(taskId: String, approved: Boolean) {
        if (pendingConfirmation.value?.id != taskId) return
        tasks.value = tasks.value.map { if (it.id == taskId) it.copy(status = if (approved) TaskStatus.SUCCESS else TaskStatus.CANCELLED, finishedAtEpochMs = now(), canCancel = false) else it }
        pendingConfirmation.value = null
    }
    override suspend fun cancelTask(taskId: String) {
        tasks.value = tasks.value.map { if (it.id == taskId && it.canCancel) it.copy(status = TaskStatus.CANCELLED, finishedAtEpochMs = now(), canCancel = false) else it }
        if (pendingConfirmation.value?.id == taskId) pendingConfirmation.value = null
    }
    override suspend fun retryTask(taskId: String) {
        tasks.value = tasks.value.map { if (it.id == taskId && it.canRetry) it.copy(status = TaskStatus.SUCCESS, resultSummary = "预览重试完成", error = null, finishedAtEpochMs = now(), canRetry = false) else it }
    }
    override suspend fun refreshStatus() { }
    override suspend fun createReminder(text: String, minutes: Int): ActionFeedback {
        if (text.isBlank() || text.length > 500 || minutes !in 1..10080) return ActionFeedback(false, "请填写 500 字以内的提醒和 1–10080 分钟")
        reminders.value = reminders.value + PhoneReminder("demo-${now()}", text, now() + minutes * 60_000L)
        return ActionFeedback(true, "提醒已创建（预览）")
    }
    override suspend fun cancelReminder(reminderId: String): ActionFeedback {
        reminders.value = reminders.value.filterNot { it.id == reminderId }
        return ActionFeedback(true, "提醒已取消（预览）")
    }
    override suspend fun saveConnectionSettings(update: ConnectionUpdate): ActionFeedback {
        val old = connectionSettings.value
        connectionSettings.value = ConnectionSettings(serverUrl = update.serverUrl, pluginUrl = update.pluginUrl, relayUrl = update.relayUrl, chatUsername = update.chatUsername,
            apiKeyConfigured = old.apiKeyConfigured || update.apiKey.isNotBlank(), pluginTokenConfigured = old.pluginTokenConfigured || update.pluginToken.isNotBlank(),
            sharedTokenConfigured = old.sharedTokenConfigured || update.sharedToken.isNotBlank(), relayTokenConfigured = old.relayTokenConfigured || update.relayToken.isNotBlank())
        return ActionFeedback(true, "配置已保存（预览）")
    }
    override suspend fun testConnection() = ActionFeedback(false, "预览模式不连接服务器")
    override suspend fun saveCompanionProfile(name: String, avatarUri: String?): ActionFeedback {
        companion.value = companion.value.copy(name = name, avatarUri = avatarUri)
        return ActionFeedback(true, "资料已保存（预览）")
    }

    private companion object {
        fun now() = System.currentTimeMillis()
    }
}
