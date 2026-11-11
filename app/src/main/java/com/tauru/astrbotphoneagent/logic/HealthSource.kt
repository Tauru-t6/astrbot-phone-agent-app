package com.tauru.astrbotphoneagent.logic

/** Match Android application IDs; do not send free-form source labels to the server. */
internal object HealthSource {
    const val DEFAULT_PACKAGE = "com.mi.health"
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun isValid(packageName: String): Boolean = packageName.length in 1..255 && packagePattern.matches(packageName)

    fun label(packageName: String): String = if (packageName == DEFAULT_PACKAGE) "小米运动健康" else packageName
}

internal data class HealthSyncSettings(
    val enabled: Boolean = false,
    val clearPending: Boolean = false,
    val lastUpload: Long = 0,
    val originPackage: String = HealthSource.DEFAULT_PACKAGE,
)
