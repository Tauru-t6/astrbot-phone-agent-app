package com.tauru.astrbotphoneagent.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.tauru.astrbotphoneagent.data.FakeAppState
import com.tauru.astrbotphoneagent.ui.theme.PhoneAgentTheme

// Local-only visual review: no phone connection, permissions or network needed.
@Preview(name = "生活 · 390", widthDp = 390, heightDp = 844, showSystemUi = true)
@Preview(name = "生活 · 320 / 大字", widthDp = 320, heightDp = 740, fontScale = 1.3f, showSystemUi = true)
@Preview(name = "生活 · 夜色", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES, showSystemUi = true)
@Composable
private fun LifePreview() {
    PhoneAgentTheme { PhoneAgentContent(remember { FakeAppState() }, initialTab = 2) }
}

@Preview(name = "聊天", widthDp = 390, heightDp = 844, showSystemUi = true)
@Composable
private fun ChatPreview() {
    PhoneAgentTheme { PhoneAgentContent(remember { FakeAppState() }, initialTab = 0) }
}

@Preview(name = "发现", widthDp = 390, heightDp = 844, showSystemUi = true)
@Composable
private fun DiscoverPreview() {
    PhoneAgentTheme { PhoneAgentContent(remember { FakeAppState() }, initialTab = 1) }
}

@Preview(name = "纸笺", widthDp = 390, heightDp = 844, showSystemUi = true)
@Composable
private fun PaperPreview() {
    PhoneAgentTheme { PhoneAgentContent(remember { FakeAppState() }, initialTab = 4) }
}

@Preview(name = "市集", widthDp = 390, heightDp = 844, showSystemUi = true)
@Composable
private fun MarketPreview() {
    PhoneAgentTheme { PhoneAgentContent(remember { FakeAppState() }, initialTab = 3) }
}
