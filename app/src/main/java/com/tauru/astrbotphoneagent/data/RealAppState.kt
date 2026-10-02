package com.tauru.astrbotphoneagent.data

import android.content.Context
import android.util.AtomicFile
import com.tauru.astrbotphoneagent.logic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** UI state backed by local history and the authenticated plugin API. */
class RealAppState(context: Context) : AppState {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = LogicConfig(appContext)
    private val executor = ShizukuCommandExecutor(appContext)
    private val runner = CommandRunner(appContext, executor)
    private val bridge = PluginBridge(appContext, config, runner, scope, executor)
    private val client = AstrBotClient(config)
    private val profileRepository = ProfileRepository(appContext)
    override val companion: Flow<CompanionProfile> = profileRepository.profile
    override val connectionSettings: Flow<ConnectionSettings> = config.connectionSettings
    private val _deviceStatus = MutableStateFlow(DeviceStatus(ConnectionState.UNKNOWN, null, false, null, null, 0L, 0L))
    override val deviceStatus = _deviceStatus.asStateFlow()
    private val _quickActions = MutableStateFlow(defaultQuickActions())
    override val quickActions = _quickActions.asStateFlow()
    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    override val tasks = _tasks.asStateFlow()
    private val _timeline = MutableStateFlow<List<TimelineItem>>(emptyList())
    override val timeline = _timeline.asStateFlow()
    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val chat = _chat.asStateFlow()
    private val _reminders = MutableStateFlow<List<PhoneReminder>>(emptyList())
    override val reminders = _reminders.asStateFlow()
    private val _diagnostics = MutableStateFlow(AppDiagnostics())
    override val diagnostics = _diagnostics.asStateFlow()
    private val _pendingConfirmation = MutableStateFlow<AgentTask?>(null)
    override val pendingConfirmation = _pendingConfirmation.asStateFlow()
    private val confirmations = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val taskJobs = ConcurrentHashMap<String, Job>()
    private val actionIds = ConcurrentHashMap<String, String>()
    private val reminderRequests = ConcurrentHashMap<String, String>()
    private val notifiedIds = ConcurrentHashMap.newKeySet<String>()
    private val history = AtomicFile(File(appContext.filesDir, "app_history.json"))
    private val historyMutex = Mutex()
    private val reminderMutex = Mutex()
    private val settingsMutex = Mutex()
    private val persistRequests = Channel<Unit>(Channel.CONFLATED)
    private val ready = CompletableDeferred<Unit>()

    init {
        runner.onCommandStarted = { command ->
            upsertTask(AgentTask(command.commandId, commandTitle(command.action), TaskStatus.RUNNING,
                (command.createdAtEpochSec * 1000).toLong(), null, null, null,
                canCancel = taskJobs.containsKey(command.commandId)))
        }
        runner.onDangerousCommand = { command -> awaitConfirmation(command) }
        runner.onCommandFinished = { command, result -> finishTask(command, result) }
        scope.launch(Dispatchers.IO) {
            loadHistory()
            ready.complete(Unit)
            launch { for (ignored in persistRequests) persistHistory() }
            launch { collectRemoteState() }
            launch { collectDeviceStatus() }
            bridge.start()
        }
    }

    override suspend fun saveCompanionProfile(name: String, avatarUri: String?): ActionFeedback =
        profileRepository.save(name, avatarUri)

    private suspend fun collectDeviceStatus() {
        combine(bridge.lastStatus, bridge.bridgeState, _reminders) { snapshot, connection, reminders ->
            _diagnostics.value = AppDiagnostics(connection.pluginConnected, snapshot?.shizukuReady == true,
                connection.lastSyncAtEpochMs, reminders.size, connection.lastError)
            _deviceStatus.value = DeviceStatus(
                // Registration is only an advertisement, never proof of a working direct route.
                connection = when {
                    connection.relayReachable -> ConnectionState.ONLINE_RELAY
                    connection.pluginConnected -> ConnectionState.UNKNOWN
                    connection.lastError != null -> ConnectionState.OFFLINE
                    else -> ConnectionState.UNKNOWN
                },
                batteryPercent = snapshot?.batteryPercent,
                charging = snapshot?.charging ?: false,
                foregroundAppLabel = snapshot?.foregroundPackage,
                foregroundAppPackage = snapshot?.foregroundPackage,
                screenOnSecondsToday = snapshot?.screenOnSecondsToday ?: 0,
                updatedAtEpochMs = ((snapshot?.reportedAtEpochSec ?: 0.0) * 1000).toLong(),
            )
        }.collect()
    }

