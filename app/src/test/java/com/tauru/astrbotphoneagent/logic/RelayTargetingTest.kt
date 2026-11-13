package com.tauru.astrbotphoneagent.logic

import org.junit.Assert.*
import org.junit.Test

class RelayTargetingTest {
    @Test fun botRejectsAllUntargetedCommandsWhilePersonalKeepsLegacyCompatibility() {
        for (action in COMMAND_ACTIONS) {
            val command = CommandEnvelope(commandId = "target-test", action = action,
                createdAtEpochSec = 1000.0, deadline = 60)
            assertTrue(action, targetDeviceMismatch(command, "bot-phone", requireTargetDevice = true))
            assertNotNull(action, commandValidationError(command, 1001.0, requireTargetDevice = true))
            assertFalse(action, targetDeviceMismatch(command, "personal-phone", requireTargetDevice = false))
            if (action !in setOf("ui_snapshot", "ui_action","ui_visual_snapshot","ui_visual_action"))
                assertNull(action, commandValidationError(command, 1001.0, requireTargetDevice = false))
            val targeted = command.copy(targetDeviceId = "bot-phone")
            assertFalse(action, targetDeviceMismatch(targeted, "bot-phone", requireTargetDevice = true))
            assertNull(action, commandValidationError(targeted, 1001.0, requireTargetDevice = true))
            assertTrue(action, targetDeviceMismatch(targeted, "other-phone", requireTargetDevice = true))
        }
    }

    @Test fun botPollOnlyClaimsExactTargetsAndPersonalLeavesLegacyPollingAvailable() {
        assertEquals("/poll?device_id=bot-phone&targeted_only=true", relayPollPath("bot-phone", true))
        assertEquals("/poll?device_id=personal-phone", relayPollPath("personal-phone", false))
        for (invalid in listOf("", "bad&id=other", "bad id", "../device", "x".repeat(129)))
            assertThrows(IllegalArgumentException::class.java) { relayPollPath(invalid, true) }
    }

    @Test fun oldMissingOrLooselyTypedCapabilitiesNeverAuthorizeABotPoll() {
        assertTrue(supportsTargetedRelayPolling(true, true))
        for (success in listOf(null, false, "true", 1))
            assertFalse(supportsTargetedRelayPolling(success, true))
        for (capability in listOf(null, false, "true", 1))
            assertFalse(supportsTargetedRelayPolling(true, capability))
        assertTrue(RelayUpdateRequiredException().message.orEmpty().contains("升级 relay"))
    }
}
