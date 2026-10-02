package com.tauru.astrbotphoneagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.data.TimelineItem
import com.tauru.astrbotphoneagent.data.TimelineItemType
import com.tauru.astrbotphoneagent.ui.theme.TextSecondary
import com.tauru.astrbotphoneagent.ui.theme.TimelineAi
import com.tauru.astrbotphoneagent.ui.theme.TimelineDiary
import com.tauru.astrbotphoneagent.ui.theme.TimelineReminder
import com.tauru.astrbotphoneagent.ui.theme.TimelineTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun accentFor(type: TimelineItemType): Color = when (type) {
    TimelineItemType.AI_MESSAGE -> TimelineAi
    TimelineItemType.TASK_RESULT -> TimelineTask
    TimelineItemType.DIARY -> TimelineDiary
    TimelineItemType.REMINDER -> TimelineReminder
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

@Composable
fun TimelineCard(
    item: TimelineItem,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val accent = accentFor(item.type)
    val cardModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }

    GlassCard(
        modifier = cardModifier,
        alpha = if (item.type == TimelineItemType.AI_MESSAGE) 0.8f else 0.6f,
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .align(Alignment.CenterVertically)
                    .background(accent, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatTime(item.timestampEpochMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(item.body, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
    }
}