    private suspend fun collectRemoteState() {
        bridge.serverState.filterNotNull().collect { state ->
            _reminders.value = state.optJSONArray("reminders").objects().mapNotNull { it.toReminder() }.sortedBy { it.dueAtEpochMs }
            val remote = state.optJSONArray("timeline").objects()
            val incoming = remote.filter { it.optString("id").isNotBlank() }.map { it.toTimelineItem() }
            _timeline.update { existing -> (incoming + existing).distinctBy { it.id }.sortedByDescending { it.timestampEpochMs }.take(200) }
            state.optJSONArray("recent_commands").objects().forEach { item ->
                val id = item.optString("command_id")
                if (id.isNotBlank() && _tasks.value.none { it.id == id }) {
                    val time = item.secondsAsMs("finished_at")
                    upsertTask(AgentTask(id, commandTitle(item.optString("action")),
                        if (item.optBoolean("success")) TaskStatus.SUCCESS else TaskStatus.FAILED,
                        time, time, null, item.textOrNull("error")))
                }
            }
            // Both a due event and an explicit notify bit are required. Old history never alerts.
            val serverNow = state.optDouble("server_time", System.currentTimeMillis() / 1000.0)
            for (item in remote) {
                val id = item.textOrNull("notification_id") ?: continue
                val age = serverNow - item.optDouble("timestamp", 0.0)
                if (item.optString("event") != "reminder_due" || !item.optBoolean("notify") ||
                    age !in 0.0..900.0 || id in notifiedIds) continue
                if (Notifier.post(appContext, item.optString("title").ifBlank { "提醒" }, item.optString("body"), id)) {
                    notifiedIds.add(id)
                    persistHistory()
                }
            }
            requestPersist()
        }
    }

