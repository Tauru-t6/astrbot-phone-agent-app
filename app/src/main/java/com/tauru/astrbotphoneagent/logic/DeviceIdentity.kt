package com.tauru.astrbotphoneagent.logic

import android.content.Context
import android.os.Build
import com.tauru.astrbotphoneagent.BuildConfig
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
        val legacyId = "phone-" + (androidId?.take(8) ?: Build.MODEL.take(8).lowercase().replace(Regex("[^a-z0-9]"), "")).padEnd(4, '0')
        // Co-installed flavors signed by the same key may share ANDROID_ID.
        // Preserve existing personal registrations and give Bot its own identity.
        val id = if (BuildConfig.IS_BOT_DEVICE) "bot-$legacyId" else legacyId
        cached = id
        return id
    }

    private fun readCachedAndroidId(): String? {
        val marker = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", ".astrbot_phone_agent_id")
        return runCatching { marker.readText().trim() }.getOrNull()
    }
}
