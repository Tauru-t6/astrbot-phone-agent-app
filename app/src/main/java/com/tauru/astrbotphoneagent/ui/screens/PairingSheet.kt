package com.tauru.astrbotphoneagent.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.tauru.astrbotphoneagent.BuildConfig
import com.tauru.astrbotphoneagent.data.*
import com.tauru.astrbotphoneagent.logic.PairingPayloadParser
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairingSheet(appState: AppState,onDismiss:()->Unit,onPaired:()->Unit=onDismiss) {
    var raw by remember { mutableStateOf("") } // single-use secret is never saved across recreation
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<ActionFeedback?>(null) }
    val scope=rememberCoroutineScope()
    val kind=if(BuildConfig.IS_BOT_DEVICE) "bot" else "personal"
    val parsed=remember(raw) { runCatching { PairingPayloadParser.parse(raw,kind) } }
    val scanner=rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { raw=it;feedback=null }
    }
    ModalBottomSheet(onDismissRequest={if(!busy)onDismiss()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("扫码配对",style=MaterialTheme.typography.headlineSmall)
            Text("在服务器控制台生成对应设备的短时配对码。扫描后先核对服务器与设备用途，再建立连接。")
            Button(enabled=!busy,onClick={scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("扫描 Phone Control 配对码").setBeepEnabled(false).setOrientationLocked(false))},modifier=Modifier.fillMaxWidth()) { Text("扫描配对二维码") }
            OutlinedTextField(raw,{raw=it.take(4096);feedback=null},label={Text("也可粘贴完整配对内容")},
                enabled=!busy,modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=6)
            parsed.getOrNull()?.let {
                Text("服务器：${it.endpoint}",style=MaterialTheme.typography.titleSmall)
                Text("用途：${if(it.deviceKind=="bot")"Bot 专用手机" else "个人手机"}。配对成功后保存独立设备凭据，可由服务器撤销。")
            }
            if(raw.isNotBlank()&&parsed.isFailure) Text("配对内容无效、已过期或用途不匹配，请重新生成。",color=MaterialTheme.colorScheme.error)
            feedback?.let { FeedbackNotice(it) }
            Button(enabled=!busy&&parsed.isSuccess,onClick={busy=true;scope.launch{
                try { feedback=appState.pairDevice(raw);if(feedback?.success==true){raw="";onPaired()} }
                finally { busy=false }
            }},modifier=Modifier.fillMaxWidth()) { Text(if(busy)"正在配对…" else "确认服务器并配对") }
        }
    }
}
