package com.tauru.astrbotphoneagent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tauru.astrbotphoneagent.data.*
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskLibrarySheet(appState:AppState,onDismiss:()->Unit) {
    val library by appState.taskLibrary.collectAsState(initial=TaskLibraryState())
    val overview by appState.autonomyOverview.collectAsState(initial=AutonomyOverview())
    var selected by remember { mutableStateOf<TaskTemplate?>(null) }
    var schedule by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<ActionFeedback?>(null) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit){appState.refreshTaskLibrary()}
    fun act(block:suspend()->ActionFeedback){if(!busy){busy=true;scope.launch{try{feedback=block()}finally{busy=false}}}}
    ModalBottomSheet(onDismissRequest={if(!busy)onDismiss()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("收藏与自主日程",style=MaterialTheme.typography.headlineSmall)
            Text("从任务记录收藏已批准的范围，以后只替换参数。自主日程只运行在明确授权的 Bot 手机。")
            feedback?.let{FeedbackNotice(it)}
            library.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            TextButton(enabled=!busy,onClick={act{appState.refreshTaskLibrary()}}){Text("刷新")}
            if(library.templates.isEmpty())Text("还没有收藏。先完成任务或批准任务，再在执行记录点「收藏为模板」。")
            library.templates.forEach { template ->
                HorizontalDivider()
                Text(template.name,style=MaterialTheme.typography.titleMedium)
                Text(template.goal,style=MaterialTheme.typography.bodyMedium)
                Text("${template.packageName} · ${if(template.hasRecipe)"已记住验证步骤" else "由模型逐步决定"}",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(enabled=!busy,onClick={selected=template;schedule=false}){Text("填写参数并执行")}
                    TextButton(enabled=!busy&&library.schedulesEnabled,onClick={selected=template;schedule=true}){Text("设为日程")}
                    TextButton(enabled=!busy,onClick={act{appState.deleteTaskTemplate(template.id)}}){Text("删除")}
                }
            }
            HorizontalDivider();Text("自主日程",style=MaterialTheme.typography.titleMedium)
            if(!library.schedulesEnabled)Text("服务器尚未开启自主日程。收藏仍可手动发起并逐次审批。",style=MaterialTheme.typography.bodySmall)
            library.schedules.forEach { item ->
                Text("${item.name} · ${if(item.enabled)"启用" else "暂停"}",style=MaterialTheme.typography.titleSmall)
                Text("每天 ${item.times.joinToString()} · ${item.timezone}\n剩余 ${item.remainingRuns} 次 / ${item.remainingModelCalls} 次模型调用\n状态：${item.lastStatus.ifBlank{"等待首次运行"}}",style=MaterialTheme.typography.bodySmall)
                Text("截止 ${scheduleExpiry(item.expiresAt,item.timezone)} · 安静时段 ${item.quietStart.ifBlank{"未设置"}}–${item.quietEnd.ifBlank{"未设置"}}\n每次最多 ${item.perRunModelCalls} 次模型调用；${if(item.notify)"结果变化时通知绑定私聊" else "不发送私聊通知"}",style=MaterialTheme.typography.bodySmall)
                Row{
                    if(item.enabled)TextButton(enabled=!busy,onClick={act{appState.stopRoutineSchedule(item.id)}}){Text("暂停")}
                    TextButton(enabled=!busy,onClick={act{appState.stopRoutineSchedule(item.id,true)}}){Text("删除日程")}
                }
            }
        }
    }
    selected?.let { template -> TemplateExecuteDialog(template,overview.devices,schedule,busy,
        onDismiss={selected=null},onRun={parameters,device->selected=null;act{appState.proposeTaskTemplate(template.id,parameters,device)}},
        onSchedule={request->selected=null;act{appState.saveRoutineSchedule(request)}}) }
}

