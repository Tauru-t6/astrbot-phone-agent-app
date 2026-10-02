package com.tauru.astrbotphoneagent.logic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire contract between this app and the AstrBot plugin (device_app.py).
 * Source of truth: docs/APP_PLUGIN_CONTRACT_V1.md. Changing a field here
 * requires the same change on the plugin side and a contract doc bump.
 */

val protocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

const val COMMAND_SCHEMA_VERSION = 1

@Serializable
data class CommandEnvelope(
    val type: String = "command",
    @SerialName("schema_version") val schemaVersion: Int = COMMAND_SCHEMA_VERSION,
    @SerialName("command_id") val commandId: String,
    val action: String,
    @Serializable(with = CommandArgsSerializer::class)
    val args: Map<String, String> = emptyMap(),
    @SerialName("created_at") val createdAtEpochSec: Double,
    val dangerous: Boolean = false,
    val deadline: Int,
)

@Serializable
data class ResultEnvelope(
    val type: String = "result",
    @SerialName("schema_version") val schemaVersion: Int = COMMAND_SCHEMA_VERSION,
    @SerialName("command_id") val commandId: String,
    val success: Boolean,
    val action: String,
    val output: String? = null,
    val error: String = "",
    @SerialName("error_code") val errorCode: String = "",
    @SerialName("finished_at") val finishedAtEpochSec: Double,
)

/** Closed command set; must mirror COMMAND_ACTIONS in device_app.py. */
val COMMAND_ACTIONS: Set<String> = setOf(
    "status", "open_app", "close_app", "foreground_app", "lock_screen",
    "wake_screen", "home", "back", "screenshot", "screen_text",
    "suspend_app", "unsuspend_app", "suspend_video_apps", "unsuspend_video_apps",
    "location", "usage_stats", "send_notification", "ping",
)

/** Actions that require an in-app confirmation dialog before executing. */
val DANGEROUS_ACTIONS: Set<String> = setOf(
    "close_app", "suspend_app", "suspend_video_apps", "location", "send_notification",
)

@Serializable
data class DeviceRegisterPayload(
    @SerialName("device_id") val deviceId: String,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("token_fingerprint") val tokenFingerprint: String? = null,
)

@Serializable
data class DeviceStatusSnapshot(
    @SerialName("battery_percent") val batteryPercent: Int? = null,
    val charging: Boolean = false,
    @SerialName("foreground_package") val foregroundPackage: String? = null,
    @SerialName("screen_on_seconds_today") val screenOnSecondsToday: Long = 0,
    @SerialName("shizuku_ready") val shizukuReady: Boolean = false,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("reported_at") val reportedAtEpochSec: Double? = null,
)
