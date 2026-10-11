package com.tauru.astrbotphoneagent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// These cover the shell-safety boundary: every value that reaches sh -c must be
// either a bounded integer or a single-quote-escaped literal.
class InputCommandsTest {

    @Test fun tapRejectsOutOfRangeAndNonNumericCoordinates() {
        assertEquals("input tap 10 20", InputCommands.tap(mapOf("x" to "10", "y" to "20")))
        for (bad in listOf(mapOf("x" to "-1", "y" to "0"), mapOf("x" to "0", "y" to "999999"),
                           mapOf("x" to "abc", "y" to "1"), mapOf("y" to "1"),
                           mapOf("x" to "1; rm -rf /", "y" to "1"))) {
            val error = assertThrows(InputCommands.Invalid::class.java) { InputCommands.tap(bad) }
            assertEquals("bad_args", error.errorCode)
        }
    }

    @Test fun swipeClampsDurationIntoRange() {
        assertEquals("input swipe 1 2 3 4 50", InputCommands.swipe(
            mapOf("x1" to "1", "y1" to "2", "x2" to "3", "y2" to "4", "duration_ms" to "1")))
        assertEquals("input swipe 1 2 3 4 10000", InputCommands.swipe(
            mapOf("x1" to "1", "y1" to "2", "x2" to "3", "y2" to "4", "duration_ms" to "999999")))
        assertEquals("input swipe 1 2 3 4 300", InputCommands.swipe(
            mapOf("x1" to "1", "y1" to "2", "x2" to "3", "y2" to "4")))
    }

    @Test fun scrollDerivesCoordinatesFromDisplaySize() {
        // 1080x1920, scroll down moves the finger from below centre to above it.
        assertEquals("input swipe 540 1440 540 480 300", InputCommands.scroll(emptyMap(), 1080, 1920))
        assertEquals("input swipe 540 480 540 1440 300", InputCommands.scroll(mapOf("direction" to "up"), 1080, 1920))
        assertEquals("input swipe 270 960 810 960 300", InputCommands.scroll(mapOf("direction" to "right"), 1080, 1920))
        assertEquals("input swipe 810 960 270 960 300", InputCommands.scroll(mapOf("direction" to "left"), 1080, 1920))
        assertThrows(InputCommands.Invalid::class.java) { InputCommands.scroll(mapOf("direction" to "sideways"), 1080, 1920) }
    }

    @Test fun textEscapingClosesTheShellQuotingBoundary() {
        assertEquals("input text 'hello'", InputCommands.inputText(mapOf("text" to "hello")))
        // A single quote must not be able to terminate the literal.
        assertEquals("""input text 'it'\''s'""", InputCommands.inputText(mapOf("text" to "it's")))
        val hostile = InputCommands.inputText(mapOf("text" to "'; rm -rf / #"))
        assertTrue(hostile.startsWith("input text '"))
        assertEquals("""input text ''\''; rm -rf / #'""", hostile)
        // Every quote in the payload is escaped, so the argument never closes early.
        assertEquals(1, Regex("'\\\\''").findAll(hostile).count())
    }

    @Test fun textRejectsEmptyOversizedAndControlCharacters() {
        for (bad in listOf("", "x".repeat(501), "line\nbreak", "tab\there", "bell\u0007")) {
            val error = assertThrows(InputCommands.Invalid::class.java) {
                InputCommands.inputText(mapOf("text" to bad))
            }
            assertEquals("bad_args", error.errorCode)
        }
    }

    @Test fun keyEventOnlyAcceptsTheAllowlistedCodes() {
        assertEquals("input keyevent 4", InputCommands.keyEvent(mapOf("key_code" to "4")))
        assertEquals("input keyevent 26", InputCommands.keyEvent(mapOf("key_code" to "26")))
        for (bad in listOf("", "9999", "-1", "abc", "4; reboot")) {
            val error = assertThrows(InputCommands.Invalid::class.java) {
                InputCommands.keyEvent(mapOf("key_code" to bad))
            }
            assertEquals("bad_args", error.errorCode)
        }
    }

    @Test fun newActionsArePartOfTheWireContractOnBothSides() {
        // device_app.py COMMAND_ACTIONS must mirror this set.
        for (action in listOf("tap", "long_press", "swipe", "scroll", "input_text", "key_event")) {
            assertTrue(action + " missing from COMMAND_ACTIONS", action in COMMAND_ACTIONS)
            assertTrue(action + " must require confirmation", action in DANGEROUS_ACTIONS)
        }
        // Reading the screen or capturing it is as sensitive as touching it.
        assertTrue("screen_text must require confirmation", "screen_text" in DANGEROUS_ACTIONS)
        assertTrue("screenshot must require confirmation", "screenshot" in DANGEROUS_ACTIONS)
    }
}
