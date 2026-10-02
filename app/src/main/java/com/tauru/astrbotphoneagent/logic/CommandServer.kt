package com.tauru.astrbotphoneagent.logic

import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/** Authenticated command endpoint, with byte-correct HTTP framing. */
class CommandServer(
    private val config: LogicConfig,
    private val executor: CommandRunner,
    private val scope: CoroutineScope,
) {
    private var serverJob: Job? = null
    @Volatile private var socket: ServerSocket? = null

    @Synchronized
    fun start(port: Int = 8260): Int {
        socket?.takeIf { !it.isClosed }?.let { return it.localPort }
        val listener = try {
            ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress("0.0.0.0", port), 8) }
        } catch (_: Exception) { return -1 }
        socket = listener
        serverJob = scope.launch(Dispatchers.IO) {
            while (isActive && !listener.isClosed) {
                val client = try { listener.accept() } catch (_: Exception) { break }
                launch {
                    try { handle(client) } catch (_: Exception) { runCatching { client.close() } }
                }
            }
        }
        return listener.localPort
    }

    fun isRunning(): Boolean = socket?.isClosed == false

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        serverJob?.cancel()
        serverJob = null
    }

    private suspend fun handle(client: Socket) {
        client.use { peer ->
            peer.soTimeout = 15_000
            val request = try { HttpCommandRequest.read(peer.getInputStream()) }
            catch (error: HttpCommandRequest.Invalid) {
                respond(peer, error.status, JSONObject().put("success", false).put("error", error.message).toString())
                return
            } catch (_: Exception) {
                respond(peer, 400, """{"success":false,"error":"invalid HTTP request"}""")
                return
            }
            val token = config.sharedToken()
            val expected = "Bearer $token".toByteArray(Charsets.UTF_8)
            val supplied = request.headers["authorization"].orEmpty().toByteArray(Charsets.UTF_8)
            if (token.isBlank() || !MessageDigest.isEqual(expected, supplied)) {
                respond(peer, 401, """{"success":false,"error":"unauthorized"}""")
                return
            }
            when {
                request.method == "GET" && request.path in setOf("/health", "/ping") ->
                    respond(peer, 200, JSONObject().put("success", true).put("service", "phone-buddy")
                        .put("app_version", config.appVersion).toString())
                request.method == "POST" && request.path == "/command" -> handleCommand(peer, request.body.toString(Charsets.UTF_8))
                else -> respond(peer, 404, """{"success":false,"error":"not found"}""")
            }
        }
    }

    private suspend fun handleCommand(peer: Socket, body: String) {
        val envelope = try { protocolJson.decodeFromString(CommandEnvelope.serializer(), body) }
        catch (_: Exception) {
            respond(peer, 400, """{"success":false,"error_code":"bad_args","error":"invalid command envelope"}""")
            return
        }
        val result = executor.run(envelope)
        respond(peer, 200, protocolJson.encodeToString(ResultEnvelope.serializer(), result))
    }

    private fun respond(peer: Socket, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Error" }
        peer.getOutputStream().apply {
            write("HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            write(bytes); flush()
        }
    }

    companion object {
        fun tokenFingerprint(token: String): String = "sha256:" + MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)

        /** Discover LAN endpoints first, then Tailscale. No deployment addresses in the APK. */
        fun directAddresses(): List<String> {
            val candidates = runCatching {
                NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp }
                    .flatMap { it.inetAddresses.asSequence() }
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && it is java.net.Inet4Address }
                    .mapNotNull { it.hostAddress }.toList()
            }.getOrDefault(emptyList())
            val tailscale = candidates.filter { address ->
                val parts = address.split('.')
                parts.firstOrNull() == "100" && (parts.getOrNull(1)?.toIntOrNull() ?: 0) in 64..127
            }
            val lan = candidates.filter { address ->
                val parts = address.split('.')
                address.startsWith("192.168.") || address.startsWith("10.") ||
                    (parts.firstOrNull() == "172" && (parts.getOrNull(1)?.toIntOrNull() ?: 0) in 16..31)
            }
            return (lan + tailscale).distinct()
        }
        fun bestLanAddress(): String? = directAddresses().firstOrNull()
    }
}
