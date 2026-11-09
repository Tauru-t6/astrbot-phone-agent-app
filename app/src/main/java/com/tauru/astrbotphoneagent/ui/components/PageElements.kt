package com.tauru.astrbotphoneagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shared hierarchy for the three main destinations. */
@Composable
internal fun PageHeading(title: String, subtitle: String, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
internal fun StatusLabel(text: String, attention: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val tint = if (attention) colors.error else colors.primary
    Row(
        Modifier.clip(CircleShape).background(tint.copy(alpha = .09f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(5.dp).background(tint, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.Medium)
    }
}
