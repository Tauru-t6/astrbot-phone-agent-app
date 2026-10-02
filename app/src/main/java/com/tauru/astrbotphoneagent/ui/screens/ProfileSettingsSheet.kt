package com.tauru.astrbotphoneagent.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.data.ActionFeedback
import com.tauru.astrbotphoneagent.data.AppState
import com.tauru.astrbotphoneagent.data.CompanionProfile
import com.tauru.astrbotphoneagent.ui.components.ProfileAvatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileSettingsSheet(appState: AppState, profile: CompanionProfile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(profile.name)) }
    var avatarUri by rememberSaveable { mutableStateOf(profile.avatarUri) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<ActionFeedback?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            avatarUri = uri.toString()
            feedback = null
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy }), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("名字与头像", style = MaterialTheme.typography.headlineSmall)
            Text("设置本机显示的名字和头像，不会修改服务端角色设定。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ProfileAvatar(name.text, avatarUri, Modifier.size(72.dp))
                Column {
                    TextButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !busy) { Text("从本地选择头像") }
                    if (avatarUri != null) TextButton(onClick = { avatarUri = null }, enabled = !busy) { Text("移除头像") }
                }
            }
            OutlinedTextField(name, { if (it.text.length <= 40) name = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                label = { Text("显示名称") }, placeholder = { Text("未设置名称") }, supportingText = { Text("${name.text.length}/40 · 留空可清除名称") }, shape = RoundedCornerShape(15.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            feedback?.let { FeedbackNotice(it) }
            Button(onClick = {
                busy = true
                feedback = null
                scope.launch {
                    try {
                        val result = uiActionFeedback { appState.saveCompanionProfile(name.text.trim(), avatarUri) }
                        feedback = result
                        if (result.success) onDismiss()
                    }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "保存中…" else "保存资料") }
        }
    }
}
