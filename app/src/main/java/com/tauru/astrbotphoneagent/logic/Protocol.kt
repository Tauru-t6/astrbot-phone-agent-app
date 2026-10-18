package com.tauru.astrbotphoneagent.logic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire contract between this app and the AstrBot plugin (device_app.py).
 * Source of truth: docs/APP_API_V2.md. Changing a field here requires the same
 * change in device_app.py COMMAND_ACTIONS and a contract doc bump.
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
    // UI automation, at parity with the OperitAI backend.
    "tap", "long_press", "swipe", "scroll", "input_text", "key_event",
)

// Largest coordinate the input actions accept, mirroring the plugin check.
const val MAX_INPUT_COORDINATE = 10_000

// Upper bound for one input_text payload.
const val MAX_INPUT_TEXT_LENGTH = 500

// Key codes key_event may inject. Anything else is refused.
val ALLOWED_KEY_CODES: Set<Int> = setOf(
    3, 4, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 61, 62, 66, 67, 82,
    85, 87, 88, 92, 93, 111, 112, 122, 123, 187,
)

/** Actions that require an in-app confirmation dialog before executing. */
val DANGEROUS_ACTIONS: Set<String> = setOf(
    "close_app", "suspend_app", "suspend_video_apps", "send_notification",
    "location", "screen_text", "screenshot",
    "tap", "long_press", "swipe", "scroll", "input_text", "key_event",
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
    @SerialName("screen_interactive") val screenInteractive: Boolean? = null,
    @SerialName("shizuku_ready") val shizukuReady: Boolean = false,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("reported_at") val reportedAtEpochSec: Double? = null,
    // Raw reason the foreground probe produced nothing; diagnostic only.
    @SerialName("foreground_debug") val foregroundDebug: String? = null,
)
