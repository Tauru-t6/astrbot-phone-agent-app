package com.tauru.astrbotphoneagent.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * UI <-> Logic contract.
 *
 * Kimi owns: everything in ui/ (screens, components, theme) and consumes
 * only these interfaces + data classes. GLM implements the interfaces.
 * UI ships with [FakeAppState] so every screen previews without logic.
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
    val streaming: Boolean,            // true while tokens are still arriving
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
)

data class ActionFeedback(val success: Boolean, val message: String)

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
}
