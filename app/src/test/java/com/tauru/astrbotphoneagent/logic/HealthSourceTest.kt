package com.tauru.astrbotphoneagent.logic

import org.junit.Assert.*
import org.junit.Test

class HealthSourceTest {
    @Test fun keepsXiaomiAsDefaultAndLabelsOtherOriginsByPackage() {
        assertEquals("com.mi.health", HealthSource.DEFAULT_PACKAGE)
        assertEquals("小米运动健康", HealthSource.label(HealthSource.DEFAULT_PACKAGE))
        assertEquals("com.example.fitness", HealthSource.label("com.example.fitness"))
    }

    @Test fun acceptsOnlyBoundedAndroidPackageIdentifiers() {
        for (packageName in listOf("com.mi.health", "com.example.fitness", "org.vendor.Health_App")) {
            assertTrue(packageName, HealthSource.isValid(packageName))
        }
        for (packageName in listOf("", "com", ".example.app", "com..app", "com.1app", "com.bad/name", "com.example.app\nignore")) {
            assertFalse(packageName, HealthSource.isValid(packageName))
        }
        assertFalse(HealthSource.isValid("com." + "a".repeat(252)))
    }
}
