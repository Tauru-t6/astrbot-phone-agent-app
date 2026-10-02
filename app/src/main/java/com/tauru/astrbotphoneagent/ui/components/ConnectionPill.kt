package com.tauru.astrbotphoneagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.data.ConnectionState
import com.tauru.astrbotphoneagent.ui.theme.Accent
import com.tauru.astrbotphoneagent.ui.theme.Danger
import com.tauru.astrbotphoneagent.ui.theme.Warning
import androidx.compose.ui.graphics.Color

@Composable
fun ConnectionPill(state: ConnectionState, modifier: Modifier = Modifier) {
    val (label, bg, fg) = when (state) {
        ConnectionState.ONLINE_DIRECT -> Triple("直连在线", Accent, Color.White)
        ConnectionState.ONLINE_RELAY -> Triple("Relay 在线", Warning, Color.White)
        ConnectionState.OFFLINE -> Triple("离线", Danger, Color.White)
        ConnectionState.UNKNOWN -> Triple(
            "连接中…",
            Color(0xFFE2E8F0),
            Color(0xFF64748B),
        )
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}
