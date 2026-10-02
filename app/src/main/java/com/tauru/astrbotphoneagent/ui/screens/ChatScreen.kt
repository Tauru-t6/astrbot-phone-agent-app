package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.data.AppState
import com.tauru.astrbotphoneagent.data.ChatMessage
import com.tauru.astrbotphoneagent.data.ConnectionState
import com.tauru.astrbotphoneagent.ui.components.GlassCard
import com.tauru.astrbotphoneagent.ui.components.ProfileAvatar
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Insets and the water background are owned by the parent Scaffold. */
@Composable
fun ChatScreen(appState: AppState) {
    val messages by appState.chat.collectAsState(initial = emptyList())
    val companion by appState.companion.collectAsState(initial = null)
    val deviceStatus by appState.deviceStatus.collectAsState(initial = null)
    val name = companion?.name.orEmpty()
    val avatarUri = companion?.avatarUri
    // Keep the composition buffer: String-only state lost committed text on
    // Honor/MagicOS keyboards. Both the button and IME use the same submit path.
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var followLatest by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun submit() {
        val text = input.text.trim()
        if (text.isNotEmpty()) {
            followLatest = true
            scope.launch { appState.sendChat(text) }
            input = TextFieldValue("")
        }
    }

    // A reader can scroll back without incoming SSE tokens pulling them down.
    // Returning to the bottom resumes following; sending always resumes it.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> followLatest = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    if (!listState.canScrollForward) followLatest = true
                }
                else -> Unit
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { !listState.canScrollForward }.collect { atBottom ->
            if (atBottom) followLatest = true
        }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text, followLatest) {
        if (messages.isNotEmpty() && followLatest) {
            // The trailing anchor also follows a reply taller than the viewport.
            listState.scrollToItem(messages.size)
        }
    }
    // A keyboard resize can change the height without a new SSE token.
    LaunchedEffect(listState, followLatest, messages.size) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect { height ->
            if (height > 0 && messages.isNotEmpty() && followLatest) listState.scrollToItem(messages.size)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ChatHeader(name, avatarUri, deviceStatus?.connection ?: ConnectionState.UNKNOWN)

        if (messages.isEmpty()) {
            EmptyConversation(
                name = name,
                avatarUri = avatarUri,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onSuggestion = { suggestion ->
                    input = TextFieldValue(suggestion, selection = TextRange(suggestion.length))
                },
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    Column {
                        val previous = messages.getOrNull(index - 1)
                        if (previous == null || messageDate(previous) != messageDate(message)) {
                            DateDivider(message.timestampEpochMs)
                        }
                        ChatBubble(message, name, avatarUri)
                    }
                }
                item(key = "conversation-end") { Spacer(Modifier.height(1.dp)) }
            }
        }

        InputPanel(input, onInputChange = { input = it }, onSubmit = ::submit)
    }
}

@Composable
private fun ChatHeader(name: String, avatarUri: String?, connection: ConnectionState) {
    val label = when (connection) {
        ConnectionState.ONLINE_DIRECT -> "设备已直连"
        ConnectionState.ONLINE_RELAY -> "设备已连接 · 中继"
        ConnectionState.OFFLINE -> "设备离线"
        ConnectionState.UNKNOWN -> "正在检查设备"
    }
    val statusColor = when (connection) {
        ConnectionState.ONLINE_DIRECT, ConnectionState.ONLINE_RELAY -> MaterialTheme.colorScheme.primary
        ConnectionState.OFFLINE, ConnectionState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
        shape = RoundedCornerShape(24.dp),
        elevation = 3.dp,
    ) {
        Row(Modifier.padding(horizontal = 15.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(name, avatarUri, Modifier.size(38.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name.ifBlank { "未设置名称" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyConversation(name: String, avatarUri: String?, modifier: Modifier, onSuggestion: (String) -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        GlassCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            shape = RoundedCornerShape(28.dp),
            alpha = 0.70f,
            elevation = 4.dp,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProfileAvatar(name, avatarUri, Modifier.size(62.dp))
                Spacer(Modifier.height(18.dp))
                Text("从一句话开始", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(7.dp))
                Text("想到什么，直接告诉我。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(22.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    listOf("在吗", "看看手机状态", "今天有点累").forEach { suggestion ->
                        val shape = RoundedCornerShape(18.dp)
                        Box(
                            Modifier.clip(shape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), shape)
                                .clickable(role = Role.Button) { onSuggestion(suggestion) }
                                .padding(horizontal = 13.dp, vertical = 12.dp),
                        ) {
                            Text(suggestion, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputPanel(input: TextFieldValue, onInputChange: (TextFieldValue) -> Unit, onSubmit: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
        shape = RoundedCornerShape(28.dp),
        elevation = 4.dp,
    ) {
        Row(Modifier.padding(7.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                shape = RoundedCornerShape(22.dp),
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
            )
            val hasText = input.text.isNotBlank()
            Box(
                modifier = Modifier.padding(bottom = 4.dp, end = 3.dp).size(48.dp)
                    .clip(CircleShape)
                    .background(if (hasText) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.13f))
                    // Keep clicks available during OEM IME composition; submit
                    // owns the final empty-text check.
                    .clickable(role = Role.Button, onClick = onSubmit),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = if (hasText) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, name: String, avatarUri: String?) {
    val isUser = message.fromUser
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = if (isUser) 38.dp else 0.dp, end = if (isUser) 0.dp else 18.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            ProfileAvatar(name, avatarUri, Modifier.padding(top = 4.dp).size(28.dp))
            Spacer(Modifier.width(7.dp))
        }
        val shape = RoundedCornerShape(
            topStart = if (isUser) 22.dp else 6.dp,
            topEnd = if (isUser) 6.dp else 22.dp,
            bottomStart = 22.dp,
            bottomEnd = 22.dp,
        )
        val bubbleModifier = Modifier.weight(1f, fill = false).widthIn(max = 340.dp)
        if (isUser) {
            Box(
                bubbleModifier.clip(shape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), shape),
            ) {
                BubbleText(message, MaterialTheme.colorScheme.onPrimaryContainer)
            }
        } else {
            GlassCard(modifier = bubbleModifier, shape = shape, elevation = 2.dp) {
                BubbleText(message, MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun BubbleText(message: ChatMessage, color: Color) {
    Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = message.text.ifEmpty { if (message.streaming) "正在想…" else "（空回复）" },
            style = MaterialTheme.typography.bodyLarge,
            color = color,
        )
        Text(
            text = if (message.streaming) "正在回复" else messageTime(message.timestampEpochMs),
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.66f),
        )
    }
}

@Composable
private fun DateDivider(timestampEpochMs: Long) {
    val date = Instant.ofEpochMilli(timestampEpochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    val label = when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> date.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    }
    Text(
        label,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private fun messageDate(message: ChatMessage): LocalDate =
    Instant.ofEpochMilli(message.timestampEpochMs).atZone(ZoneId.systemDefault()).toLocalDate()

private fun messageTime(timestampEpochMs: Long): String =
    Instant.ofEpochMilli(timestampEpochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