    override suspend fun sendChat(text: String) {
        ready.await()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val replyId = "m-${UUID.randomUUID()}"
        _chat.update { (it + ChatMessage("m-${UUID.randomUUID()}", true, trimmed, now(), false) +
            ChatMessage(replyId, false, "", now(), true)).takeLast(300) }
        requestPersist()
        var accumulated = StringBuilder()
        var complete = false
        try {
            if (config.astrbotUrl().isBlank() || config.apiKey().isBlank() || config.username().isBlank()) {
                throw AstrBotClient.ApiException("请先在设置中填写服务器、API Key 和聊天身份")
            }
            client.chatStream(trimmed).collect { event ->
                when (event) {
                    is AstrBotClient.ChatStreamEvent.Piece -> {
                        accumulated.append(event.text)
                        replaceChatMessage(replyId, accumulated.toString(), true)
                    }
                    is AstrBotClient.ChatStreamEvent.Complete -> {
                        complete = true
                        accumulated = StringBuilder(event.text)
                        replaceChatMessage(replyId, event.text, false)
                        appendTimeline(TimelineItemType.AI_MESSAGE, event.text.take(60).ifBlank { "助手回复" }, event.text)
                    }
                    is AstrBotClient.ChatStreamEvent.Failed -> {
                        replaceChatMessage(replyId, accumulated.toString().ifBlank { "（${event.message}）" }, false)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            replaceChatMessage(replyId, accumulated.toString().ifBlank { "（回复已中断）" }, false)
            throw cancelled
        } catch (error: Exception) {
            replaceChatMessage(replyId, accumulated.toString().ifBlank { "（${safeError(error)}）" }, false)
        } finally {
            _chat.update { messages -> messages.map { if (it.id == replyId && it.streaming)
                it.copy(text = it.text.ifBlank { if (complete) "（空回复）" else "（连接中断，请重试）" }, streaming = false) else it } }
            requestPersist()
        }
    }

    override suspend fun runQuickAction(actionId: String) {
        ready.await()
        val action = _quickActions.value.firstOrNull { it.id == actionId } ?: return
        val commandAction = when (actionId) {
            "screenshot" -> "screenshot"
            "locate" -> "location"
            "battery" -> "status"
            else -> {
                upsertTask(AgentTask("t-${UUID.randomUUID()}", action.title, TaskStatus.FAILED, now(), now(), null,
                    "尚未配置受控应用和自动恢复策略，此功能暂不可用"))
                return
            }
        }
        val taskId = "t-${UUID.randomUUID()}"
        actionIds[taskId] = actionId
        val command = CommandEnvelope(commandId = taskId, action = commandAction,
            args = if (commandAction == "location") mapOf("high_accuracy" to "false") else emptyMap(),
            createdAtEpochSec = now() / 1000.0, dangerous = action.dangerous, deadline = 120)
        upsertTask(AgentTask(taskId, action.title, TaskStatus.PENDING, now(), null, null, null, canCancel = true))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { finishTask(command, runner.run(command)) }
            catch (cancelled: CancellationException) {
                finishTask(command, ResultEnvelope(commandId = taskId, success = false, action = commandAction,
                    error = "任务已取消", errorCode = "cancelled", finishedAtEpochSec = now() / 1000.0))
                throw cancelled
            } catch (_: Exception) {
                finishTask(command, ResultEnvelope(commandId = taskId, success = false, action = commandAction,
                    error = "执行失败，请检查权限和设备状态", errorCode = "internal", finishedAtEpochSec = now() / 1000.0))
            } finally { taskJobs.remove(taskId) }
        }
        taskJobs[taskId] = job
        job.invokeOnCompletion { cause ->
            taskJobs.remove(taskId, job)
            if (cause is CancellationException) finishTask(command,
                ResultEnvelope(commandId = taskId, success = false, action = commandAction,
                    error = "任务已取消", errorCode = "cancelled", finishedAtEpochSec = now() / 1000.0))
        }
        job.start()
    }

    private suspend fun awaitConfirmation(command: CommandEnvelope): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        confirmations[command.commandId] = deferred
        val existing = _tasks.value.firstOrNull { it.id == command.commandId }
        val task = (existing ?: AgentTask(command.commandId, commandTitle(command.action), TaskStatus.RUNNING,
            now(), null, null, null)).copy(status = TaskStatus.WAITING_CONFIRMATION, canCancel = true,
                resultSummary = when (command.action) {
                    "location" -> "读取当前位置${if (command.args["high_accuracy"].toBoolean()) "（精确定位）" else ""}"
                    "close_app", "suspend_app", "suspend_video_apps" ->
                        "目标应用：${command.args["package"] ?: command.args["packages"] ?: "未指定"}"
                    "send_notification" -> "${command.args["title"].orEmpty()}\n${command.args["text"].orEmpty()}".take(400)
                    else -> command.action
                })
        upsertTask(task)
        _pendingConfirmation.value = task
        return try {
            val remaining = ((command.createdAtEpochSec + command.deadline) * 1000 - now()).toLong().coerceAtLeast(1)
            val approved = withTimeoutOrNull(remaining.coerceAtMost(120_000)) { deferred.await() } == true
            if (approved) upsertTask(task.copy(status = TaskStatus.RUNNING, canCancel = taskJobs.containsKey(task.id)))
            approved
        } finally {
            confirmations.remove(command.commandId, deferred)
            _pendingConfirmation.update { if (it?.id == command.commandId) null else it }
        }
    }

    override suspend fun confirmAction(taskId: String, approved: Boolean) {
        confirmations[taskId]?.complete(approved)
    }

    override suspend fun cancelTask(taskId: String) {
        confirmations[taskId]?.complete(false)
        taskJobs[taskId]?.cancel()
    }

    override suspend fun retryTask(taskId: String) {
        ready.await()
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (task.canRetry) actionIds[taskId]?.let { runQuickAction(it) }
    }

    @Synchronized private fun finishTask(command: CommandEnvelope, result: ResultEnvelope) {
        val existing = _tasks.value.firstOrNull { it.id == command.commandId }
        if (existing?.finishedAtEpochMs != null) return
        val status = when {
            result.success -> TaskStatus.SUCCESS
            result.errorCode in setOf("cancelled", "denied") -> TaskStatus.CANCELLED
            else -> TaskStatus.FAILED
        }
        val task = (existing ?: AgentTask(command.commandId, commandTitle(command.action), TaskStatus.RUNNING,
            (command.createdAtEpochSec * 1000).toLong(), null, null, null)).copy(status = status,
            finishedAtEpochMs = (result.finishedAtEpochSec * 1000).toLong(),
            resultSummary = if (result.success) result.output?.take(500) ?: "已完成" else null,
            error = if (result.success) null else result.error.take(500),
            canCancel = false, canRetry = !result.success && actionIds.containsKey(command.commandId))
        upsertTask(task)
        appendTimeline(TimelineItemType.TASK_RESULT, "${task.title} · ${if (result.success) "完成" else if (status == TaskStatus.CANCELLED) "已取消" else "失败"}",
            task.resultSummary ?: task.error.orEmpty(), task.id)
    }

    override suspend fun refreshStatus() { ready.await(); bridge.refreshNow() }

    override suspend fun createReminder(text: String, minutes: Int): ActionFeedback = reminderMutex.withLock {
        ready.await()
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 500) return@withLock ActionFeedback(false, "提醒内容请填写 1–500 个字")
        if (minutes !in 1..10080) return@withLock ActionFeedback(false, "提醒时间须为 1 分钟到 7 天")
        val key = "$minutes:$trimmed"
        val requestId = reminderRequests.getOrPut(key) { UUID.randomUUID().toString() }
        // Persist before POST: repeating an uncertain request after a restart uses the same UUID.
        if (!persistHistory()) return@withLock ActionFeedback(false, "无法保存提醒请求，请检查存储空间")
        try {
            val result = client.createReminder(trimmed, minutes, requestId)
            result.optJSONObject("reminder")?.toReminder()?.let { reminder ->
                _reminders.update { (it.filterNot { old -> old.id == reminder.id } + reminder).sortedBy { r -> r.dueAtEpochMs } }
            }
            reminderRequests.remove(key)
            persistHistory()
            scope.launch { bridge.refreshServerState() }
            ActionFeedback(true, "提醒已添加")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ActionFeedback(false, safeError(error)) }
    }

