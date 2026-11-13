package com.tauru.astrbotphoneagent.logic

import com.tauru.astrbotphoneagent.data.HealthConnectState
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HealthFreshnessTest {
    private val today=LocalDate.of(2026,11,13)
    private val good=HealthConnectState(enabled=true,permissionsKnown=true,lastUploadAtEpochMs=1000,lastAttemptAtEpochMs=900,
        lastAttemptStatus="ok",latestDate="2026-11-13")
    @Test fun recentTimestampDoesNotHideFailedNewReceiptOrUnknownSourceDate() {
        assertTrue(healthIsFresh(good,2000,today))
        for(status in listOf("","read_failed","permission_denied","empty","upload_failed","in_progress"))
            assertFalse(status,healthIsFresh(good.copy(lastAttemptStatus=status),2000,today))
        assertFalse(healthIsFresh(good.copy(latestDate="2026-11-09"),2000,today))
        assertFalse(healthIsFresh(good.copy(latestDate=""),2000,today))
        assertFalse(healthIsFresh(good.copy(lastUploadAtEpochMs=3000),2000,today))
        assertFalse(healthIsFresh(good.copy(clearPending=true),2000,today))
    }
}
