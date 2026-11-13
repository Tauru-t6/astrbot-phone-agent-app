package com.tauru.astrbotphoneagent.logic

import org.junit.Assert.*
import org.junit.Test

class VisualTextPolicyTest {
    @Test fun visualActionsRequireUnchangedFrameAndFreshLease() {
        VisualTextPolicy.checkAction("tap","same","same",1000,"搜索")
        assertEquals("ui_snapshot_changed",assertThrows(UiFailure::class.java){VisualTextPolicy.checkAction("tap","old","new",1000,"搜索")}.code)
        assertEquals("ui_snapshot_expired",assertThrows(UiFailure::class.java){VisualTextPolicy.checkAction("tap","same","same",31_000,"搜索")}.code)
    }
    @Test fun visualModeCannotTypeScrollOrUseSensitiveLabels() {
        for(action in listOf("input_text","scroll","shell"))assertThrows(UiFailure::class.java){VisualTextPolicy.checkAction(action,"same","same",1000,"搜索")}
        for(label in listOf("立即付款","确认","Send message","Delete","允许访问"))assertFalse(VisualTextPolicy.safeLabel(label))
        assertTrue(VisualTextPolicy.sensitivePage(listOf("请输入密码")))
    }
}