    override suspend fun cancelReminder(reminderId: String): ActionFeedback = reminderMutex.withLock {
        ready.await()
        if (reminderId.isBlank()) return@withLock ActionFeedback(false, "找不到这条提醒")
        try {
            client.cancelReminder(reminderId)
            _reminders.update { list -> list.filterNot { it.id == reminderId } }
            requestPersist()
            scope.launch { bridge.refreshServerState() }
            ActionFeedback(true, "提醒已取消")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ActionFeedback(false, safeError(error)) }
    }

    override suspend fun saveConnectionSettings(update: ConnectionUpdate): ActionFeedback = settingsMutex.withLock {
        ready.await()
        val urls = listOf(update.serverUrl, update.pluginUrl, update.relayUrl)
        if (urls.any { !validUrl(it) }) return@withLock ActionFeedback(false, "地址须为 http:// 或 https://，且不能包含密码、查询参数或片段")
        if (update.chatUsername.length > 128 || update.chatUsername.any { it.isISOControl() })
            return@withLock ActionFeedback(false, "聊天身份格式不正确")
        val replacements = mapOf("openapi_key" to update.apiKey, "plugin_jwt" to update.pluginToken,
            "shared_token" to update.sharedToken, "relay_token" to update.relayToken)
        if (replacements.values.any { it.any { character -> character.isISOControl() } })
            return@withLock ActionFeedback(false, "令牌中不能包含换行或控制字符")
        try {
            val values = mutableMapOf("astrbot_base_url" to update.serverUrl.trim().trimEnd('/'),
                "plugin_base_url" to update.pluginUrl.trim().trimEnd('/'), "relay_base_url" to update.relayUrl.trim().trimEnd('/'),
                "chat_username" to update.chatUsername.trim(), "chat_session" to LogicConfig.DEFAULT_SESSION)
            replacements.filterValues { it.isNotBlank() }.forEach { (key, value) -> values[key] = value.trim() }
            config.update(values)
            bridge.stop()
            bridge.start()
            ActionFeedback(true, "连接设置已保存，正在重新连接")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ActionFeedback(false, "设置保存失败，请检查存储空间") }
    }

