package com.tauru.astrbotphoneagent.logic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/** Serializes phone mutations and deduplicates retries across transports/restarts. */
class CommandRunner(private val context: Context, private val executor: ShizukuCommandExecutor) {
    var onDangerousCommand: (suspend (CommandEnvelope) -> Boolean)? = null
    var onCommandStarted: ((CommandEnvelope) -> Unit)? = null
    var onCommandFinished: ((CommandEnvelope, ResultEnvelope) -> Unit)? = null
    private val gate = Mutex()
    private val journal = AtomicFile(File(context.filesDir, "command_receipts.json"))
    private val receipts: JSONObject = runCatching { JSONObject(String(journal.readFully(), Charsets.UTF_8)) }.getOrDefault(JSONObject())

    suspend fun run(envelope: CommandEnvelope): ResultEnvelope = withContext(Dispatchers.IO) {
        gate.withLock {
            val identity = protocolJson.encodeToString(CommandEnvelope.serializer(), envelope)
            val saved = receipts.optJSONObject(envelope.commandId)
            if (saved != null) {
                if (saved.optString("identity") != identity) return@withLock envelope.result(false, code = "id_conflict", error = "命令 ID 已被使用")
                val cached = runCatching { protocolJson.decodeFromString(ResultEnvelope.serializer(), saved.getString("result")) }.getOrNull()
                if (cached != null) return@withLock cached
            }
            val invalid = commandValidationError(envelope, System.currentTimeMillis() / 1000.0)
            if (invalid != null) return@withLock envelope.result(false, code = "invalid_command", error = invalid)
            val interrupted = envelope.result(false, code = "interrupted", error = "上次执行被中断，结果未知，请检查后重新发起")
            // Mark before changing phone state. A killed process must not replay it.
            record(envelope, identity, interrupted)
            runCatching { onCommandStarted?.invoke(envelope) }
            val remaining = ((envelope.createdAtEpochSec + envelope.deadline) * 1000 - System.currentTimeMillis()).toLong().coerceAtLeast(1)
            val result = try {
                withTimeoutOrNull(remaining) {
                    if ((envelope.dangerous || envelope.action in DANGEROUS_ACTIONS) &&
                        onDangerousCommand?.invoke(envelope) != true) {
                        envelope.result(false, code = "denied", error = "操作未在手机上获得确认")
                    } else execute(envelope)
                } ?: envelope.result(false, code = "timeout", error = "命令等待或执行超时")
            } catch (cancelled: CancellationException) {
                val cancelledResult = envelope.result(false, code = "cancelled", error = "任务已取消")
                record(envelope, identity, cancelledResult)
                runCatching { onCommandFinished?.invoke(envelope, cancelledResult) }
                throw cancelled
            } catch (_: Exception) {
                envelope.result(false, code = "internal", error = "手机执行失败，请检查权限和设备状态")
            }
            record(envelope, identity, result)
            runCatching { onCommandFinished?.invoke(envelope, result) }
            result
        }
    }

    private fun record(envelope: CommandEnvelope, identity: String, result: ResultEnvelope) {
        receipts.put(envelope.commandId, JSONObject().put("identity", identity)
            .put("result", protocolJson.encodeToString(ResultEnvelope.serializer(), result)))
        // Results are only reusable within the bounded command lifetime.
        val cutoff = System.currentTimeMillis() / 1000.0 - 86400
        receipts.keys().asSequence().toList().forEach { key ->
            val entry = receipts.optJSONObject(key) ?: return@forEach
            val value = runCatching { protocolJson.decodeFromString(ResultEnvelope.serializer(), entry.optString("result")) }.getOrNull()
            if (value != null && value.finishedAtEpochSec < cutoff) receipts.remove(key)
        }
        val stream = journal.startWrite()
        try { stream.write(receipts.toString().toByteArray(Charsets.UTF_8)); journal.finishWrite(stream) }
        catch (error: Exception) { journal.failWrite(stream); throw error }
    }

    private suspend fun execute(envelope: CommandEnvelope): ResultEnvelope = when (envelope.action) {
        "location" -> runLocation(envelope)
        "send_notification" -> {
            val posted = Notifier.post(context, envelope.args["title"].orEmpty().ifBlank { "提醒" },
                envelope.args["text"].orEmpty(), envelope.args["notification_id"] ?: envelope.commandId)
            envelope.result(posted, if (posted) """{"posted":true}""" else null,
                if (posted) "" else "denied", if (posted) "" else "通知权限或通知渠道未开启")
        }
        else -> {
            val outcome = runInterruptible(Dispatchers.IO) { executor.execute(envelope.action, envelope.args) }
            when (outcome) {
                is ShizukuCommandExecutor.CommandOutcome.Ok -> envelope.result(true, outcome.output)
                is ShizukuCommandExecutor.CommandOutcome.Fail -> envelope.result(false, code = outcome.errorCode, error = outcome.message)
            }
        }
    }

    private suspend fun runLocation(envelope: CommandEnvelope): ResultEnvelope {
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!coarse && !fine) return envelope.result(false, code = "denied", error = "请在系统设置中授予定位权限")
        val highAccuracy = envelope.args["high_accuracy"].toBoolean()
        if (highAccuracy && !fine) return envelope.result(false, code = "denied", error = "精确定位需要精确位置权限")
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            highAccuracy && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return envelope.result(false, code = "unavailable", error = "系统定位未开启")
        }
        val location = withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val signal = androidx.core.os.CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(manager, provider, signal,
                        ContextCompat.getMainExecutor(context)) { fix -> if (continuation.isActive) continuation.resume(fix) }
                } catch (_: SecurityException) { if (continuation.isActive) continuation.resume(null) }
            }
        } ?: return envelope.result(false, code = "timeout", error = "20 秒内未获取到位置")
        return envelope.result(true, JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
            .put("coord_type", "wgs84").put("accuracy_m", location.accuracy.toInt()).put("provider", provider).toString())
    }

    private fun CommandEnvelope.result(success: Boolean, output: String? = null, code: String = "", error: String = "") =
        ResultEnvelope(commandId = commandId, success = success, action = action, output = output,
            error = error.take(500), errorCode = code, finishedAtEpochSec = System.currentTimeMillis() / 1000.0)

    companion object { fun newCommandId(): String = "c-" + UUID.randomUUID().toString().replace("-", "").take(12) }
}
