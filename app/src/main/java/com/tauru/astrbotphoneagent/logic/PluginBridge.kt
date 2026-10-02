package com.tauru.astrbotphoneagent.logic

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Keeps transport reachability separate from phone permission and registration. */
class PluginBridge(
    private val context: Context,
    private val config: LogicConfig,
    private val runner: CommandRunner,
    private val scope: CoroutineScope,
    private val executor: ShizukuCommandExecutor,
) {
    private val client = AstrBotClient(config)
    private val server = CommandServer(config, runner, scope)
    private val jobs = mutableListOf<Job>()
    private val syncMutex = Mutex()
    private val _serverState = MutableStateFlow<JSONObject?>(null)
    val serverState: StateFlow<JSONObject?> = _serverState.asStateFlow()
    private val _lastStatus = MutableStateFlow<DeviceStatusSnapshot?>(null)
    val lastStatus: StateFlow<DeviceStatusSnapshot?> = _lastStatus.asStateFlow()
    private val _bridgeState = MutableStateFlow(BridgeState())
    val bridgeState: StateFlow<BridgeState> = _bridgeState.asStateFlow()

    data class BridgeState(
        val serverPort: Int = -1,
        val registered: Boolean = false,
        val pluginConnected: Boolean = false,
        val lastSyncAtEpochMs: Long = 0,
        val relayReachable: Boolean = false,
        val lastError: String? = null,
    )

    @Synchronized fun start() {
        if (jobs.any { it.isActive }) return
        val port = server.start()
        _bridgeState.update { it.copy(serverPort = port) }
        jobs += scope.launch(Dispatchers.IO) { registerLoop() }
        jobs += scope.launch(Dispatchers.IO) { relayLoop() }
        jobs += scope.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive) {
                refreshLocalStatus()
                delay(30_000)
            }
        }
        jobs += scope.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive) {
                refreshServerState()
                delay(15_000)
            }
        }
    }

    @Synchronized fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        server.stop()
        _bridgeState.value = BridgeState()
    }

    suspend fun refreshNow(): Boolean {
        refreshLocalStatus()
        return refreshServerState()
    }

    suspend fun refreshServerState(): Boolean = syncMutex.withLock {
        try {
            if (config.pluginUrl().isBlank() || config.pluginJwt().isBlank()) {
                _bridgeState.update { it.copy(pluginConnected = false, lastError = "请先在设置中配置插件地址和令牌") }
                return@withLock false
            }
            val body = client.fetchAppState()
            _serverState.value = body
            _bridgeState.update { it.copy(pluginConnected = true, lastSyncAtEpochMs = System.currentTimeMillis(), lastError = null) }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            _bridgeState.update { it.copy(pluginConnected = false, lastError = safeError(error)) }
            false
        }
    }

    private suspend fun registerLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val port = _bridgeState.value.serverPort
                val token = config.sharedToken()
                if (port > 0 && token.isNotBlank() && config.pluginUrl().isNotBlank() && config.pluginJwt().isNotBlank()) {
                    val urls = CommandServer.directAddresses().map { "http://$it:$port" }
                    val registered = urls.isNotEmpty() && client.registerDevice(DeviceIdentity.deviceId(context),
                        urls.first(), CommandServer.tokenFingerprint(token), urls)
                    _bridgeState.update { it.copy(registered = registered) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _bridgeState.update { it.copy(registered = false) } }
            delay(60_000)
        }
    }

    private suspend fun relayLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                if (config.relayUrl().isNotBlank() && config.relayTokenValue().isNotBlank()) {
                    val command = client.relayPoll(DeviceIdentity.deviceId(context))
                    _bridgeState.update { it.copy(relayReachable = true) }
                    if (command != null) coroutineScope {
                        val renewal = launch {
                            while (currentCoroutineContext().isActive) {
                                delay(15_000)
                                val taskId = client.relayTaskId(command.commandId) ?: break
                                try { client.relayRenew(taskId) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { /* A later poll can recover the lease. */ }
                            }
                        }
                        try {
                            val result = runner.run(command)
                            client.relayReportResult(result)
                        } finally { renewal.cancel() }
                    }
                } else _bridgeState.update { it.copy(relayReachable = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _bridgeState.update { it.copy(relayReachable = false) } }
            delay(5_000)
        }
    }

    private suspend fun refreshLocalStatus() = withContext(Dispatchers.IO) {
        try {
            val outcome = executor.execute("status", emptyMap())
            val output = (outcome as? ShizukuCommandExecutor.CommandOutcome.Ok)?.output
            val json = JSONObject(output ?: "{}")
            _lastStatus.value = DeviceStatusSnapshot(
                batteryPercent = if (!json.has("battery_percent") || json.isNull("battery_percent")) null else json.optInt("battery_percent"),
                charging = json.optBoolean("charging"),
                foregroundPackage = json.optString("foreground_package").takeIf { it.isNotBlank() && it != "null" },
                screenOnSecondsToday = json.optLong("screen_on_seconds_today"),
                shizukuReady = executor.shizukuReady(), appVersion = config.appVersion,
                reportedAtEpochSec = System.currentTimeMillis() / 1000.0,
            )
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep the last dated snapshot when local capture fails. */ }
    }

    private fun safeError(error: Exception): String = if (error is AstrBotClient.ApiException)
        error.message ?: "连接失败" else "连接失败，请检查服务器地址和配置"
}