    override suspend fun testConnection(): ActionFeedback {
        ready.await()
        return if (bridge.refreshServerState()) ActionFeedback(true, "插件连接正常，状态同步成功")
        else ActionFeedback(false, bridge.bridgeState.value.lastError ?: "插件连接失败")
    }

    private fun validUrl(value: String): Boolean {
        if (value.isBlank()) return true
        val url = value.trim().toHttpUrlOrNull() ?: return false
        return url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null
    }

    private fun upsertTask(task: AgentTask) {
        _tasks.update { (listOf(task) + it.filterNot { old -> old.id == task.id }).sortedByDescending { t -> t.createdAtEpochMs }.take(100) }
        requestPersist()
    }

    private fun replaceChatMessage(id: String, text: String, streaming: Boolean) {
        _chat.update { list -> list.map { if (it.id == id) it.copy(text = text, streaming = streaming) else it } }
        requestPersist()
    }

    private fun appendTimeline(type: TimelineItemType, title: String, body: String, taskId: String? = null) {
        val item = TimelineItem("tl-local-${UUID.randomUUID()}", type, now(), title, body.take(2000), taskId)
        _timeline.update { (listOf(item) + it).take(200) }
        requestPersist()
    }

    private fun requestPersist() { persistRequests.trySend(Unit) }

