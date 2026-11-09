package com.tauru.astrbotphoneagent.logic

import org.junit.Assert.*
import org.junit.Test

class PeerRateLimiterTest {
    @Test fun quotaExpiresAtWindowBoundary() {
        var now = 0L
        val limiter = PeerRateLimiter(limit = 2, windowMs = 100, clockMs = { now })
        assertFalse(limiter.isLimited("a"))
        assertFalse(limiter.isLimited("a"))
        assertTrue(limiter.isLimited("a"))
        now = 99
        assertTrue(limiter.isLimited("a"))
        now = 100
        assertFalse(limiter.isLimited("a"))
    }

    @Test fun fullPeerTablePreservesExistingQuotasAndReclaimsIdlePeers() {
        var now = 0L
        val limiter = PeerRateLimiter(limit = 1, windowMs = 100, maxPeers = 2, clockMs = { now })
        assertFalse(limiter.isLimited("a"))
        assertFalse(limiter.isLimited("b"))
        assertTrue(limiter.isLimited("c"))
        assertTrue(limiter.isLimited("a"))
        now = 100
        assertFalse(limiter.isLimited("c"))
        assertFalse(limiter.isLimited("d"))
        assertTrue(limiter.isLimited("c"))
    }

    @Test fun differentPeersHaveIndependentQuotas() {
        val limiter = PeerRateLimiter(limit = 1)
        assertFalse(limiter.isLimited("a"))
        assertTrue(limiter.isLimited("a"))
        assertFalse(limiter.isLimited("b"))
    }
}
