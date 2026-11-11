package com.tauru.astrbotphoneagent.logic

import java.io.IOException

internal const val RELAY_UPDATE_REQUIRED = "请升级 relay：Bot 版需要按设备定向领取任务"

internal class RelayUpdateRequiredException : IOException(RELAY_UPDATE_REQUIRED)

/** Do not coerce an absent capability or the string "true" to server support. */
internal fun supportsTargetedRelayPolling(success: Any?, capability: Any?): Boolean =
    success == true && capability == true

internal fun relayPollPath(deviceId: String, targetedOnly: Boolean): String {
    require(Regex("[A-Za-z0-9._-]{1,128}").matches(deviceId)) { "invalid relay device identity" }
    return "/poll?device_id=$deviceId" + if (targetedOnly) "&targeted_only=true" else ""
}
