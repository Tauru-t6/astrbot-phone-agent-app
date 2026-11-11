package com.tauru.astrbotphoneagent.logic

import org.junit.Assert.*
import org.junit.Test

class UiSnapshotTest {
    private val pkg = "com.example.video"
    private fun xml(vararg nodes: String) = "<?xml version=\"1.0\"?><hierarchy rotation=\"0\">${nodes.joinToString("")}</hierarchy>"
    private fun node(text: String = "搜索", id: String = "com.example.video:id/search", bounds: String = "[20,40][400,100]",
                     extra: String = "", packageName: String = pkg): String =
        """<node text="$text" resource-id="$id" class="android.widget.Button" package="$packageName" bounds="$bounds" clickable="true" enabled="true" $extra />"""
    private fun parse(raw: String, now: Long = 1000) = UiSnapshotParser.parse(raw, pkg, 1080, 2400, now, 1000.0)
    private fun args(snapshot: UiSnapshot, kind: String = "tap", vararg extras: Pair<String, String>) =
        mapOf("expected_package" to pkg, "snapshot_id" to snapshot.id, "kind" to kind, "node_id" to "n0") + extras

    @Test fun parsesXmlEntitiesChineseAndBoundedActionableNodeFields() {
        val snapshot = parse(xml(node(text = "动画 &amp; 科技", extra = "focusable=\"true\"")))
        val n = snapshot.nodes.single()
        assertEquals("动画 & 科技", n.text)
        assertEquals(listOf(20, 40, 400, 100), n.bounds)
        assertEquals("n0", n.id)
        assertTrue(n.clickable)
        assertTrue(n.focusable)
        assertEquals(pkg, snapshot.packageName)
        assertEquals(1, snapshot.wire()["schema_version"])
    }

    @Test fun landscapeCaptureUsesRotatedDisplayBounds() {
        val snapshot = parse(xml(node(bounds="[1400,40][2000,100]")).replace("rotation=\"0\"", "rotation=\"1\""))
        assertEquals(2400, snapshot.width)
        assertEquals(1080, snapshot.height)
        assertEquals(listOf(1400,40,2000,100), snapshot.nodes.single().bounds)
    }

    @Test fun passwordValuesAreNeverReturnedAndCannotBeActionTargets() {
        val snapshot = parse(xml(node(text = "PRIVATE_PASSWORD", extra = "password=\"true\" content-desc=\"PRIVATE_LABEL\"")))
        assertEquals("", snapshot.nodes.single().text)
        assertEquals("", snapshot.nodes.single().description)
        assertFalse(snapshot.wire().toString().contains("PRIVATE_PASSWORD"))
        val failure = assertThrows(UiFailure::class.java) { UiActionPolicy.target(snapshot, snapshot, args(snapshot)) }
        assertEquals("ui_action_blocked", failure.code)
    }

    @Test fun malformedXmlDoctypeOffscreenBoundsAndOverflowFailClosed() {
        for (raw in listOf("<hierarchy><node>", "<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///private'>]><hierarchy/>",
                           xml(node(bounds = "[0,0][9999999999999,80]")), xml(node(bounds = "[-1,0][50,80]")),
                           xml(node(bounds = "[50,0][10,80]")), xml(node(bounds = "[0,0][2000,80]")))) {
            assertThrows(UiFailure::class.java) { parse(raw) }
        }
    }

    @Test fun overlappingOtherAppInvalidatesSnapshotButKnownKeyboardIsOmitted() {
        assertThrows(UiFailure::class.java) { parse(xml(node(), node(packageName = "com.other.app"))) }
        val value = UiSnapshotParser.parse(xml(node(), node(packageName = "com.vendor.ime")), pkg,1080,2400,1000,
            inputMethodPackage = "com.vendor.ime")
        assertEquals(1, value.nodes.size)
    }

    @Test fun oversizedHierarchyDoesNotPretendToBeComplete() {
        assertThrows(UiFailure::class.java) { parse(xml(*(0..160).map { node(id="com.example.video:id/n$it") }.toTypedArray())) }
        assertThrows(UiFailure::class.java) { parse("x".repeat(UiSnapshotParser.MAX_BYTES + 1)) }
    }

    @Test fun stalePackageMismatchAndConsumedTokensCannotAuthorizeActions() {
        val lease = UiSnapshotLease()
        val snapshot = parse(xml(node()))
        lease.remember(snapshot)
        assertEquals(snapshot, lease.take(snapshot.id, pkg, 2000))
        assertThrows(UiFailure::class.java) { lease.take(snapshot.id, pkg, 2001) }
        lease.remember(snapshot)
        assertThrows(UiFailure::class.java) { lease.take(snapshot.id, pkg, 31001) }
        lease.remember(snapshot)
        assertThrows(UiFailure::class.java) { lease.take(snapshot.id, "com.other.app", 2000) }
        assertThrows(UiFailure::class.java) { lease.take(snapshot.id, pkg, 2000) }
    }

