package com.tauru.astrbotphoneagent.logic

internal object PhoneLocationPayload {
    fun encode(
        latitude: Double,
        longitude: Double,
        fixTimeMs: Long,
        fixElapsedRealtimeNanos: Long,
        nowElapsedRealtimeNanos: Long,
        provider: String,
        accuracyMeters: Double?,
        isMock: Boolean,
        addressRequested: Boolean,
    ): Map<String, Any> {
        val ageSeconds = ((nowElapsedRealtimeNanos - fixElapsedRealtimeNanos).coerceAtLeast(0L)) / 1_000_000_000.0
        val result = linkedMapOf<String, Any>(
            "latitude" to latitude,
            "longitude" to longitude,
            "coord_type" to "wgs84",
            "captured_at" to (fixTimeMs / 1000.0),
            "age_seconds" to ageSeconds,
            "is_mock" to isMock,
            "provider" to provider,
            "address_status" to if (addressRequested) "not_supported" else "not_requested",
        )
        if (accuracyMeters != null && accuracyMeters.isFinite() && accuracyMeters >= 0.0) {
            result["accuracy_m"] = accuracyMeters
        }
        return result
    }
}
