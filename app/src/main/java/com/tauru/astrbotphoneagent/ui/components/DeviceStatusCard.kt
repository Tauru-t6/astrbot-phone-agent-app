package com.tauru.astrbotphoneagent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.data.DeviceStatus
import com.tauru.astrbotphoneagent.ui.theme.TextSecondary

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h} 小时 ${m} 分" else "${m} 分"
}

@Composable
fun DeviceStatusCard(status: DeviceStatus, modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatItem(
                label = "电量",
                value = status.batteryPercent?.let { "$it%" + if (status.charging) " ⚡" else "" } ?: "—",
            )
            StatItem(label = "前台应用", value = status.foregroundAppLabel ?: "—")
            StatItem(label = "今日亮屏", value = formatDuration(status.screenOnSecondsToday))
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
