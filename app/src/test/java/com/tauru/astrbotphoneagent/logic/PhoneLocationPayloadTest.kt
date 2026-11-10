package com.tauru.astrbotphoneagent.logic

import org.junit.Assert.*
import org.junit.Test

class PhoneLocationPayloadTest {
    @Test fun reportsWgs84CaptureTimeMonotonicAgeAndUnavailableAddress() {
        val result = PhoneLocationPayload.encode(
            latitude = 39.9,
            longitude = 116.4,
            fixTimeMs = 1_794_312_000_000,
            fixElapsedRealtimeNanos = 8_000_000_000,
            nowElapsedRealtimeNanos = 10_500_000_000,
            provider = "gps",
            accuracyMeters = 3.25,
            isMock = false,
            addressRequested = true,
        )
        assertEquals("wgs84", result["coord_type"])
        assertEquals(1_794_312_000.0, result["captured_at"] as Double, 0.0001)
        assertEquals(2.5, result["age_seconds"] as Double, 0.0001)
        assertEquals(3.25, result["accuracy_m"] as Double, 0.0001)
        assertEquals(false, result["is_mock"])
        assertEquals("not_supported", result["address_status"])
    }

    @Test fun preservesZeroAccuracyAndOmitsUnavailableAccuracy() {
        val known = PhoneLocationPayload.encode(0.0, 0.0, 1000, 10, 20,
            "network", 0.0, false, false)
        assertEquals(0.0, known["accuracy_m"] as Double, 0.0)
        assertEquals("not_requested", known["address_status"])
        val unknown = PhoneLocationPayload.encode(0.0, 0.0, 1000, 10, 20,
            "network", null, true, false)
        assertFalse(unknown.containsKey("accuracy_m"))
        assertEquals(true, unknown["is_mock"])
    }
}
