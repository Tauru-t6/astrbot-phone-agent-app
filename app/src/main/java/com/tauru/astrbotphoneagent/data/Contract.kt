package com.tauru.astrbotphoneagent.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * UI <-> Logic contract.
 *
 * The UI layer depends only on these interfaces and data classes, so screens
 * preview against [FakeAppState] without touching logic or the network.
 *
 * Rules of the seam:
 * - UI never calls Shizuku / HTTP / Room directly. Everything goes through AppState.
 * - All long-running work returns ids immediately; progress arrives via flows.
 * - Confirmation-required actions must NOT execute before UI calls confirmAction().
 */

// ---------- value types ----------

enum class ConnectionState { ONLINE_DIRECT, ONLINE_RELAY, OFFLINE, UNKNOWN }

enum class TaskStatus { PENDING, RUNNING, WAITING_CONFIRMATION, SUCCESS, FAILED, CANCELLED }

enum class TimelineItemType { AI_MESSAGE, TASK_RESULT, DIARY, REMINDER }

data class DeviceStatus(
    val connection: ConnectionState,
    val batteryPercent: Int?,          // null = unknown
    val charging: Boolean,
    val foregroundAppLabel: String?,   // e.g. "哔哩哔哩"
    val foregroundAppPackage: String?,
    val screenOnSecondsToday: Long,
    val updatedAtEpochMs: Long,
)

data class QuickAction(
    val id: String,                    // stable key, e.g. "focus_mode"
    val title: String,                 // "专注模式"
    val subtitle: String,              // "禁用视频 App 25 分钟"
    val iconName: String,              // material icon name, UI maps to ImageVector
    val dangerous: Boolean,            // true -> confirm dialog before execute
    val active: Boolean,               // true -> shown as toggled-on (e.g. focus mode running)
)

data class AgentTask(
    val id: String,
    val title: String,                 // human readable, "打开哔哩哔哩"
    val status: TaskStatus,
    val createdAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val resultSummary: String?,        // one-line outcome
    val error: String?,
    val canRetry: Boolean = false,
    val canCancel: Boolean = false,
    val screenshotResult: ScreenshotResult? = null,  // G3: typed media, not truncated String
)

data class TimelineItem(
    val id: String,
    val type: TimelineItemType,
    val timestampEpochMs: Long,
    val title: String,
    val body: String,                  // AI text / task summary / diary excerpt
    val relatedTaskId: String?,        // non-null when type == TASK_RESULT
)

data class ChatMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val timestampEpochMs: Long,
    val streaming: Boolean,
    val status: ChatMessageStatus = ChatMessageStatus.SUCCEEDED,  // G4: delivery status
    val error: String? = null,                                    // G4: failure reason
    val requestId: String? = null,                               // G4: for retry association
)

data class CompanionProfile(
    val name: String,
    val personaSummary: String,        // shown read-only in settings v1
    val avatarEmoji: String,           // legacy preview field; empty by default
    val avatarUri: String? = null,     // local user-selected image; never uploaded
)

data class PhoneReminder(
    val id: String,
    val text: String,
    val dueAtEpochMs: Long,
    val source: String = "app",
)

/** Secrets are never returned to the settings UI. Empty replacement fields keep them. */
data class ConnectionSettings(
    val serverUrl: String = "",
    val pluginUrl: String = "",
    val relayUrl: String = "",
    val apiKeyConfigured: Boolean = false,
    val pluginTokenConfigured: Boolean = false,
    val sharedTokenConfigured: Boolean = false,
    val relayTokenConfigured: Boolean = false,
    val chatUsername: String = "",
)

data class ConnectionUpdate(
    val serverUrl: String,
    val pluginUrl: String,
    val relayUrl: String,
    val apiKey: String = "",
    val pluginToken: String = "",
    val sharedToken: String = "",
    val relayToken: String = "",
    val chatUsername: String = "",
)

data class AppDiagnostics(
    val pluginConnected: Boolean = false,
    val shizukuReady: Boolean = false,
    val lastSyncAtEpochMs: Long = 0L,
    val pendingReminderCount: Int = 0,
    val error: String? = null,
    // "可用" / "未授权" / "服务未运行"; empty when never probed.
    val shizukuDetail: String = "",
    // Independent grant; without it the screen-time card always reads 0.
    val usageAccess: Boolean = false,
)

data class ActionFeedback(val success: Boolean, val message: String)

// User-controlled automation preferences, mirrored from local storage.
data class AutomationSettings(
    val autoApproveActions: Set<String> = setOf("screenshot"),
    val notificationForwarding: Boolean = false,
)

// G3: Screenshot result — a typed media attachment, not a truncated String.
// The executor returns base64 JPEG inside a JSON string; this type lets the
// UI decode and render it without losing data to take(500).
data class ScreenshotResult(
    val format: String = "jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Int = 0,
    val dataBase64: String = "",
) {
    val isValid: Boolean get() = dataBase64.isNotEmpty() && bytes > 0
}

