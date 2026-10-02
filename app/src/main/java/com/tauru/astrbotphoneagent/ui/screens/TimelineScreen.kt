package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tauru.astrbotphoneagent.data.AppState
import com.tauru.astrbotphoneagent.data.TimelineItem
import com.tauru.astrbotphoneagent.data.TimelineItemType
import com.tauru.astrbotphoneagent.ui.components.GlassCard
import com.tauru.astrbotphoneagent.ui.components.ProfileAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TimelineScreen(appState: AppState, onOpenChat: () -> Unit) {
    val timeline by appState.timeline.collectAsState(initial = emptyList())
    val companion by appState.companion.collectAsState(initial = null)
    var selectedType by rememberSaveable { mutableStateOf<TimelineItemType?>(null) }
    val visibleItems = timeline.filter { selectedType == null || it.type == selectedType }
    val colors = MaterialTheme.colorScheme

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, top = 18.dp, end = 22.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("发现", style = MaterialTheme.typography.displayLarge, color = colors.onBackground)
            Text(
                "日常里，留下一点回响。",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TimelineFilter("全部", selectedType == null) { selectedType = null }
                TimelineItemType.entries.forEach { type ->
                    TimelineFilter(timelineTypeLabel(type), selectedType == type) { selectedType = type }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (selectedType == null) "最近动态" else timelineTypeLabel(selectedType!!),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    "${visibleItems.size} 条记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        }
        if (visibleItems.isEmpty()) {
            item {
                TimelineEmptyState(
                    isTimelineEmpty = timeline.isEmpty(),
                    selectedLabel = selectedType?.let(::timelineTypeLabel),
                    onOpenChat = onOpenChat,
                )
            }
        }
        items(visibleItems, key = { it.id }) { item ->
            if (item.type == TimelineItemType.AI_MESSAGE) {
                AiMessageCard(item, companion?.name.orEmpty(), companion?.avatarUri, onOpenChat)
            } else {
                TimelineRecordCard(item)
            }
        }
    }
}

@Composable
private fun TimelineFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)) },
        shape = CircleShape,
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surface.copy(alpha = 0.44f),
            labelColor = colors.onSurfaceVariant,
            selectedContainerColor = colors.primary,
            selectedLabelColor = colors.onPrimary,
        ),
    )
}

@Composable
private fun AiMessageCard(item: TimelineItem, companionName: String, avatarUri: String?, onOpenChat: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    GlassCard(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable(onClick = onOpenChat),
        shape = RoundedCornerShape(28.dp),
        elevation = 5.dp,
    ) {
        Column(Modifier.padding(21.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(companionName, avatarUri, Modifier.size(43.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(companionName.ifBlank { "未设置名称" }, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    Text(
                        formatTimelineTime(item.timestampEpochMs),
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = colors.primary, modifier = Modifier.size(19.dp))
            }
            if (item.title.isNotBlank() && item.title != companionName) {
                Text(
                    item.title,
                    modifier = Modifier.padding(top = 19.dp),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                )
            }
            if (item.body.isNotBlank()) {
                Text(
                    item.body,
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp),
                    color = colors.onSurface,
                )
            }
            Row(
                modifier = Modifier.padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.primary)
                Text("聊一聊", style = MaterialTheme.typography.labelMedium, color = colors.primary)
            }
        }
    }
}

@Composable
private fun TimelineRecordCard(item: TimelineItem) {
    val colors = MaterialTheme.colorScheme
    val icon = when (item.type) {
        TimelineItemType.TASK_RESULT -> Icons.Outlined.CheckCircleOutline
        TimelineItemType.DIARY -> Icons.Outlined.EditNote
        TimelineItemType.REMINDER -> Icons.Outlined.NotificationsNone
        TimelineItemType.AI_MESSAGE -> Icons.Outlined.AutoAwesome
    }
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(23.dp), elevation = 3.dp) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(37.dp).clip(RoundedCornerShape(13.dp)).background(colors.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = colors.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.body.isNotBlank()) {
                    Text(
                        item.body,
                        modifier = Modifier.padding(top = 5.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp),
                        color = colors.onSurfaceVariant,
                    )
                }
                Text(
                    "${timelineTypeLabel(item.type)}  ·  ${formatTimelineTime(item.timestampEpochMs)}",
                    modifier = Modifier.padding(top = 9.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.78f),
                )
            }
        }
    }
}

@Composable
private fun TimelineEmptyState(isTimelineEmpty: Boolean, selectedLabel: String?, onOpenChat: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), elevation = 4.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 25.dp, vertical = 37.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(66.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = colors.primary, modifier = Modifier.size(26.dp))
            }
            Text(
                if (isTimelineEmpty) "故事，慢慢留下来" else "这里暂时很安静",
                modifier = Modifier.padding(top = 22.dp),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                if (isTimelineEmpty) "还没有动态。提醒、任务和伙伴的消息，会在这里相遇。" else "还没有${selectedLabel.orEmpty()}记录，稍后再来看看。",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(25.dp))
            Button(
                onClick = onOpenChat,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text("去聊聊")
            }
        }
    }
}

private fun timelineTypeLabel(type: TimelineItemType): String = when (type) {
    TimelineItemType.AI_MESSAGE -> "伙伴消息"
    TimelineItemType.TASK_RESULT -> "任务"
    TimelineItemType.DIARY -> "纸笺"
    TimelineItemType.REMINDER -> "提醒"
}

private fun formatTimelineTime(timestamp: Long): String =
    SimpleDateFormat("M月d日  HH:mm", Locale.getDefault()).format(Date(timestamp))