@Composable
private fun TemplateExecuteDialog(template:TaskTemplate,devices:List<RegisteredPhone>,scheduled:Boolean,busy:Boolean,
    onDismiss:()->Unit,onRun:(Map<String,String>,String)->Unit,onSchedule:(RoutineScheduleRequest)->Unit) {
    val choices=devices.filter{if(scheduled)it.role=="bot" else it.role in setOf("bot","personal")}
    var target by remember(template.id,scheduled){mutableStateOf(choices.firstOrNull{it.deviceId==template.deviceId}?.deviceId?:choices.firstOrNull()?.deviceId.orEmpty())}
    var parameters by remember(template.id){mutableStateOf(template.parameters.associate{it.name to it.defaultValue})}
    var time by remember{mutableStateOf("18:00")};var days by remember{mutableStateOf("7")}
    var modelLimit by remember{mutableStateOf("60")};var approved by remember{mutableStateOf(false)}
    var notify by remember{mutableStateOf(false)}
    val count=days.toIntOrNull();val budget=modelLimit.toIntOrNull()
    val expiry=remember(count){System.currentTimeMillis()/1000.0+(count?:0)*86400.0}
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text(if(scheduled)"批准限额日程" else "使用收藏任务")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(parameters.entries.fold(template.scopeDescription){text,(key,value)->text.replace("{{$key}}",value)})
            if(template.resultFields.isNotEmpty())Text("返回最多 ${template.resultCount} 项，字段 ${template.resultFields.joinToString()}；结果由新页面再次核验后保存。")
            if(template.rememberRecipe)Text("成功后保存验证过的控件步骤，后续复用前仍检查当前页面。")
            choices.forEach{phone->Row{RadioButton(target==phone.deviceId,{target=phone.deviceId;approved=false});Text("${phone.label} · ${deviceRoleLabel(phone.role)}")}}
            if(choices.isEmpty())Text("没有符合用途的已注册设备",color=MaterialTheme.colorScheme.error)
            template.parameters.forEach{p->OutlinedTextField(parameters[p.name].orEmpty(),{parameters=parameters+(p.name to it.take(p.maxLength));approved=false},label={Text(p.name)},singleLine=true)}
            if(scheduled){
                OutlinedTextField(time,{time=it.take(5);approved=false},label={Text("每天时间 HH:MM")},singleLine=true)
                OutlinedTextField(days,{days=it.take(2);approved=false},label={Text("有效天数 / 总次数（1–30，每天最多一次）")},singleLine=true)
                OutlinedTextField(modelLimit,{modelLimit=it.take(4);approved=false},label={Text("整个日程模型调用总上限")},singleLine=true)
                Text("时区 ${ZoneId.systemDefault().id}；23:00–07:00 安静时段；每次最多 8 次模型调用。模型调用次数不是货币费用。设备或范围变化需重新授权。")
                if(count in 1..30)Text("截止时间：${scheduleExpiry(expiry,ZoneId.systemDefault().id)}；最多 $count 次。")
                Row{Checkbox(notify,{notify=it;approved=false});Text("仅结果变化时通知已绑定的私聊")}
                Row{Checkbox(approved,{approved=it});Text("我批准此设备、任务、参数、时段及次数范围")}
            }else Text("提交会生成新的待确认任务，不会立即操作手机。")
        }},confirmButton={TextButton(enabled=!busy&&target.isNotBlank()&&(!scheduled||(approved&&count in 1..30&&budget in 8..1000&&Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(time))),onClick={
            if(scheduled)onSchedule(RoutineScheduleRequest(template.id,parameters,target,time,ZoneId.systemDefault().id,count!!,budget!!,8,expiry,notify=notify))
            else onRun(parameters,target)
        }){Text(if(scheduled)"批准并保存日程" else "生成待确认任务")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

private fun scheduleExpiry(epoch:Double,zone:String):String=runCatching{
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of(zone)).format(Instant.ofEpochSecond(epoch.toLong()))
}.getOrDefault("未知")

@Composable
internal fun SaveTemplateDialog(appState:AppState,run:TaskRunSummary,onDismiss:()->Unit,onFeedback:(ActionFeedback)->Unit) {
    var name by remember{mutableStateOf(run.goal.take(60))};var original by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("收藏为任务模板")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(name,{name=it.take(60)},label={Text("收藏名称")})
        OutlinedTextField(original,{original=it.take(200)},label={Text("可替换的关键词原文（可选）")})
        Text("填写后将该原文保存为 query 参数；应用、设备和动作范围不会被参数替换。")
    }},confirmButton={TextButton(enabled=!busy&&name.isNotBlank(),onClick={busy=true;scope.launch{
        try{val result=appState.saveTaskTemplate(run.runId,name,if(original.isBlank())emptyMap()else mapOf("query" to original));onFeedback(result);if(result.success)onDismiss()}
        finally{busy=false}
    }}){Text("收藏")}},dismissButton={TextButton(enabled=!busy,onClick=onDismiss){Text("取消")}})
}