// G1: Autonomy overview — what the App can show in its diagnostics page.
// Sourced from /app/state's night_guard + device_last_status + features.
data class NightGuardState(
    val enabled: Boolean = false,
    val window: String = "",
    val inWindow: Boolean = false,
    val dryRun: Boolean = true,
    val packages: List<String> = emptyList(),
    val phase: String = "",
    val foregroundPackage: String = "",
    val ownedSuspended: List<String> = emptyList(),
    val enforcementCount: Int = 0,
    val lastError: String = "",
)

// P1-E: one executed (or refused) task run, as served by /app/state task_runs.
data class TaskRunSummary(
    val runId: String = "",
    val goal: String = "",
    val status: String = "",
    val dryRun: Boolean = false,
    val executed: Int = 0,
    val verified: Int = 0,
    val failed: Int = 0,
    val blocked: Int = 0,
    val refused: String = "",
    val lastError: String = "",
    val steps: List<TaskRunStep> = emptyList(),
)

data class TaskRunStep(
    val action: String = "",
    val status: String = "",
    val reason: String = "",
    val detail: String = "",
)

data class AutonomyOverview(
    val nightGuard: NightGuardState = NightGuardState(),
    val sleepModeActive: Boolean = false,
    val sleepModeUntil: String? = null,
    val suspendedPackages: List<String> = emptyList(),
    val features: Map<String, Boolean> = emptyMap(),
    val backend: String = "",
    val care: CareStatus? = null,
    val taskRuns: List<TaskRunSummary> = emptyList(),
    val taskExecuteEnabled: Boolean = false,
)

// P1-D: read-only care snapshot written by the companion bridge. The app never
// configures care here; it only shows what the server actually decided.
data class CareStatus(
    val autonomyEnabled: Boolean = false,
    val mode: String = "",
    val quietHours: String = "",
    val inQuietHours: Boolean = false,
    val dailyLimit: Int = 0,
    val sentToday: Int = 0,
    val remaining: Int = 0,
    val evalToday: Int = 0,
    val lastSentAt: String = "",
    val lastEvalAt: String = "",
    val recent: List<CareDecision> = emptyList(),
)

data class CareDecision(
    val outcome: String = "",
    val reason: String = "",
    val detail: String = "",
    val atEpochSec: Double = 0.0,
)

// G4: Chat message delivery status — distinguishes partial output, failure, and cancellation.
enum class ChatMessageStatus { QUEUED, SENDING, STREAMING, SUCCEEDED, FAILED, CANCELLED }

// ---------- the seam ----------

interface AppState {
    // Streams (StateFlow at implementation level; UI collects)
    val deviceStatus: Flow<DeviceStatus>
    val quickActions: Flow<List<QuickAction>>
    val tasks: Flow<List<AgentTask>>           // newest first
    val timeline: Flow<List<TimelineItem>>     // newest first
    val chat: Flow<List<ChatMessage>>          // oldest first
    val companion: Flow<CompanionProfile>
    val reminders: Flow<List<PhoneReminder>> get() = flowOf(emptyList())
    val connectionSettings: Flow<ConnectionSettings> get() = flowOf(ConnectionSettings())
    val diagnostics: Flow<AppDiagnostics> get() = flowOf(AppDiagnostics())
    val pendingConfirmation: Flow<AgentTask?> get() = flowOf(null)
    val automationSettings: Flow<AutomationSettings> get() = flowOf(AutomationSettings())
    // G1: autonomy overview for the diagnostics/autonomy page.
    val autonomyOverview: Flow<AutonomyOverview> get() = flowOf(AutonomyOverview())

    // Intents (suspend, fast-returning; outcomes come through the flows)
    suspend fun sendChat(text: String)
    suspend fun runQuickAction(actionId: String)              // dangerous actions must await confirmAction
    suspend fun confirmAction(taskId: String, approved: Boolean)
    suspend fun cancelTask(taskId: String)
    suspend fun retryTask(taskId: String)
    suspend fun refreshStatus()                               // pull-to-refresh hook
    suspend fun createReminder(text: String, minutes: Int): ActionFeedback = ActionFeedback(false, "暂不可用")
    suspend fun cancelReminder(reminderId: String): ActionFeedback = ActionFeedback(false, "暂不可用")
    suspend fun saveConnectionSettings(update: ConnectionUpdate): ActionFeedback = ActionFeedback(false, "暂不可用")
    suspend fun testConnection(): ActionFeedback = ActionFeedback(false, "暂不可用")
    suspend fun saveCompanionProfile(name: String, avatarUri: String?): ActionFeedback = ActionFeedback(false, "暂不可用")
    suspend fun saveAutomationSettings(update: AutomationSettings): ActionFeedback = ActionFeedback(false, "暂不可用")
}