    private suspend fun persistHistory(): Boolean = withContext(Dispatchers.IO) {
        historyMutex.withLock {
            try {
                val value = JSONObject().put("version", 1)
                    .put("chat", JSONArray(_chat.value.map { JSONObject().put("id", it.id).put("from_user", it.fromUser)
                        .put("text", it.text).put("timestamp", it.timestampEpochMs).put("streaming", it.streaming) }))
                    .put("tasks", JSONArray(_tasks.value.map { JSONObject().put("id", it.id).put("title", it.title)
                        .put("status", it.status.name).put("created", it.createdAtEpochMs).put("finished", it.finishedAtEpochMs)
                        .put("summary", it.resultSummary).put("error", it.error).put("action_id", actionIds[it.id]) }))
                    .put("timeline", JSONArray(_timeline.value.map { JSONObject().put("id", it.id).put("type", it.type.name)
                        .put("timestamp", it.timestampEpochMs).put("title", it.title).put("body", it.body).put("task_id", it.relatedTaskId) }))
                    .put("notified_ids", JSONArray(notifiedIds.toList()))
                    .put("reminder_requests", JSONObject(reminderRequests.toMap()))
                val stream = history.startWrite()
                try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); history.finishWrite(stream) }
                catch (error: Exception) { history.failWrite(stream); throw error }
                true
            } catch (_: Exception) { false }
        }
    }

    private fun loadHistory() {
        val value = runCatching { JSONObject(history.readFully().toString(Charsets.UTF_8)) }.getOrNull() ?: return
        _chat.value = value.optJSONArray("chat").objects().mapNotNull { item -> runCatching {
            ChatMessage(item.getString("id"), item.optBoolean("from_user"),
                item.optString("text").let { if (item.optBoolean("streaming")) it.ifBlank { "（上次回复已中断）" } else it },
                item.optLong("timestamp"), false)
        }.getOrNull() }.takeLast(300)
        _tasks.value = value.optJSONArray("tasks").objects().mapNotNull { item -> runCatching {
            val id = item.getString("id")
            item.textOrNull("action_id")?.let { actionIds[id] = it }
            val saved = TaskStatus.valueOf(item.getString("status"))
            val interrupted = saved in setOf(TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.WAITING_CONFIRMATION)
            AgentTask(id, item.optString("title"), if (interrupted) TaskStatus.FAILED else saved, item.optLong("created"),
                if (interrupted) now() else if (item.isNull("finished")) null else item.optLong("finished"),
                item.textOrNull("summary"), if (interrupted) "上次执行被中断，请检查结果后重试" else item.textOrNull("error"),
                canRetry = actionIds.containsKey(id) && (interrupted || saved in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED)))
        }.getOrNull() }.take(100)
        _timeline.value = value.optJSONArray("timeline").objects().mapNotNull { item -> runCatching {
            TimelineItem(item.getString("id"), TimelineItemType.valueOf(item.getString("type")), item.optLong("timestamp"),
                item.optString("title"), item.optString("body"), item.textOrNull("task_id"))
        }.getOrNull() }.take(200)
        value.optJSONArray("notified_ids")?.let { ids -> for (i in 0 until ids.length()) ids.optString(i).takeIf { it.isNotBlank() }?.let { notifiedIds.add(it) } }
        value.optJSONObject("reminder_requests")?.let { requests -> requests.keys().forEach { key ->
            requests.optString(key).takeIf { runCatching { UUID.fromString(it) }.isSuccess }?.let { reminderRequests[key] = it }
        } }
    }

    private fun JSONObject.toReminder(): PhoneReminder? {
        if (optString("status", "pending") != "pending" || optString("id").isBlank()) return null
        return PhoneReminder(optString("id"), optString("text"), secondsAsMs("due"), optString("source", "app"))
    }

    private fun JSONObject.toTimelineItem() = TimelineItem("tl-remote-${optString("id")}", when (optString("type")) {
        "task_result" -> TimelineItemType.TASK_RESULT
        "diary" -> TimelineItemType.DIARY
        "reminder" -> TimelineItemType.REMINDER
        else -> TimelineItemType.AI_MESSAGE
    }, secondsAsMs("timestamp"), optString("title"), optString("body"), textOrNull("related_task_id"))

    private fun JSONObject.secondsAsMs(key: String): Long = optDouble(key, 0.0).takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() } ?: 0L
    private fun JSONObject.textOrNull(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun safeError(error: Exception): String = if (error is AstrBotClient.ApiException) error.message ?: "请求失败" else "请求失败，请检查设置和网络"
    private fun now() = System.currentTimeMillis()
    private fun commandTitle(action: String): String = when (action) {
        "status" -> "查看设备状态"
        "screenshot" -> "保存截屏"
        "location" -> "获取位置"
        "open_app" -> "打开应用"
        "close_app" -> "关闭应用"
        "lock_screen" -> "锁屏"
        "wake_screen" -> "亮屏"
        "suspend_app", "suspend_video_apps" -> "暂停应用"
        "unsuspend_app", "unsuspend_video_apps" -> "恢复应用"
        "send_notification" -> "显示通知"
        "home" -> "返回桌面"
        "back" -> "返回上一步"
        "screen_text" -> "读取屏幕"
        "usage_stats" -> "查看使用统计"
        else -> "手机操作"
    }
    private fun defaultQuickActions() = listOf(
        QuickAction("focus_mode", "专注", "需先配置受控应用", "self_improvement", false, false),
        QuickAction("screenshot", "截屏", "保存到系统相册", "screenshot", false, false),
        QuickAction("night_mode", "夜间模式", "需先配置恢复策略", "bedtime", false, false),
        QuickAction("locate", "获取位置", "操作前需要确认", "place", true, false),
        QuickAction("battery", "查看电量", "读取设备状态", "battery_std", false, false),
        QuickAction("lock_apps", "暂停应用", "需先配置受控应用", "lock", true, false),
    )
}
