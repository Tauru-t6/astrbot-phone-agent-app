package com.tauru.astrbotphoneagent.logic

import com.tauru.astrbotphoneagent.data.HealthConnectState
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal fun healthAttemptMessage(status:String):String?=when(status){
    "ok",""->null
    "empty"->"来源健康 App 暂无可同步记录"
    "permission_denied"->"最近一次健康读取未获授权，请重新检查权限"
    "unavailable"->"Health Connect 暂不可用"
    "read_failed"->"最近一次健康读取失败，请保持 App 在前台重试"
    "upload_failed"->"最近一次上传未成功，请检查连接后重试"
    "in_progress"->"上次同步尚未完成，请重试"
    else->"最近同步状态未知，请重试"
}

/** A recent upload timestamp alone never proves the source records are fresh. */
internal fun healthIsFresh(state:HealthConnectState,now:Long=System.currentTimeMillis(),today:LocalDate=LocalDate.now()):Boolean {
    if(!state.enabled||state.clearPending||!state.permissionsKnown||state.lastAttemptStatus!="ok"||state.lastAttemptError!=null||state.error!=null)return false
    if(state.lastUploadAtEpochMs<=0||now-state.lastUploadAtEpochMs !in 0..(3*60*60_000L))return false
    val date=runCatching{LocalDate.parse(state.latestDate)}.getOrNull()?:return false
    return ChronoUnit.DAYS.between(date,today) in 0..1
}
