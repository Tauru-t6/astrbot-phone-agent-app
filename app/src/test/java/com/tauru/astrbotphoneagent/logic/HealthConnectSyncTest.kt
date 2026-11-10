package com.tauru.astrbotphoneagent.logic

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.ZoneId
import java.util.Collections

class HealthConnectSyncTest {
    private class Fixture : HealthSyncStorage, HealthSummaryReader, HealthSyncRemote {
        @Volatile var settings = HealthSyncSettings(enabled = true)
        @Volatile var clearFails = false
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val uploads: MutableList<HealthUpload> = Collections.synchronizedList(mutableListOf())
        var uploadHook: suspend () -> Unit = {}
        var permissionHook: suspend () -> Set<String> = { setOf("steps") }
        val sync = HealthConnectSync(this, this, this)

        override suspend fun healthSettings() = settings
        override suspend fun update(updates: Map<String, String>) {
            settings = settings.copy(
                enabled = updates["health_connect_enabled"]?.toBoolean() ?: settings.enabled,
                clearPending = updates["health_clear_pending"]?.toBoolean() ?: settings.clearPending,
                lastUpload = updates["health_last_upload"]?.toLong() ?: settings.lastUpload,
                originPackage = updates["health_origin_package"] ?: settings.originPackage,
            )
        }
        override fun available() = true
        override suspend fun grantedTypes() = permissionHook()
        override suspend fun read(types: Set<String>, zone: ZoneId, originPackage: String): List<HealthDay> {
            events += "read:$originPackage"
            return listOf(HealthDay("2026-11-10", mapOf("steps" to 12.0)))
        }
        override suspend fun clear() {
            events += "clear"
            if (clearFails) throw IOException("synthetic offline")
        }
        override suspend fun upload(snapshot: HealthUpload): String {
            events += "upload:${snapshot.originPackage}"
            uploadHook()
            uploads += snapshot
            return "auto"
        }
    }

    @Test fun offlineSourceChangeClearsLocalValuesAndBlocksReadsUntilDeletionSucceeds() = runBlocking {
        val f = Fixture()
        assertTrue(f.sync.sync().success)
        assertFalse(f.sync.state.value.latestMetrics.isEmpty())
        f.events.clear()
        f.clearFails = true
        assertFalse(f.sync.setSource("com.example.fitness").success)
        assertEquals("com.example.fitness", f.settings.originPackage)
        assertTrue(f.settings.clearPending)
        assertEquals(0L, f.settings.lastUpload)
        assertTrue(f.sync.state.value.latestMetrics.isEmpty())
        assertFalse(f.sync.sync().success)
        assertEquals(listOf("clear", "clear"), f.events.toList())
        f.clearFails = false
        assertTrue(f.sync.sync().success)
        assertFalse(f.settings.clearPending)
        assertEquals(listOf("clear", "clear", "clear", "read:com.example.fitness", "upload:com.example.fitness"), f.events.toList())
        assertEquals("com.example.fitness", f.uploads.last().originPackage)
    }

    @Test fun sourceChangeWaitsForOldAcknowledgementThenDeletesBeforeReadingNewSource() = runBlocking {
        withTimeout(5_000) {
            val f = Fixture()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.uploadHook = { started.complete(Unit); release.await() }
            val old = async { f.sync.sync() }
            started.await()
            val switch = async(start = CoroutineStart.UNDISPATCHED) { f.sync.setSource("com.example.fitness") }
            assertFalse(switch.isCompleted)
            assertEquals(HealthSource.DEFAULT_PACKAGE, f.settings.originPackage)
            f.uploadHook = {}
            release.complete(Unit)
            assertTrue(old.await().success)
            assertTrue(switch.await().success)
            assertEquals(listOf("read:com.mi.health", "upload:com.mi.health", "clear",
                "read:com.example.fitness", "upload:com.example.fitness"), f.events.toList())
            assertEquals("com.example.fitness", f.sync.state.value.originPackage)
            assertFalse(f.settings.clearPending)
        }
    }

    @Test fun disablingPersistsRetryAndNeverReadsWhileOffline() = runBlocking {
        val f = Fixture()
        f.clearFails = true
        assertFalse(f.sync.setEnabled(false).success)
        assertFalse(f.settings.enabled)
        assertTrue(f.settings.clearPending)
        assertFalse(f.sync.sync().success)
        assertEquals(listOf("clear", "clear"), f.events.toList())
        f.clearFails = false
        assertFalse(f.sync.sync().success)
        assertFalse(f.settings.clearPending)
        assertTrue(f.uploads.isEmpty())
    }

    @Test fun invalidSourceCannotChangeSettingsOrTriggerRemoteCalls() = runBlocking {
        val f = Fixture()
        val before = f.settings
        assertFalse(f.sync.setSource("com.bad/source").success)
        assertEquals(before, f.settings)
        assertTrue(f.events.isEmpty())
    }

    @Test fun revokedPermissionDuringReadInvalidatesDataInsteadOfUploadingReadValues() = runBlocking {
        val f = Fixture()
        var checks = 0
        f.permissionHook = { if (++checks == 1) setOf("steps") else emptySet() }
        assertFalse(f.sync.sync().success)
        assertEquals("permission_denied", f.uploads.single().status)
        assertTrue(f.uploads.single().days.isEmpty())
        assertTrue(f.sync.state.value.latestMetrics.isEmpty())
    }
}
