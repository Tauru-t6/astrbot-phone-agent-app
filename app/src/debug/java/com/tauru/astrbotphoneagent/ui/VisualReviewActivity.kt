package com.tauru.astrbotphoneagent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tauru.astrbotphoneagent.data.*
import com.tauru.astrbotphoneagent.ui.theme.PhoneAgentTheme
import kotlinx.coroutines.delay

/** Isolated, repeatable screenshots and interaction checks, with no server calls. */
class VisualReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent.getStringExtra("scenario") ?: "ready"
        val fake = FakeAppState()
        val ready = scenario == "ready"
        if (scenario != "empty") fake.connectionSettings.value = ConnectionSettings(
            pluginUrl = "http://192.0.2.10:6185/api/plug/astrbot_plugin_phone_agent",
            pluginTokenConfigured = true, sharedTokenConfigured = true,
        )
        fake.diagnostics.value = AppDiagnostics(
            pluginConnected = ready, shizukuReady = ready, usageAccess = ready,
            lastSyncAtEpochMs = if (ready) System.currentTimeMillis() - 120_000 else 0,
            shizukuDetail = if (ready) "可用" else "服务未运行",
            error = if (scenario == "error") "插件认证失败，请更新插件 Token 后重试。" else null,
        )
        fake.deviceStatus.value = fake.deviceStatus.value.copy(
            connection = if (ready) ConnectionState.ONLINE_DIRECT else ConnectionState.UNKNOWN,
            batteryPercent = if (ready) 73 else null,
            screenOnSecondsToday = if (ready) 3 * 3600L + 42 * 60L else 0,
            foregroundAppLabel = if (ready) "阅读" else null,
            foregroundAppPackage = null,
        )
        fake.companion.value = fake.companion.value.copy(name = "", personaSummary = "")
        if (!ready) { fake.tasks.value = emptyList(); fake.reminders.value = emptyList() }
        fake.quickActions.value = fake.quickActions.value.filter { it.id in setOf("screenshot", "battery", "night_mode", "locate") }
            .map { if (it.id == "screenshot") it.copy(subtitle = "查看当前屏幕") else it }
        val state = object : AppState by fake {
            override suspend fun testConnection(): ActionFeedback {
                delay(500)
                return if (scenario == "error") ActionFeedback(false, "插件认证失败，请更新插件 Token 后重试。")
                else ActionFeedback(true, "插件连接正常，状态同步成功（预览）")
            }
        }
        setContent { PhoneAgentTheme { PhoneAgentContent(state, initialTab = intent.getIntExtra("tab", 1).coerceIn(0, 2)) } }
    }
}
