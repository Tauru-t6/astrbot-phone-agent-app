package com.tauru.astrbotphoneagent.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.tauru.astrbotphoneagent.data.FakeAppState
import com.tauru.astrbotphoneagent.ui.theme.PhoneAgentTheme

// Local-only previews; no phone connection, permissions or network needed.
@Preview(name = "生活 · 360", widthDp = 360, heightDp = 800, showSystemUi = true)
@Preview(name = "生活 · 412 / 大字", widthDp = 412, heightDp = 915, fontScale = 1.3f, showSystemUi = true)
@Preview(name = "生活 · 夜色", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES, showSystemUi = true)
@Composable
private fun LifePreview() {
    PhoneAgentTheme { PhoneAgentContent(remember { FakeAppState() }, initialTab = 1) }
}

@Preview(name = "动态", widthDp = 360, heightDp = 800, showSystemUi = true)
@Composable
private fun TimelinePreview() {
    PhoneAgentTheme { PhoneAgentContent(remember { FakeAppState() }, initialTab = 0) }
}

@Preview(name = "我的", widthDp = 412, heightDp = 915, showSystemUi = true)
@Preview(name = "我的 · 大字", widthDp = 360, heightDp = 800, fontScale = 1.3f, showSystemUi = true)
@Composable
private fun SettingsPreview() {
    PhoneAgentTheme { PhoneAgentContent(remember { FakeAppState() }, initialTab = 2) }
}
