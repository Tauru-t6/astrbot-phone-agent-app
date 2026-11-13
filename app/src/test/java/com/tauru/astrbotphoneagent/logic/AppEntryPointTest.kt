package com.tauru.astrbotphoneagent.logic
import org.junit.Assert.*
import org.junit.Test
class AppEntryPointTest {
    @Test fun settingsPresetsTargetOnlyTheReviewedSettingsPackage() {
        assertEquals("am start -W -a android.settings.DISPLAY_SETTINGS -p com.android.settings",AppEntryPoint.command("com.android.settings","display_settings"))
        assertThrows(UiFailure::class.java){AppEntryPoint.command("com.other.app","wifi_settings")}
        assertThrows(UiFailure::class.java){AppEntryPoint.command("com.android.settings","intent://arbitrary")}
        assertThrows(UiFailure::class.java){AppEntryPoint.command("com.android.settings; reboot","app_home")}
    }
}
