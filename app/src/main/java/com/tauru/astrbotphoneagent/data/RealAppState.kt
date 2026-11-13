package com.tauru.astrbotphoneagent.data

import android.content.Context
import android.util.AtomicFile
import com.tauru.astrbotphoneagent.BuildConfig
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
    private val healthSync = HealthConnectSync(appContext, config)
    override val healthConnect = healthSync.state
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
    private val _automation = MutableStateFlow(AutomationSettings())
    override val automationSettings = _automation.asStateFlow()
    private val _autonomyOverview = MutableStateFlow(AutonomyOverview())
    override val autonomyOverview = _autonomyOverview.asStateFlow()
    private val _taskLibrary = MutableStateFlow(TaskLibraryState())
    override val taskLibrary = _taskLibrary.asStateFlow()
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

    // Mirrors the user's auto-approve list so the confirmation gate can read it
    // without suspending.
    @Volatile private var autoApprove: Set<String> = emptySet()

    init {
        // Bot builds do not initialize Health Connect, so initialize identity here.
        DeviceIdentity.deviceId(appContext)
        runner.onCommandStarted = { command ->
            upsertTask(AgentTask(command.commandId, commandTitle(command.action), TaskStatus.RUNNING,
                (command.createdAtEpochSec * 1000).toLong(), null, null, null,
                canCancel = taskJobs.containsKey(command.commandId)))
        }
        runner.onDangerousCommand = { command -> awaitConfirmation(command) }
        runner.autoApproveActions = { autoApprove }
        runner.onCommandFinished = { command, result -> finishTask(command, result) }
        scope.launch(Dispatchers.IO) {
            autoApprove = runCatching { config.autoApproveActions() }.getOrDefault(emptySet())
            _automation.value = AutomationSettings(
                autoApproveActions = autoApprove,
                notificationForwarding = !BuildConfig.IS_BOT_DEVICE && runCatching { config.notificationForwarding() }.getOrDefault(false),
            )
            loadHistory()
            ready.complete(Unit)
            launch { for (ignored in persistRequests) persistHistory() }
            launch { collectRemoteState() }
            launch { collectDeviceStatus() }
            bridge.start()
            if (!BuildConfig.IS_BOT_DEVICE) launch {
                healthSync.refresh()
                delay(20_000)
                while (isActive) {
                    healthSync.sync(force = false)
                    delay(15 * 60_000)
                }
            }
        }
    }

    override suspend fun saveCompanionProfile(name: String, avatarUri: String?): ActionFeedback =
        profileRepository.save(name, avatarUri)

    override suspend fun refreshHealthConnect(): ActionFeedback = healthSync.refresh()
    override suspend fun setHealthConnectEnabled(enabled: Boolean): ActionFeedback = healthSync.setEnabled(enabled)
    override suspend fun setHealthConnectSource(packageName: String): ActionFeedback = healthSync.setSource(packageName)
    override suspend fun syncHealthConnect(): ActionFeedback = healthSync.sync()

    fun onForeground() {
        if (BuildConfig.IS_BOT_DEVICE) return
        scope.launch {
            healthSync.refresh()
            healthSync.sync(force = false)
        }
    }

    override suspend fun saveAutomationSettings(update: AutomationSettings): ActionFeedback {
        val allowed = update.autoApproveActions.intersect(DANGEROUS_ACTIONS)
        val forwarding = !BuildConfig.IS_BOT_DEVICE && update.notificationForwarding
        return try {
            config.update(mapOf(
                "auto_approve_actions" to allowed.joinToString(","),
                "notification_forwarding" to if (forwarding) "true" else "false",
            ))
            autoApprove = allowed
            _automation.value = AutomationSettings(allowed, forwarding)
            ActionFeedback(true, "已保存")
        } catch (_: Exception) {
            ActionFeedback(false, "保存失败，请重试")
        }
    }

    /** Human-readable app name for a package; null when it cannot be resolved. */
    private fun appLabel(packageName: String?): String? {
        val packageId = packageName?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val info = appContext.packageManager.getApplicationInfo(packageId, 0)
            appContext.packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private suspend fun collectDeviceStatus() {
        combine(bridge.lastStatus, bridge.bridgeState, _reminders) { snapshot, connection, reminders ->
            _diagnostics.value = AppDiagnostics(
                pluginConnected = connection.pluginConnected,
                shizukuReady = snapshot?.shizukuReady == true,
                lastSyncAtEpochMs = connection.lastSyncAtEpochMs,
                pendingReminderCount = reminders.size,
                error = connection.lastError,
                shizukuDetail = connection.shizukuDetail,
                usageAccess = connection.usageAccess,
                registered = connection.registered,
                relayReachable = connection.relayReachable,
                inputMethodEnabled = (appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .enabledInputMethodList.any { it.packageName == appContext.packageName },
                deviceId = DeviceIdentity.deviceId(appContext),
                checkedAtEpochMs = System.currentTimeMillis(),
            )
            _deviceStatus.value = DeviceStatus(
                // Reaching /app/state is a working server route, so the UI can
                // say "direct online"; it is not proof the server can reach the
                // phone back, which command results still verify per call.
                connection = when {
                    connection.relayReachable -> ConnectionState.ONLINE_RELAY
                    connection.pluginConnected -> ConnectionState.ONLINE_DIRECT
                    connection.lastError != null -> ConnectionState.OFFLINE
                    else -> ConnectionState.UNKNOWN
                },
                batteryPercent = snapshot?.batteryPercent,
                charging = snapshot?.charging ?: false,
                foregroundAppLabel = appLabel(snapshot?.foregroundPackage),
                foregroundAppPackage = snapshot?.foregroundPackage,
                screenOnSecondsToday = snapshot?.screenOnSecondsToday ?: 0,
                updatedAtEpochMs = ((snapshot?.reportedAtEpochSec ?: 0.0) * 1000).toLong(),
            )
        }.collect()
    }

    private suspend fun collectRemoteState() {
        bridge.serverState.filterNotNull().collect { state ->
            _reminders.value = if (BuildConfig.IS_BOT_DEVICE) emptyList() else state.optJSONArray("reminders").objects().mapNotNull { it.toReminder() }.sortedBy { it.dueAtEpochMs }
            val remote = state.optJSONArray("timeline").objects().filter {
                !BuildConfig.IS_BOT_DEVICE || it.optString("type") == "task_result"
            }
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
            // G1/G2: parse night_guard + sleep_mode + features into AutonomyOverview.
            val ng = state.optJSONObject("night_guard")
            val sleepMode = state.optJSONObject("sleep_mode")
            val features = state.optJSONObject("features")
            val firstUserState = ng?.optJSONObject("states")?.let { users ->
                users.keys().asSequence().mapNotNull { uid -> users.optJSONObject(uid) }.firstOrNull()
            }
            // P1-D: read-only care snapshot from the companion bridge.
            val careJson = state.optJSONObject("care")?.takeIf { !BuildConfig.IS_BOT_DEVICE && it.length() > 0 }
            val careUser = careJson?.optJSONObject("users")?.let { users ->
                users.keys().asSequence().firstOrNull()?.let { users.optJSONObject(it) }
            }
            val care = careJson?.let { json ->
                CareStatus(
                    autonomyEnabled = json.optBoolean("autonomy_enabled", false),
                    mode = json.optString("mode", ""),
                    quietHours = json.optString("quiet_hours", ""),
                    inQuietHours = json.optBoolean("in_quiet_hours", false),
                    dailyLimit = json.optInt("daily_limit", 0),
                    sentToday = careUser?.optInt("sent_today", 0) ?: 0,
                    remaining = careUser?.optInt("remaining", 0) ?: 0,
                    evalToday = careUser?.optInt("eval_today", 0) ?: 0,
                    lastSentAt = careUser?.optString("last_sent_at", "").orEmpty(),
                    lastEvalAt = careUser?.optString("last_eval_at", "").orEmpty(),
                    recent = careUser?.optJSONArray("recent")?.let { arr ->
                        (0 until arr.length()).mapNotNull { index -> arr.optJSONObject(index) }
                            .map { item ->
                                CareDecision(
                                    outcome = item.optString("outcome", ""),
                                    reason = item.optString("reason", ""),
                                    detail = item.optString("detail", ""),
                                    atEpochSec = item.optDouble("at", 0.0),
                                )
                            }.reversed()
                    } ?: emptyList(),
                )
            }
            // P1-E: executed task runs (dry-run or real), newest first.
            // Stage 4: proposals waiting for a human decision.
            val localDeviceId = DeviceIdentity.deviceId(appContext)
            val uiTask = state.optJSONObject("ui_task")
            val devices = state.optJSONArray("devices")?.let { list ->
                (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { item ->
                    val id = item.textOrNull("device_id") ?: return@mapNotNull null
                    RegisteredPhone(id, item.textOrNull("label") ?: id, item.textOrNull("role") ?: "unassigned",
                        item.optBoolean("online", false), item.textOrNull("app_version").orEmpty(), item.textOrNull("device_kind").orEmpty())
                }
            } ?: emptyList()
            val taskApprovals = state.optJSONArray("task_approvals")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { item ->
                    val taskScope = item.optJSONObject("scope")
                    TaskApproval(
                        approvalId = item.optString("approval_id", ""),
                        runId = item.optString("run_id", ""),
                        actions = item.optJSONArray("actions")?.let { list ->
                            (0 until list.length()).map { list.optString(it) }.filter { it.isNotBlank() }
                        } ?: emptyList(),
                        status = item.optString("status", ""),
                        expiresAt = item.optDouble("expires_at", 0.0),
                        reason = item.optString("reason", ""),
                        goal = item.optString("goal", ""),
                        mode = item.optString("mode", ""),
                        packageName = taskScope?.optString("package", "").orEmpty(),
                        allowedTexts = taskScope?.stringList("allowed_texts").orEmpty(),
                        successTexts = taskScope?.stringList("success_texts").orEmpty(),
                        maxSteps = taskScope?.optInt("max_steps", 0) ?: 0,
                        deviceId = taskScope?.textOrNull("device_id") ?: item.textOrNull("device_id").orEmpty(),
                        deviceRole = taskScope?.optString("device_role", "").orEmpty(),
                        requesterDeviceId = item.textOrNull("requester_device_id") ?: taskScope?.textOrNull("requester_device_id").orEmpty(),
                        completionMode = taskScope?.textOrNull("completion_mode").orEmpty(),
                        allowVision = taskScope?.optBoolean("allow_vision",false) ?: false,
                        rememberRecipe = taskScope?.optBoolean("remember_recipe",false) ?: false,
                        entryPoint = taskScope?.optString("entry_point","app_home") ?: "app_home",
                        notify = taskScope?.optBoolean("notify",false) ?: false,
                        resultFields = taskScope?.optJSONObject("result_spec")?.stringList("fields").orEmpty(),
                        resultCount = taskScope?.optJSONObject("result_spec")?.optInt("max_items",0) ?: 0,
                        requiredResultCount = taskScope?.optJSONObject("result_spec")?.optInt("required_items",1) ?: 0,
                    )
                }.filter { approval -> approval.deviceId == localDeviceId || approval.requesterDeviceId == localDeviceId ||
                    (devices.size <= 1 && approval.deviceId.isBlank() && approval.mode != "ui") }
            } ?: emptyList()
            // Stage 4: surface a pending approval over the current screen when
            // the overlay permission is granted; otherwise it stays in the sheet.
            val pendingApproval = taskApprovals.firstOrNull {
                it.status == "pending" && it.expiresAt > System.currentTimeMillis() / 1000.0
            }
            if (pendingApproval != null) {
                // UI-task scope includes target device, app and input strings.
                // It must be reviewed in the full sheet before approval.
                val shown = if (pendingApproval.mode == "ui") {
                    withContext(Dispatchers.Main.immediate) { ApprovalOverlay.dismiss() }
                    false
                } else withContext(Dispatchers.Main.immediate) { ApprovalOverlay.show(
                    appContext, pendingApproval,
                    secondsLeft = (pendingApproval.expiresAt - System.currentTimeMillis() / 1000.0)
                        .toInt().coerceAtLeast(0),
                    onApprove = { scope.launch { approveTask(pendingApproval.approvalId) } },
                    onReject = { scope.launch { rejectTask(pendingApproval.approvalId, "悬浮窗拒绝") } },
                ) }
                // The floating card is best effort; a notification always works.
                if (!shown) {
                    val actions = pendingApproval.actions.joinToString("、") { ApprovalLabels.action(it) }
                    Notifier.post(
                        appContext,
                        "有一件事要你点头",
                        if (pendingApproval.mode == "ui") "界面任务待确认 · 打开 Phone Control 查看目标、输入内容和步骤上限"
                        else "$actions · 打开 App 后可在自主功能里允许或拒绝",
                        "approval-${pendingApproval.approvalId}",
                    )
                }
            } else {
                withContext(Dispatchers.Main.immediate) { ApprovalOverlay.dismiss() }
            }
            val taskRuns = state.optJSONArray("task_runs")?.let { arr ->
                (0 until arr.length()).mapNotNull { index -> arr.optJSONObject(index) }.map { item ->
                    val summary = item.optJSONObject("summary")
                    val taskScope = summary?.optJSONObject("ui_scope")
                    TaskRunSummary(
                        runId = item.optString("run_id", ""),
                        goal = item.optString("goal", ""),
                        status = item.optString("status", ""),
                        dryRun = summary?.optBoolean("dry_run", false) ?: false,
                        executed = summary?.optInt("executed", 0) ?: 0,
                        verified = summary?.optInt("verified", 0) ?: 0,
                        failed = summary?.optInt("failed", 0) ?: 0,
                        blocked = summary?.optInt("blocked", 0) ?: 0,
                        refused = summary?.optString("refused", "").orEmpty(),
                        lastError = item.optString("last_error", ""),
                        mode = summary?.optString("mode", "").orEmpty(),
                        packageName = taskScope?.optString("package", "").orEmpty(),
                        maxSteps = taskScope?.optInt("max_steps", 0) ?: 0,
                        modelCalls = summary?.optInt("model_calls", 0) ?: 0,
                        deviceId = item.textOrNull("device_id") ?: taskScope?.textOrNull("device_id").orEmpty(),
                        deviceRole = taskScope?.optString("device_role", "").orEmpty(),
                        requesterDeviceId = summary?.textOrNull("requester_device_id") ?: taskScope?.textOrNull("requester_device_id").orEmpty(),
                        results = summary?.optJSONObject("ui_results")?.optJSONArray("items").objects().take(10).map {
                            TaskResultItem(it.textOrNull("title").orEmpty(),it.textOrNull("author").orEmpty(),it.textOrNull("duration").orEmpty(),it.textOrNull("url").orEmpty()) },
                        resultsVerified = summary?.optJSONObject("ui_results")?.optBoolean("verified",false) ?: false,
                        resultSource=summary?.optJSONObject("ui_results")?.optString("source").orEmpty(),
                        missingResultFields=summary?.optJSONObject("ui_results")?.stringList("missing_fields").orEmpty(),
                        parentRunId = summary?.textOrNull("resume_from") ?: summary?.textOrNull("parent_run_id").orEmpty(),
                        canResume = summary?.optString("mode")=="ui" && item.optString("status") in setOf("failed","waiting_approval") &&
                            item.optString("last_error").isNotBlank(),
                        unknownOutcome = item.optJSONArray("steps").objects().any { it.optString("status")=="unknown" },
                        steps = item.optJSONArray("steps")?.let { steps ->
                            (0 until steps.length()).mapNotNull { steps.optJSONObject(it) }.map { step ->
                                TaskRunStep(
                                    action = step.optString("action", ""),
                                    status = step.optString("status", ""),
                                    reason = (step.optJSONObject("verify") ?: org.json.JSONObject())
                                        .optString("reason", ""),
                                    detail = (step.optJSONObject("verify") ?: org.json.JSONObject())
                                        .optString("detail", ""),
                                )
                            }
                        } ?: emptyList(),
                    )
                }.filter { run -> run.deviceId == localDeviceId || run.requesterDeviceId == localDeviceId ||
                    (devices.size <= 1 && run.deviceId.isBlank() && run.mode != "ui") }
            } ?: emptyList()
            _autonomyOverview.value = AutonomyOverview(
                nightGuard = NightGuardState(
                    enabled = ng?.optBoolean("enabled", false) ?: false,
                    window = ng?.optString("window", "") ?: "",
                    inWindow = ng?.optBoolean("in_window", false) ?: false,
                    dryRun = ng?.optBoolean("dry_run", true) ?: true,
                    packages = ng?.optJSONArray("packages")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                    } ?: emptyList(),
                    phase = firstUserState?.optString("phase", "") ?: "",
                    foregroundPackage = firstUserState?.optString("foreground_package", "") ?: "",
                    ownedSuspended = firstUserState?.optJSONArray("owned_suspended")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                    } ?: emptyList(),
                    enforcementCount = firstUserState?.optInt("enforcement_count", 0) ?: 0,
                    lastError = firstUserState?.optString("last_error", "") ?: "",
                ),
                sleepModeActive = sleepMode?.optBoolean("manual_active", false) ?: false,
                sleepModeUntil = if (sleepMode?.isNull("until") == false) sleepMode.optString("until") else null,
                suspendedPackages = sleepMode?.optJSONArray("suspended")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                } ?: emptyList(),
                features = features?.let { f ->
                    f.keys().asSequence().associate { key -> key to f.optBoolean(key) }
                } ?: emptyMap(),
                backend = state.optString("backend", ""),
                care = care,
                taskRuns = taskRuns,
                taskApprovals = taskApprovals,
                taskExecuteEnabled = state.optBoolean("task_execute_enabled", false),
                uiTask = UiTaskCapability(
                    supported = uiTask?.optBoolean("supported", false) ?: false,
                    enabled = uiTask?.optBoolean("enabled", false) ?: false,
                    reason = uiTask?.optString("reason", "").orEmpty(),
                    maxSteps = (uiTask?.optInt("max_steps", 8) ?: 8).coerceIn(1, 12),
                    packages = uiTask?.optJSONArray("packages")?.let { list ->
                        (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { item ->
                            item.optString("package").takeIf { it.isNotBlank() }?.let { packageName ->
                                UiTaskApp(item.optString("name", packageName), packageName)
                            }
                        }
                    } ?: emptyList(),
                ),
                devices = devices,
                defaultDeviceId = state.optString("default_device_id", ""),
                currentDeviceId = localDeviceId,
            )
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
        _chat.update { (it + ChatMessage("m-${UUID.randomUUID()}", true, trimmed, now(), false,
            status = ChatMessageStatus.SUCCEEDED) +
            ChatMessage(replyId, false, "", now(), true, status = ChatMessageStatus.STREAMING,
                requestId = replyId)).takeLast(300) }
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
                        replaceChatMessage(replyId, event.text, false, status = ChatMessageStatus.SUCCEEDED)
                        appendTimeline(TimelineItemType.AI_MESSAGE, event.text.take(60).ifBlank { "助手回复" }, event.text)
                    }
                    is AstrBotClient.ChatStreamEvent.Failed -> {
                        replaceChatMessage(replyId, accumulated.toString().ifBlank { "（${event.message}）" }, false,
                            status = ChatMessageStatus.FAILED, error = event.message)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            replaceChatMessage(replyId, accumulated.toString().ifBlank { "（回复已中断）" }, false,
                status = ChatMessageStatus.CANCELLED, error = "回复已中断")
            throw cancelled
        } catch (error: Exception) {
            replaceChatMessage(replyId, accumulated.toString().ifBlank { "（${safeError(error)}）" }, false,
                status = ChatMessageStatus.FAILED, error = safeError(error))
        } finally {
            _chat.update { messages -> messages.map { if (it.id == replyId && it.streaming)
                it.copy(text = it.text.ifBlank { if (complete) "（空回复）" else "（连接中断，请重试）" },
                    streaming = false,
                    status = if (complete) ChatMessageStatus.SUCCEEDED else it.status) else it } }
            requestPersist()
        }
    }

    override suspend fun runQuickAction(actionId: String) {
        ready.await()
        val action = _quickActions.value.firstOrNull { it.id == actionId } ?: return
        // The plugin owns the protected-package list, so guard actions are
        // server routes rather than device commands.
        val guard = when (actionId) {
            "focus_mode" -> Triple("start", 30, "已开启 30 分钟专注")
            "night_mode" -> Triple("start", 480, "已开启守夜，8 小时后自动恢复")
            "release_apps" -> Triple("stop", 0, "已解除应用限制")
            else -> null
        }
        if (guard != null) {
            runGuardAction(actionId, action.title, guard.first, guard.second, guard.third)
            return
        }
        val commandAction = when (actionId) {
            "screenshot" -> "screenshot"
            "locate" -> "location"
            "battery" -> "status"
            else -> {
                upsertTask(AgentTask("t-${UUID.randomUUID()}", action.title, TaskStatus.FAILED, now(), now(), null,
                    "该快捷操作尚未接入，请改用聊天指令"))
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

    private fun runGuardAction(actionId: String, title: String, mode: String, minutes: Int, successText: String) {
        val taskId = "t-${UUID.randomUUID()}"
        actionIds[taskId] = actionId
        upsertTask(AgentTask(taskId, title, TaskStatus.RUNNING, now(), null, null, null, canCancel = false))
        val job = scope.launch {
            try {
                val result = client.setSleepMode(mode, minutes)
                val ok = result.optBoolean("success")
                val detail = if (ok) successText else result.optString("error").ifBlank { "服务器未接受该请求" }
                finishGuardTask(taskId, title, ok, detail)
            } catch (cancelled: CancellationException) {
                finishGuardTask(taskId, title, false, "任务已取消")
                throw cancelled
            } catch (error: Exception) {
                finishGuardTask(taskId, title, false, safeError(error))
            } finally { taskJobs.remove(taskId) }
        }
        taskJobs[taskId] = job
    }

    private fun finishGuardTask(taskId: String, title: String, success: Boolean, detail: String) {
        val time = now()
        upsertTask(AgentTask(taskId, title, if (success) TaskStatus.SUCCESS else TaskStatus.FAILED,
            time, time, detail.take(400), if (success) null else detail.take(400)))
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
                    "ui_snapshot" -> "读取 ${command.args["expected_package"].orEmpty().take(255)} 的当前界面，供本次任务规划下一步。"
                    "ui_action" -> "在 ${command.args["expected_package"].orEmpty().take(255)} 中${uiActionLabel(command.args["kind"].orEmpty())}。本次任务只能输入已批准范围内的文字。"
                    "ui_visual_snapshot" -> "读取目标应用截图并在本机识别文字；画面会交给已连接服务器的任务模型。"
                    "ui_visual_action" -> "根据本次截图中识别的文字节点点击或返回；页面变化就停止。"
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
        // Screen snapshots are transient model input, never timeline/history content.
        val screenshot = if (result.success && command.action == "screenshot") {
            parseScreenshotResult(result.output)
        } else null
        val summary = if (result.success) {
            when {
                command.action in setOf("ui_snapshot","ui_visual_snapshot") -> "已读取任务界面"
                command.action in setOf("ui_action","ui_visual_action") -> "已执行${uiActionLabel(command.args["kind"].orEmpty())}，等待任务核验"
                screenshot != null -> "截图 ${screenshot.width}×${screenshot.height}"
                else -> result.output?.take(2000) ?: "已完成"
            }
        } else null
        val task = (existing ?: AgentTask(command.commandId, commandTitle(command.action), TaskStatus.RUNNING,
            (command.createdAtEpochSec * 1000).toLong(), null, null, null)).copy(status = status,
            finishedAtEpochMs = (result.finishedAtEpochSec * 1000).toLong(),
            resultSummary = summary,
            error = if (result.success) null else result.error.take(500),
            canCancel = false, canRetry = !result.success && actionIds.containsKey(command.commandId),
            screenshotResult = screenshot)
        upsertTask(task)
        appendTimeline(TimelineItemType.TASK_RESULT, "${task.title} · ${if (result.success) "完成" else if (status == TaskStatus.CANCELLED) "已取消" else "失败"}",
            summary ?: task.error.orEmpty(), task.id)
    }

    private fun parseScreenshotResult(output: String?): ScreenshotResult? {
        if (output.isNullOrBlank()) return null
        return try {
            val json = JSONObject(output)
            ScreenshotResult(
                format = json.optString("format", "jpeg"),
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                bytes = json.optInt("bytes", 0),
                dataBase64 = json.optString("data_base64", ""),
            )
        } catch (_: Exception) { null }
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

    override suspend fun approveTask(approvalId: String): ActionFeedback = decideTask(approvalId, true)

    override suspend fun pairDevice(payload: String): ActionFeedback = settingsMutex.withLock {
        ready.await()
        try {
            val parsed=PairingPayloadParser.parse(payload,if(BuildConfig.IS_BOT_DEVICE)"bot" else "personal")
            val values=DevicePairingClient.redeem(parsed,DeviceIdentity.deviceId(appContext))
            config.update(values)
            bridge.stop();bridge.start()
            ActionFeedback(true,"配对成功，已保存本设备专用凭据")
        } catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){ActionFeedback(false,if(error is IllegalArgumentException)"配对内容无效、已过期或用途不匹配，请重新生成" else safeError(error))}
    }

    override suspend fun runConnectionCheck(): ActionFeedback {
        ready.await()
        return try {
            val connected=withTimeout(45_000){bridge.checkConnection()}
            val current=bridge.bridgeState.value
            ActionFeedback(connected&&current.registered,if(!connected)current.lastError?:"服务器未连接"
                else if(!current.registered)"服务器可达，但设备注册尚未成功"
                else if(!executor.shizukuReady())"服务器与设备注册正常，Shizuku 尚未就绪"
                else "服务器、设备注册和 Shizuku 检查通过")
        }catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){ActionFeedback(false,safeError(error))}
    }

    override suspend fun refreshTaskLibrary():ActionFeedback {
        return try {
            val data=client.fetchTaskLibrary()
            val templates=data.optJSONArray("templates").objects().map { item ->
                val taskScope=item.optJSONObject("scope")?:JSONObject()
                val parameters=item.optJSONArray("parameters").objects().map {
                    TaskTemplateParameter(it.optString("name"),it.optString("default"),it.optInt("max_length",200)) }
                TaskTemplate(item.optString("template_id"),item.optString("name"),taskScope.optString("package"),taskScope.optString("goal"),
                    item.optString("device_id"),parameters,item.optBoolean("has_recipe"),buildString{
                        append("应用：${taskScope.optString("package")}\n目标：${taskScope.optString("goal")}")
                        append("\n允许输入：${taskScope.stringList("allowed_texts").joinToString("、")}")
                        append("\n完成条件：${taskScope.stringList("success_texts").joinToString("、")} · 最多 ${taskScope.optInt("max_steps")} 步")
                        append("\n入口：${taskScope.optString("entry_point","app_home")}")
                        if(taskScope.optBoolean("allow_vision"))append("\n允许截图辅助，画面会交给任务模型")
                    },taskScope.optJSONObject("result_spec")?.stringList("fields").orEmpty(),
                    taskScope.optJSONObject("result_spec")?.optInt("max_items",0)?:0,taskScope.optBoolean("remember_recipe"))
            }
            val schedules=data.optJSONArray("schedules").objects().map { item -> RoutineSchedule(
                item.optString("schedule_id").ifBlank{item.optString("id")},
                templates.firstOrNull{it.id==item.optString("template_id")}?.name?:item.optString("template_id"),
                item.optString("device_id"),item.optBoolean("enabled"),item.stringList("daily_times"),item.optString("timezone"),
                item.optInt("remaining_runs"),item.optInt("remaining_model_calls"),item.optDouble("expires_at",0.0),
                item.optString("last_status"),item.optBoolean("notify"),item.optString("quiet_start"),item.optString("quiet_end"),item.optInt("per_run_model_calls")) }
            _taskLibrary.value=TaskLibraryState(true,data.optBoolean("enabled"),templates,schedules,schedulesEnabled=data.optBoolean("schedules_enabled"))
            ActionFeedback(true,"任务收藏已刷新")
        }catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){_taskLibrary.update{it.copy(error=safeError(error))};ActionFeedback(false,safeError(error))}
    }

    private suspend fun libraryAction(successMessage:String,block:suspend()->JSONObject):ActionFeedback = try {
        block();refreshTaskLibrary();bridge.refreshServerState();ActionFeedback(true,successMessage)
    }catch(cancelled:CancellationException){throw cancelled}
    catch(error:Exception){ActionFeedback(false,safeError(error))}

    override suspend fun saveTaskTemplate(runId:String,name:String,parameters:Map<String,String>) =
        libraryAction("任务已收藏"){client.updateTaskLibrary(JSONObject().put("action","save").put("run_id",runId)
            .put("name",name).put("parameters",JSONObject(parameters)))}
    override suspend fun deleteTaskTemplate(templateId:String)=libraryAction("收藏已删除"){
        client.updateTaskLibrary(JSONObject().put("action","delete").put("template_id",templateId))}
    override suspend fun proposeTaskTemplate(templateId:String,parameters:Map<String,String>,deviceId:String)=
        libraryAction("已生成新的待确认任务，请核对范围后批准"){client.proposeTaskTemplate(templateId,parameters,deviceId)}
    override suspend fun resumeServerTask(runId:String,manualStateConfirmed:Boolean)=
        libraryAction("已创建续跑提案，批准后会重新观察当前页面"){client.resumeServerTask(runId,manualStateConfirmed)}
    override suspend fun saveRoutineSchedule(request:RoutineScheduleRequest)=libraryAction("日程已保存，可随时暂停"){
        client.updateTaskLibrary(JSONObject().put("action","schedule_save").put("template_id",request.templateId)
            .put("parameters",JSONObject(request.parameters)).put("device_id",request.deviceId)
            .put("daily_times",JSONArray(listOf(request.time))).put("timezone",request.timezone)
            .put("max_runs",request.maxRuns).put("max_runs_per_day",1).put("max_model_calls",request.maxModelCalls)
            .put("per_run_model_calls",request.perRunModelCalls).put("expires_at",request.expiresAt)
            .put("quiet_start",request.quietStart).put("quiet_end",request.quietEnd).put("notify",request.notify)
            .put("notify_only_changed",true).put("approved",true).put("enabled",true))}
    override suspend fun stopRoutineSchedule(scheduleId:String,delete:Boolean)=libraryAction(if(delete)"日程已删除" else "日程已暂停"){
        client.updateTaskLibrary(JSONObject().put("action",if(delete)"schedule_delete" else "schedule_enable")
            .put("schedule_id",scheduleId).put("enabled",false))}

    override suspend fun proposeUiTask(request: UiTaskRequest): ActionFeedback {
        ready.await()
        val current = _autonomyOverview.value
        if (!current.uiTask.supported) return ActionFeedback(false, "请先升级服务器插件以启用界面任务")
        if (!current.uiTask.enabled || !current.taskExecuteEnabled) return ActionFeedback(false, "服务器尚未开启界面任务执行")
        if (request.goal.isBlank() || request.goal.length > 500) return ActionFeedback(false, "请填写 1–500 字的任务目标")
        if (!Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(request.packageName))
            return ActionFeedback(false, "请填写有效的目标 App 包名")
        if (request.allowedTexts.size > 5 || request.successTexts.isEmpty() || request.successTexts.size > 5 ||
            (request.allowedTexts + request.successTexts).any { it.isBlank() || it.length > 200 || it.any(Char::isISOControl) })
            return ActionFeedback(false, "每项文字须为 1–200 字，最多各 5 项；请至少填写一项完成页面文字")
        if (request.maxSteps !in 1..current.uiTask.maxSteps) return ActionFeedback(false, "步骤上限超出服务器允许范围")
        if (request.deviceId.isBlank()) return ActionFeedback(false, "请选择执行设备")
        val selected = current.devices.firstOrNull { it.deviceId == request.deviceId }
            ?: return ActionFeedback(false, "该设备已不在注册列表，请刷新")
        if (selected.role !in setOf("personal", "bot")) return ActionFeedback(false, "请先为这台设备明确分配用途")
        return try {
            client.proposeUiTask(request)
            bridge.refreshServerState()
            ActionFeedback(true, "任务已提交，请在待你决定中核对执行设备、任务范围并允许这一次")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ActionFeedback(false, safeError(error)) }
    }

    override suspend fun cancelServerTask(runId: String): ActionFeedback {
        ready.await()
        if (runId.isBlank() || _autonomyOverview.value.taskRuns.none { it.runId == runId })
            return ActionFeedback(false, "找不到本机对应的任务")
        return try {
            client.cancelServerTask(runId)
            bridge.refreshServerState()
            ActionFeedback(true, "已请求停止，请查看最新任务状态；已完成的操作不会自动撤销")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ActionFeedback(false, safeError(error)) }
    }

    override suspend fun setDeviceRole(deviceId: String, role: String, label: String): ActionFeedback {
        ready.await()
        val device = _autonomyOverview.value.devices.firstOrNull { it.deviceId == deviceId }
            ?: return ActionFeedback(false, "找不到这台注册设备")
        if (role !in setOf("personal", "bot", "unassigned")) return ActionFeedback(false, "设备用途不受支持")
        if ((device.role == "personal") != (role == "personal")) return ActionFeedback(false, "已有个人手机用途固定，不能在这里替换")
        if (label.length > 60 || label.any(Char::isISOControl)) return ActionFeedback(false, "设备名称最多 60 字且不能换行")
        return try {
            client.setDeviceRole(deviceId, role, label.trim())
            bridge.refreshServerState()
            ActionFeedback(true, "设备用途已保存")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ActionFeedback(false, safeError(error)) }
    }

    override suspend fun rejectTask(approvalId: String, reason: String): ActionFeedback {
        ready.await()
        if (approvalId.isBlank()) return ActionFeedback(false, "找不到这条待批任务")
        return try {
            val result = client.rejectTask(approvalId, reason)
            scope.launch { bridge.refreshServerState() }
            if (result.optBoolean("success")) ActionFeedback(true, "已拒绝")
            else ActionFeedback(false, result.optString("error").ifBlank { "服务器拒绝了请求" })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ActionFeedback(false, safeError(error)) }
    }

    private suspend fun decideTask(approvalId: String, approved: Boolean): ActionFeedback {
        ready.await()
        if (approvalId.isBlank()) return ActionFeedback(false, "找不到这条待批任务")
        return try {
            // A stuck device must not leave the sheet showing "执行中" forever.
            val result = kotlinx.coroutines.withTimeout(30_000) { client.approveTask(approvalId) }
            scope.launch { bridge.refreshServerState() }
            if (result.optBoolean("success")) {
                val run = result.optJSONObject("run")
                val verified = run?.optJSONObject("summary")?.optInt("verified", 0) ?: 0
                val refused = run?.optJSONObject("summary")?.optString("refused", "").orEmpty()
                val status = run?.optString("status", "").orEmpty().ifBlank { result.optString("status", "") }
                when {
                    refused.isNotBlank() -> ActionFeedback(false, "已批准，但执行被拒绝：$refused")
                    status in setOf("failed", "cancelled") -> ActionFeedback(false, "任务${if (status == "failed") "失败" else "已取消"}，请查看执行记录")
                    status == "waiting_approval" || status == "needs_user" -> ActionFeedback(false, "任务需要你处理，请查看暂停原因")
                    status == "done" -> ActionFeedback(true, "任务完成，核验通过 $verified 步")
                    else -> ActionFeedback(true, "已批准，任务已启动；执行结果会在任务记录中更新")
                }
            } else {
                ActionFeedback(false, result.optString("error").ifBlank { "服务器拒绝了请求" })
            }
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

    private fun replaceChatMessage(
        id: String, text: String, streaming: Boolean,
        status: ChatMessageStatus? = null, error: String? = null,
    ) {
        _chat.update { list -> list.map {
            if (it.id == id) it.copy(
                text = text, streaming = streaming,
                status = status ?: it.status,
                error = error ?: it.error,
            ) else it
        } }
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
    private fun JSONObject.stringList(key: String): List<String> = optJSONArray(key)?.let { list ->
        (0 until list.length()).mapNotNull { index -> (list.opt(index) as? String)?.takeIf { it.isNotBlank() } }
    }.orEmpty()
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun safeError(error: Exception): String = if (error is AstrBotClient.ApiException) error.message ?: "请求失败" else "请求失败，请检查设置和网络"
    private fun now() = System.currentTimeMillis()
    private fun commandTitle(action: String): String = when (action) {
        "status" -> "查看设备状态"
        "screenshot" -> "截屏"
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
        "ui_snapshot" -> "观察任务页面"
        "ui_action" -> "执行界面操作"
        "ui_visual_snapshot" -> "截图辅助观察"
        "ui_visual_action" -> "执行截图节点操作"
        "usage_stats" -> "查看使用统计"
        else -> "手机操作"
    }
    private fun uiActionLabel(kind: String): String = when (kind) {
        "tap" -> "点击已定位控件"
        "input_text", "type" -> "输入批准文字"
        "scroll" -> "滚动页面"
        "back" -> "返回上一步"
        "wait" -> "等待页面更新"
        else -> "批准的界面操作"
    }
    private fun defaultQuickActions() = listOf(
        QuickAction("focus_mode", "专注", "30 分钟不看视频", "self_improvement", false, false),
        QuickAction("screenshot", "截屏", "截取当前屏幕", "screenshot", false, false),
        QuickAction("night_mode", "守夜", "限制受控应用 8 小时", "bedtime", false, false),
        QuickAction("locate", "获取位置", "操作前需要确认", "place", true, false),
        QuickAction("battery", "查看电量", "读取设备状态", "battery_std", false, false),
        QuickAction("release_apps", "解除限制", "恢复所有受控应用", "lock", false, false),
    )
}
