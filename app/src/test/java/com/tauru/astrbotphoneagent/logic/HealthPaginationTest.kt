package com.tauru.astrbotphoneagent.logic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HealthPaginationTest {
    @Test fun android13EmptyFinalTokenStopsWithoutReadingFirstPageAgain() = runTest {
        val requested = mutableListOf<String?>()
        val records = readHealthPages<Int> { token ->
            requested += token
            when (token) {
                null -> listOf(1, 2) to "next"
                "next" -> listOf(3) to ""
                else -> error("Final empty token must not be requested")
            }
        }
        assertEquals(listOf(null, "next"), requested)
        assertEquals(listOf(1, 2, 3), records)
    }

    @Test fun emptyProviderReturnsNoDataForEitherTerminalRepresentation() = runTest {
        for (terminal in listOf(null, "")) {
            var calls = 0
            assertTrue(readHealthPages<Int> { calls++; emptyList<Int>() to terminal }.isEmpty())
            assertEquals(1, calls)
        }
    }

    @Test fun finalAllowedPageIsIncludedWhenItTerminates() = runTest {
        var calls = 0
        val records = readHealthPages(maxPages = 2) {
            calls++
            listOf(calls) to if (calls == 1) "second" else ""
        }
        assertEquals(listOf(1, 2), records)
    }

    @Test fun brokenProviderAndOverLimitNeverReturnPartialSummary() = runTest {
        var cycleCalls = 0
        val cycle = runCatching { readHealthPages(maxPages = 3) { cycleCalls++; listOf(1) to "same" } }
        assertTrue(cycle.exceptionOrNull() is IllegalStateException)
        assertEquals(2, cycleCalls)
        var limitCalls = 0
        val limit = runCatching { readHealthPages(maxPages = 2) { limitCalls++; listOf(1) to "page-$limitCalls" } }
        assertTrue(limit.exceptionOrNull() is IllegalStateException)
        assertEquals(2, limitCalls)
    }
}
