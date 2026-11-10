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

internal interface HealthSyncStorage {
    suspend fun healthSettings(): HealthSyncSettings
    suspend fun update(updates: Map<String, String>)
}

internal interface HealthSummaryReader {
    fun available(): Boolean
    suspend fun grantedTypes(): Set<String>
    suspend fun read(types: Set<String>, zone: java.time.ZoneId, originPackage: String): List<HealthDay>
}

internal data class HealthUpload(
    val originPackage: String,
    val capturedAt: java.time.Instant,
    val zone: java.time.ZoneId,
    val status: String,
    val grantedTypes: Set<String>,
    val days: List<HealthDay>,
)

internal interface HealthSyncRemote {
    suspend fun upload(snapshot: HealthUpload): String
    suspend fun clear()
}
