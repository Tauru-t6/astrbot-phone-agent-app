package com.tauru.astrbotphoneagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.data.QuickAction
import com.tauru.astrbotphoneagent.ui.theme.Accent
import com.tauru.astrbotphoneagent.ui.theme.AccentSoft
import com.tauru.astrbotphoneagent.ui.theme.TextSecondary

private fun iconFor(name: String): ImageVector = when (name) {
    "self_improvement" -> Icons.Outlined.SelfImprovement
    "screenshot" -> Icons.Outlined.Screenshot
    "bedtime" -> Icons.Outlined.Bedtime
    "place" -> Icons.Outlined.Place
    "battery_std" -> Icons.Outlined.BatteryStd
    "lock" -> Icons.Outlined.Lock
    else -> Icons.Outlined.Place
}

@Composable
fun QuickActionTile(
    action: QuickAction,
    onClick: (QuickAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier.aspectRatio(1f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onClick(action) }
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (action.active) Accent else AccentSoft.copy(alpha = 0.3f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = iconFor(action.iconName),
                    contentDescription = action.title,
                    tint = if (action.active) MaterialTheme.colorScheme.onPrimary else Accent,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                action.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                action.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
