package com.tauru.astrbotphoneagent.logic

import java.net.URI
import kotlinx.serialization.json.*

internal data class PairingPayload(val endpoint: String, val code: String, val expiresAt: Double, val deviceKind: String)

/** Parse only the pairing envelope; no network or credential persistence here. */
internal object PairingPayloadParser {
    fun parse(raw: String, deviceKind: String, now: Double = System.currentTimeMillis() / 1000.0): PairingPayload {
        require(raw.length in 1..4096) { "配对内容大小无效" }
        val value = Json.parseToJsonElement(raw).jsonObject
        require(value["type"]?.jsonPrimitive?.content == "phone_control_pairing" &&
            value["version"]?.jsonPrimitive?.intOrNull == 1) { "不是 Phone Control 配对码" }
        val endpoint = value["endpoint"]?.jsonPrimitive?.content.orEmpty().trimEnd('/')
        require(validEndpoint(endpoint)) { "配对地址须为 HTTPS 或内网 IP 地址" }
        val code = value["code"]?.jsonPrimitive?.content.orEmpty()
        require(Regex("pcp_[A-Za-z0-9_-]{32,160}").matches(code)) { "配对码格式无效" }
        val expires = value["expires_at"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        require(expires.isFinite() && expires > now && expires <= now + 600) { "配对码已过期，请在服务器重新生成" }
        val kind = value["device_kind"]?.jsonPrimitive?.content.orEmpty()
        require(kind == deviceKind && kind in setOf("personal", "bot")) { "配对码用途与当前 App 不一致" }
        return PairingPayload(endpoint, code, expires, kind)
    }

    fun validEndpoint(value: String): Boolean = runCatching {
        val uri = URI(value)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.port == 0 || uri.port > 65535 ||
            uri.path !in setOf("", "/")) return@runCatching false
        if (uri.scheme == "https") return@runCatching true
        val host = uri.host.removePrefix("[").removeSuffix("]").lowercase()
        if (host == "localhost" || host == "::1" || host.startsWith("fd") && host.contains(':')) return@runCatching true
        val octets = host.split('.').map { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return@runCatching false
        val a = octets[0]!!; val b = octets[1]!!
        a == 10 || a == 127 || a == 192 && b == 168 || a == 172 && b in 16..31 || a == 100 && b in 64..127
    }.getOrDefault(false)
}
