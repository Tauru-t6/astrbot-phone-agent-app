package com.tauru.astrbotphoneagent.logic

import android.content.Context
import com.tauru.astrbotphoneagent.data.ActionFeedback
import com.tauru.astrbotphoneagent.data.HealthConnectState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared AppState adapter. The Bot APK has no health SDK, reader, or transport. */
@Suppress("UNUSED_PARAMETER")
internal class HealthConnectSync(context: Context, config: LogicConfig) {
    private val unavailableMessage = "Phone Control Bot 不提供健康数据功能"
    val state = MutableStateFlow(
        HealthConnectState(available = false, enabled = false, error = unavailableMessage),
    ).asStateFlow()

    suspend fun refresh(): ActionFeedback = unavailable()
    suspend fun setEnabled(enabled: Boolean): ActionFeedback = unavailable()
    suspend fun setSource(packageName: String): ActionFeedback = unavailable()
    suspend fun sync(force: Boolean = true): ActionFeedback = unavailable()

    private fun unavailable() = ActionFeedback(false, unavailableMessage)
}
