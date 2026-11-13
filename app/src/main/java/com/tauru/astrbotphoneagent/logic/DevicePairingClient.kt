package com.tauru.astrbotphoneagent.logic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.tauru.astrbotphoneagent.logic.AstrBotClient.ApiException

internal object DevicePairingClient {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun redeem(payload: PairingPayload, deviceId: String): Map<String,String> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("code",payload.code).put("device_id",deviceId).put("device_kind",payload.deviceKind)
        val request = Request.Builder().url(payload.endpoint + "/pair/redeem")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val source=response.body?.source() ?: throw ApiException("配对响应为空")
            source.request(32_769)
            val bytes=source.buffer.readByteArray(minOf(source.buffer.size,32_769L))
            if (bytes.size > 32_768) throw ApiException("配对响应过大")
            val result=runCatching { JSONObject(String(bytes,Charsets.UTF_8)) }.getOrNull()
                ?: throw ApiException("配对响应格式不正确")
            if (!response.isSuccessful || result.optBoolean("success") != true)
                throw ApiException(pairingFailure(result.optString("error_code")))
            val pluginUrl=result.optString("plugin_base_url").trimEnd('/')
            if (pluginUrl != payload.endpoint + "/device-api" || result.optString("device_id") != deviceId ||
                result.optString("device_kind") != payload.deviceKind) throw ApiException("配对返回了不匹配的设备或地址")
            val token=result.optString("device_token"); val shared=result.optString("shared_token")
            if (!Regex("pcd_[A-Za-z0-9_-]{32,160}").matches(token) || shared.length !in 32..512 || shared.any(Char::isISOControl))
                throw ApiException("配对未返回有效设备凭据")
            // Per-device pairing starts with a direct route. It never inherits
            // the global Dashboard or Relay credentials from a QR payload.
            mapOf("plugin_base_url" to pluginUrl,"plugin_jwt" to token,"shared_token" to shared,
                "relay_base_url" to "","relay_token" to "","astrbot_base_url" to "")
        }
    }
}

internal fun pairingFailure(code: String): String = when(code) {
    "pair_expired" -> "配对码已过期，请重新生成"
    "pair_used" -> "配对码已使用，请重新生成"
    "pair_expired_or_used" -> "配对码已过期或已使用，请重新生成"
    "device_already_exists" -> "设备已经登记，请在服务器生成替换此设备的配对码"
    "device_kind_mismatch" -> "设备用途不匹配，请生成对应版本的配对码"
    "device_revoked" -> "此设备凭据已撤销，请重新配对"
    "owner_reauthentication_required" -> "服务器需要管理员重新登录后恢复配对服务"
    else -> "配对失败，请核对服务器地址和配对码"
}
