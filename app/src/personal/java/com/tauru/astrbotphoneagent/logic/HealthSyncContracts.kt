package com.tauru.astrbotphoneagent.logic

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
