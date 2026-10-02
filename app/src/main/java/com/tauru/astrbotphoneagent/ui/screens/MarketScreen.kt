package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tauru.astrbotphoneagent.data.AppState
import com.tauru.astrbotphoneagent.ui.components.GlassCard

@Composable
fun MarketScreen(appState: AppState) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 22.dp, top = 18.dp, end = 22.dp, bottom = 24.dp),
    ) {
        Text("市集", style = MaterialTheme.typography.displayLarge, color = colors.onBackground)
        Text(
            "给日常添一点刚刚好的工具。",
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            elevation = 5.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 42.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(68.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✦", style = MaterialTheme.typography.headlineMedium, color = colors.primary)
                }
                Text(
                    "市集尚未开放",
                    modifier = Modifier.padding(top = 22.dp),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "目前还没有可浏览、安装或购买的内容。\n先留一处，给新的可能。",
                    modifier = Modifier.padding(top = 11.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
