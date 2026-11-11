package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.tauru.astrbotphoneagent.data.AppState

/** No health UI is available in the Bot APK, including stale navigation state. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun HealthConnectSheet(appState: AppState, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { onDismiss() }
}
