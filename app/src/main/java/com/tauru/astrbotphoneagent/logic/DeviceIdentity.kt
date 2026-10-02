package com.tauru.astrbotphoneagent.logic

import android.content.Context
import android.os.Build
import java.io.File

/** Stable per-device id used for relay claim ownership and plugin registration. */
object DeviceIdentity {
    @Volatile
    private var cached: String? = null

    fun deviceId(context: Context? = null): String {
        cached?.let { return it }
        val androidId = context?.let {
            android.provider.Settings.Secure.getString(it.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        } ?: readCachedAndroidId()
        val id = "phone-" + (androidId?.take(8) ?: Build.MODEL.take(8).lowercase().replace(Regex("[^a-z0-9]"), "")).padEnd(4, '0')
        cached = id
        return id
    }

    private fun readCachedAndroidId(): String? {
        val marker = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", ".astrbot_phone_agent_id")
        return runCatching { marker.readText().trim() }.getOrNull()
    }
}
