package com.tauru.astrbotphoneagent.logic

import android.content.Context
import android.util.Log
import com.tauru.astrbotphoneagent.data.ActionFeedback
import com.tauru.astrbotphoneagent.data.HealthConnectState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/** Opt-in summary upload. Revocation/disable never reuses a cached health payload. */
internal class HealthConnectSync internal constructor(
    private val config: HealthSyncStorage,
    private val reader: HealthSummaryReader,
    private val remote: HealthSyncRemote,
) {
    constructor(context: Context, config: LogicConfig) : this(
        object : HealthSyncStorage {
            override suspend fun healthSettings() = config.healthSettings()
            override suspend fun update(updates: Map<String, String>) = config.update(updates)
        },
        HealthConnectReader(context.applicationContext), HealthSyncTransport(context.applicationContext, config),
    )
    private val mutex = Mutex()
    private val _state = MutableStateFlow(HealthConnectState())
    val state = _state.asStateFlow()
    private var lastAttempt = 0L

    suspend fun refresh(): ActionFeedback = withContext(Dispatchers.IO) {
        try {
            val available = reader.available()
            val granted = if (available) reader.grantedTypes() else emptySet()
            val settings = config.healthSettings()
            _state.update { it.copy(originPackage = settings.originPackage, available = available,
                enabled = settings.enabled, grantedTypes = granted, permissionsKnown = available,
                lastUploadAtEpochMs = settings.lastUpload, clearPending = settings.clearPending,
                lastAttemptAtEpochMs = settings.lastAttempt,
                lastAttemptStatus = settings.lastAttemptStatus,lastAttemptError=healthAttemptMessage(settings.lastAttemptStatus),
                latestDate=if(settings.lastAttemptStatus=="ok")settings.lastRecordDate else "",
                error = if (!available) "请安装或更新 Health Connect" else if (granted.isEmpty()) "尚未授予健康读取权限" else null) }
            ActionFeedback(available, if (available) "权限状态已刷新" else "Health Connect 暂不可用")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            val message = "暂时无法确认健康权限，请打开 Health Connect 后回到 App 重试"
            _state.update { it.copy(permissionsKnown = false, error = message) }
            ActionFeedback(false, message)
        }
    }

    suspend fun setSource(packageName: String): ActionFeedback = withContext(Dispatchers.IO) {
        val selected = packageName.trim()
        if (!HealthSource.isValid(selected)) return@withContext ActionFeedback(false, "请输入有效的 Android 应用包名")
        var syncAfterClear = false
        val changed = mutex.withLock {
            val previous = config.healthSettings()
            if (previous.originPackage == selected) return@withLock ActionFeedback(true, "当前来源未变更")
            config.update(mapOf(
                "health_origin_package" to selected,
                "health_clear_pending" to "true",
                "health_last_upload" to "0",
                "health_last_attempt" to "0","health_last_attempt_status" to "","health_last_record_date" to "",
            ))
            syncAfterClear = previous.enabled
            _state.update { it.copy(originPackage = selected, latestMetrics = emptyMap(), latestDate = "",
                lastReadAtEpochMs = 0, lastUploadAtEpochMs = 0, clearPending = true,
                lastAttemptAtEpochMs=0,lastAttemptStatus="",lastAttemptError=null,serverSourceMode = "", error = null) }
            val cleared = clearRemote()
            if (!cleared.success) return@withLock cleared
            ActionFeedback(true, "已切换为 ${HealthSource.label(selected)}，旧来源快照已清除")
        }
        if (syncAfterClear && changed.success) sync(force = true) else changed
    }

    suspend fun setEnabled(enabled: Boolean): ActionFeedback = withContext(Dispatchers.IO) {
        if (enabled) {
            mutex.withLock {
                config.update(mapOf("health_connect_enabled" to "true"))
                _state.update { it.copy(enabled = true) }
            }
            sync(force = true)
        } else {
            mutex.withLock {
                // Persist opt-out and pending deletion together, including process death.
                config.update(mapOf("health_connect_enabled" to "false", "health_clear_pending" to "true"))
                _state.update { it.copy(enabled = false, latestMetrics = emptyMap(), latestDate = "", clearPending = true) }
                clearRemote()
            }
        }
    }

    private suspend fun clearRemote(): ActionFeedback {
        return try {
            remote.clear()
            config.update(mapOf("health_clear_pending" to "false", "health_last_upload" to "0","health_last_attempt" to "0",
                "health_last_attempt_status" to "","health_last_record_date" to ""))
            _state.update { it.copy(clearPending = false, lastUploadAtEpochMs = 0,lastAttemptAtEpochMs=0,
                lastAttemptStatus="",lastAttemptError=null,latestDate="",error = null) }
            ActionFeedback(true, "服务器健康快照已清除")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            _state.update { it.copy(error = "服务器旧快照尚未清除；新数据读取已暂停，连接恢复后重试") }
            ActionFeedback(false, "服务器清除请求待重试，暂不读取或上传新数据")
        }
    }

    suspend fun sync(force: Boolean = true): ActionFeedback = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (config.healthSettings().clearPending) {
                val cleared = clearRemote()
                if (!cleared.success) return@withLock cleared
            }
            val settings = config.healthSettings()
            if (!settings.enabled) return@withLock ActionFeedback(false, "请先开启健康同步")
            val originPackage = settings.originPackage
            if (!HealthSource.isValid(originPackage)) return@withLock ActionFeedback(false, "所选应用包名无效")
            val now = System.currentTimeMillis()
            if (!force && now - lastAttempt < 15 * 60_000) return@withLock ActionFeedback(true, "")
            lastAttempt = now
            config.update(mapOf("health_last_attempt" to now.toString(),"health_last_attempt_status" to "in_progress"))
            _state.update { it.copy(syncing = true, enabled = true, error = null,lastAttemptAtEpochMs=now,lastAttemptError=null,lastAttemptStatus="in_progress") }
            var types = emptySet<String>()
            var permissionsKnown = false
            var rows = emptyList<HealthDay>()
            var status = "ok"
            var readError: String? = null
            try {
                withTimeout(60_000) {
                    if (!reader.available()) { status = "unavailable"; readError = "Health Connect 暂不可用" }
                    else {
                        types = reader.grantedTypes()
                        permissionsKnown = true
                        if (types.isEmpty()) { status = "permission_denied"; readError = "请先授予健康读取权限" }
                        else {
                            rows = reader.read(types, ZoneId.systemDefault(), originPackage)
                            if (reader.grantedTypes() != types) { rows = emptyList(); status = "permission_denied"; readError = "读取期间权限已变化，请重新同步" }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                status = "read_failed"; rows = emptyList(); readError = "读取超时，请保持 App 在前台重试"
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(syncing = false) }; throw cancelled
            } catch (_: SecurityException) {
                status = "permission_denied"; rows = emptyList(); readError = "健康读取被拒绝，请在 App 前台重新授权或同步"
            } catch (error: Exception) {
                status = "read_failed"; rows = emptyList()
                // Fixed operation/class only: never log records, exception messages or payloads.
                val operation = (error as? HealthReadException)?.operation ?: "权限状态"
                val reason = error.cause ?: error
                Log.w("PhoneHealthSync", "read_failed operation=$operation exception=${reason.javaClass.simpleName}")
                readError = if (reason.javaClass.simpleName in setOf("RemoteException", "DeadObjectException"))
                    "Health Connect 连接中断，请先打开 Health Connect 再回到 App 重试"
                    else "$operation 读取失败，请保持 App 在前台后重试"
            }
            try {
                if (!config.healthSettings().enabled) return@withLock ActionFeedback(false, "健康同步已关闭")
                if (config.healthSettings().originPackage != originPackage) return@withLock ActionFeedback(false, "健康来源已变更，请重新同步")
                val captured = Instant.now()
                _state.update { it.copy(available = status != "unavailable", grantedTypes = types, permissionsKnown = permissionsKnown,
                    originPackage = originPackage,
                    lastReadAtEpochMs = captured.toEpochMilli(), latestDate = rows.firstOrNull()?.date.orEmpty(),
                    latestMetrics = rows.firstOrNull()?.metrics.orEmpty(), error = readError) }
                val sourceMode = remote.upload(HealthUpload(originPackage, captured, ZoneId.systemDefault(), status, types, rows))
                val healthSucceeded = status == "ok" && rows.isNotEmpty()
                val attemptStatus=if(status=="ok"&&!healthSucceeded)"empty" else status
                val updates=mutableMapOf("health_last_attempt_status" to attemptStatus)
                if(healthSucceeded){updates["health_last_upload"]=captured.toEpochMilli().toString();updates["health_last_record_date"]=rows.first().date}
                config.update(updates)
                _state.update { it.copy(lastUploadAtEpochMs = if(healthSucceeded)captured.toEpochMilli() else it.lastUploadAtEpochMs, serverSourceMode = sourceMode,
                    lastAttemptStatus=attemptStatus,lastAttemptError = readError?:healthAttemptMessage(attemptStatus),
                    error = readError ?: if (rows.isEmpty()) "所选健康 App 暂无可同步记录" else null) }
                ActionFeedback(status == "ok", readError ?: if (rows.isEmpty()) "已连接，所选健康 App 暂无记录" else "健康摘要已同步到服务器")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val detail = if (error is AstrBotClient.ApiException) error.message ?: "服务器请求失败" else "上传失败，请检查 App 配置与网络"
                runCatching{config.update(mapOf("health_last_attempt_status" to "upload_failed"))}
                _state.update { it.copy(error = detail,lastAttemptError=detail,lastAttemptStatus="upload_failed") }
                ActionFeedback(false, if (readError == null) "读取完成，上传未成功：$detail" else "$readError；状态上传未成功：$detail")
            } finally { _state.update { it.copy(syncing = false) } }
        }
    }
}
