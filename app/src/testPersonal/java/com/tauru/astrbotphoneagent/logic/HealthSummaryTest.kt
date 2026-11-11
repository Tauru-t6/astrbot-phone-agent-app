package com.tauru.astrbotphoneagent.logic

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class HealthSummaryTest {
    @Test fun emptyDataDoesNotBecomeZeroMeasurements() {
        assertTrue(HealthSummary.oxygenByDay(emptyList(), ZoneId.of("Asia/Shanghai")).isEmpty())
    }

    @Test fun recordsAreDeduplicatedAndNonfiniteValuesExcluded() {
        val time = Instant.parse("2026-11-10T06:00:00Z")
        val points = listOf(OxygenPoint("a", time, 96.0), OxygenPoint("a", time, 96.0),
            OxygenPoint("b", time.plusSeconds(1), 98.0), OxygenPoint("bad", time, Double.NaN),
            OxygenPoint("invalid", time, 130.0))
        val values = HealthSummary.oxygenByDay(points, ZoneId.of("Asia/Shanghai")).values.single()
        assertEquals(97.0, values.getValue("spo2_avg"), 0.001)
        assertEquals(96.0, values.getValue("spo2_min"), 0.001)
    }

    @Test fun dayBoundaryUsesPhoneTimezoneRatherThanUtc() {
        val point = OxygenPoint("a", Instant.parse("2026-11-09T17:00:00Z"), 97.0)
        assertEquals(LocalDate.of(2026, 11, 10), HealthSummary.oxygenByDay(listOf(point), ZoneId.of("Asia/Shanghai")).keys.single())
        assertEquals(LocalDate.of(2026, 11, 9), HealthSummary.oxygenByDay(listOf(point), ZoneId.of("UTC")).keys.single())
    }
}