    @Test fun actionRequiresSameStructureAndTargetValueButIgnoresOtherPlainTimerText() {
        val before = parse(xml(node(), """<node text="00:01" class="android.widget.TextView" package="$pkg" bounds="[0,200][60,260]" enabled="true"/>"""))
        val changedTimer = parse(xml(node(), """<node text="00:02" class="android.widget.TextView" package="$pkg" bounds="[0,200][60,260]" enabled="true"/>"""))
        assertNotNull(UiActionPolicy.target(before, changedTimer, args(before)))
        val changedButton = parse(xml(node(text="购买"), """<node text="00:02" class="android.widget.TextView" package="$pkg" bounds="[0,200][60,260]" enabled="true"/>"""))
        assertEquals("ui_snapshot_changed", assertThrows(UiFailure::class.java) {
            UiActionPolicy.target(before, changedButton, args(before))
        }.code)
        val shifted = parse(xml(node(bounds="[20,70][400,130]")))
        assertThrows(UiFailure::class.java) { UiActionPolicy.target(before, shifted, args(before)) }
    }

    @Test fun exactOverlapAndUnfocusedInputAreRejected() {
        val overlap = parse(xml(node(), node()))
        assertEquals("ui_target_ambiguous", assertThrows(UiFailure::class.java) { UiActionPolicy.target(overlap, overlap, args(overlap)) }.code)
        val notInput = parse(xml(node()))
        assertEquals("ui_input_not_focused", assertThrows(UiFailure::class.java) { UiActionPolicy.target(notInput, notInput, args(notInput,"input_text","text" to "hello")) }.code)
    }

    @Test fun clickableParentCannotReuseAChangedChildLabel() {
        fun page(label: String) = xml("""<node text="" class="android.widget.FrameLayout" package="$pkg" bounds="[20,40][400,100]" clickable="true" enabled="true">
            <node text="$label" class="android.widget.TextView" package="$pkg" bounds="[30,50][300,90]" enabled="true" />
        </node>""")
        val before = parse(page("搜索"))
        val after = parse(page("立即付款"))
        assertEquals("ui_snapshot_changed", assertThrows(UiFailure::class.java) {
            UiActionPolicy.target(before, after, args(before))
        }.code)
    }

    @Test fun systemWindowAndKeyboardCannotReceiveATargetAppTap() {
        val before = parse(xml(node()))
        val obscured = parse(xml(node(), node(packageName = "com.android.systemui")))
        assertEquals("ui_target_obscured", assertThrows(UiFailure::class.java) {
            UiActionPolicy.target(before, obscured, args(before))
        }.code)
        val bottomBar = parse(xml(node(), node(packageName = "com.android.systemui", bounds = "[0,2300][1080,2400]")))
        assertNotNull(UiActionPolicy.target(before, bottomBar, args(before)))
    }

    @Test fun actionArgsRejectCoordinatesUnknownKeysControlsAndAllowUnicode() {
        val s=parse(xml(node()))
        UiActionPolicy.validate(args(s))
        UiActionPolicy.validate(args(s,"input_text","text" to "哆啦A梦"))
        for (bad in listOf(args(s,"tap","x" to "1"), args(s,"tap","approved" to "true"),
            args(s,"input_text","text" to "line\nbreak"),args(s,"input_text","text" to ""),
            args(s,"scroll","direction" to "invalid"),args(s)+("snapshot_id" to "old"))) {
            assertEquals("bad_args",assertThrows(UiFailure::class.java) { UiActionPolicy.validate(bad) }.code)
        }
    }

    @Test fun newCommandsRequireOnDeviceConfirmationAndTargetsCannotCrossDevices() {
        for (kind in listOf("ui_snapshot","ui_action")) {
            assertTrue(kind in COMMAND_ACTIONS)
            assertTrue(kind in DANGEROUS_ACTIONS)
        }
        val command=CommandEnvelope(commandId="test",action="ping",createdAtEpochSec=1.0,deadline=20,targetDeviceId="phone-a")
        assertFalse(targetDeviceMismatch(command,"phone-a"))
        assertTrue(targetDeviceMismatch(command,"phone-b"))
        assertFalse(targetDeviceMismatch(command.copy(targetDeviceId=null),"phone-b"))
        assertTrue(targetDeviceMismatch(command.copy(targetDeviceId=""),"phone-b"))
        for (action in listOf("ui_snapshot", "ui_action")) {
            assertNotNull(commandValidationError(command.copy(action = action, targetDeviceId = null), 2.0))
            assertNull(commandValidationError(command.copy(action = action), 2.0))
        }
        assertNotNull(commandValidationError(command.copy(targetDeviceId = " "), 2.0))
    }
}
