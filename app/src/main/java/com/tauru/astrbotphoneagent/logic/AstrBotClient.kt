package com.tauru.astrbotphoneagent.logic

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.ConcurrentHashMap

/**
 * AstrBot OpenAPI client.
 *
 * Chat: POST /api/v1/chat with SSE streaming. Emits [ChatStreamEvent] pieces
 * as they arrive; the full text arrives with the final Complete event.
 * The user configures the chat identity to match their server session.
 */
class AstrBotClient(private val config: LogicConfig) {

    sealed interface ChatStreamEvent {
        data class Piece(val text: String) : ChatStreamEvent
        data class Complete(val text: String) : ChatStreamEvent
        data class Failed(val message: String) : ChatStreamEvent
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    fun chatStream(message: String): Flow<ChatStreamEvent> = callbackFlow {
        val url = config.astrbotBaseUrl.first().trimEnd('/') + "/api/v1/chat"
        val apiKey = config.apiKey()
        val username = config.username()
        val session = config.session()
        val payload = JSONObject()
            .put("username", username)
            .put("session_id", session)
            .put("message", message)
            .toString()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(ChatStreamEvent.Failed("网络连接失败，请检查服务器地址和网络"))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        trySend(ChatStreamEvent.Failed(httpError(it.code)))
                        close()
                        return
                    }
                    val source = it.body?.source() ?: run {
                        trySend(ChatStreamEvent.Failed("empty response body"))
                        close()
                        return
                    }
                    try {
                        var completeText: String? = null
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data.isEmpty()) continue
                            val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
                            when (event.optString("type")) {
                                "plain" -> {
                                    val piece = event.optString("data")
                                    if (piece.isNotEmpty()) trySend(ChatStreamEvent.Piece(piece))
                                }
                                "complete" -> {
                                    completeText = event.optString("data")
                                    trySend(ChatStreamEvent.Complete(completeText.orEmpty()))
                                }
                                "end" -> {
                                    if (completeText == null) {
                                        trySend(ChatStreamEvent.Failed("stream ended without a complete event"))
                                    }
                                    break
                                }
                            }
                        }
                        if (completeText == null && !isClosedForSend) {
                            trySend(ChatStreamEvent.Failed("stream closed before completion"))
                        }
                    } catch (e: IOException) {
                        trySend(ChatStreamEvent.Failed("连接中断，请稍后重试"))
                    } finally {
                        close()
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }

    /** POST plugin/device/register with this phone's direct endpoint. */
    suspend fun registerDevice(deviceId: String, baseUrl: String, tokenFingerprint: String, baseUrls: List<String> = listOf(baseUrl)): Boolean {
        val url = config.pluginUrl().trimEnd('/') + "/device/register"
        val payload = JSONObject()
            .put("device_id", deviceId)
            .put("base_url", baseUrl)
            .put("base_urls", org.json.JSONArray(baseUrls))
            .put("app_version", config.appVersion)
            .put("token_fingerprint", tokenFingerprint)
            .toString()
        val request = Request.Builder()
            .url(url)
            // The /api/plug router authenticates dashboard JWTs only.
            .header("Authorization", "Bearer ${config.pluginJwt()}")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return executeForSuccess(request)
    }

    /** Timeline sync: fetch items newer than [since] from the plugin. */
    suspend fun fetchTimeline(since: Double, limit: Int = 50): List<JSONObject> {
        val url = config.pluginUrl().trimEnd('/') +
            "/device/timeline?since=$since&limit=$limit"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.pluginJwt()}")
            .get()
            .build()
        val body = executeForBody(request) ?: return emptyList()
        val items = body.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
    }

    suspend fun fetchAppState(): JSONObject = pluginRequest("/app/state").also {
        if (it.optInt("api_version") < 2) throw ApiException("请先更新服务器插件")
    }

    suspend fun fetchReminders(): JSONObject = pluginRequest("/app/reminders")

    suspend fun createReminder(text: String, minutes: Int, requestId: String): JSONObject =
        pluginRequest("/app/reminders", JSONObject().put("text", text)
            .put("minutes", minutes).put("request_id", requestId))

    suspend fun cancelReminder(id: String): JSONObject =
        pluginRequest("/app/reminders/cancel", JSONObject().put("reminder_id", id))

    private suspend fun pluginRequest(path: String, payload: JSONObject? = null): JSONObject {
        val pluginUrl = config.pluginUrl()
        val token = config.pluginJwt()
        if (pluginUrl.isBlank() || token.isBlank()) throw ApiException("请先在设置中填写插件地址和令牌")
        val builder = Request.Builder().url(pluginUrl + path)
            .header("Authorization", "Bearer $token")
        if (payload == null) builder.get() else builder.post(payload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType()))
        val body = executeForBody(builder.build()) ?: throw ApiException("服务器返回了空响应")
        if (!body.optBoolean("success")) {
            throw ApiException(when (body.optString("error_code")) {
                "not_found" -> "这条提醒已不存在，请刷新列表"
                "invalid_minutes", "invalid_text", "validation_error", "invalid_request" -> "提醒内容或时间不符合要求"
                "too_many_reminders", "limit_reached", "reminder_limit" -> "待办提醒数量已达上限"
                "unauthorized", "forbidden" -> "认证失败，请检查插件令牌"
                else -> "服务器未完成请求，请稍后重试"
            })
        }
        return body
    }

    /** Relay queue: claim the next envelope for this device, if any. */
    suspend fun relayPoll(deviceId: String): CommandEnvelope? {
        val relayUrl = config.relayUrl()
        if (relayUrl.isBlank()) return null
        val url = relayUrl + "/poll"
        val token = config.relayTokenValue()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Relay-Device-ID", deviceId)
            .get()
            .build()
        val body = executeForBody(request) ?: return null
        val task = body.optJSONObject("task") ?: return null
        val message = task.optString("message")
        if (message.isBlank() || !message.trimStart().startsWith("{")) return null
        val envelope = runCatching {
            protocolJson.decodeFromString(CommandEnvelope.serializer(), message)
        }.getOrNull()
        val taskId = task.optString("id").ifBlank { task.optString("task_id") }
        if (envelope == null || taskId.isBlank()) return null
        relayTaskIds[envelope.commandId] = taskId
        return envelope
    }

    /** Relay queue: report a command result back to the plugin. */
    suspend fun relayReportResult(envelope: ResultEnvelope): Boolean {
        val url = config.relayUrl().trimEnd('/') + "/result"
        val token = config.relayTokenValue()
        val deviceId = DeviceIdentity.deviceId()
        val payload = JSONObject()
            .put("task_id", relayTaskIds[envelope.commandId] ?: return false)
            .put("success", envelope.success)
            .put("ai_response", protocolJson.encodeToString(ResultEnvelope.serializer(), envelope))
            .put("error", envelope.error)
            .toString()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Relay-Device-ID", deviceId)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return executeForSuccess(request).also { if (it) relayTaskIds.remove(envelope.commandId) }
    }

    /** Relay queue lease renewal for long-running commands. */
    suspend fun relayRenew(taskId: String): Boolean {
        val url = config.relayUrl().trimEnd('/') + "/renew"
        val token = config.relayTokenValue()
        val payload = JSONObject().put("task_id", taskId).toString()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Relay-Device-ID", DeviceIdentity.deviceId())
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return executeForSuccess(request)
    }

    private val relayTaskIds = ConcurrentHashMap<String, String>()

    fun relayTaskId(commandId: String): String? = relayTaskIds[commandId]

    private suspend fun executeForBody(request: Request): JSONObject? =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            call.timeout().timeout(25, TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ApiException("无法连接服务器，请检查地址和网络"))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = runCatching { it.body?.string() }.getOrNull()
                        if (!it.isSuccessful || body == null) {
                            if (continuation.isActive) continuation.resumeWithException(ApiException(httpError(it.code)))
                            return
                        }
                        val parsed = runCatching { JSONObject(body) }.getOrNull()
                        if (continuation.isActive) {
                            if (parsed == null) continuation.resumeWithException(ApiException("服务器响应格式不正确"))
                            else continuation.resume(parsed)
                        }
                    }
                }
            })
        }

    private suspend fun executeForSuccess(request: Request): Boolean {
        val body = executeForBody(request) ?: return false
        return body.optBoolean("success")
    }

    class ApiException(message: String) : IOException(message)

    private fun httpError(code: Int): String = when (code) {
        401, 403 -> "认证失败，请检查插件令牌或 API Key"
        404 -> "接口不存在，请检查插件地址和版本"
        else -> "服务器请求失败（HTTP $code）"
    }
}
