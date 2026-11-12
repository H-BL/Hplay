package com.hpplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAccessibilityAutoConfirmTest {
    @Test
    fun componentUsesThePackageAndServiceClass() {
        assertEquals(
            "com.hpplay/com.hpplay.UsbAutoConfirmService",
            UsbAccessibilityAutoConfirm.component("com.hpplay"),
        )
    }

    @Test
    fun commandsEnableTheServiceWhenNoneWasSet() {
        val commands = UsbAccessibilityAutoConfirm.commands("com.hpplay", "")

        assertEquals(
            "settings put secure enabled_accessibility_services " +
                "\"com.hpplay/com.hpplay.UsbAutoConfirmService\"",
            commands[0],
        )
        assertEquals("settings put secure accessibility_enabled 1", commands[1])
    }

    @Test
    fun commandsKeepExistingServices() {
        val commands = UsbAccessibilityAutoConfirm.commands(
            "com.hpplay",
            "com.example.talkback/com.example.talkback.Service",
        )

        assertTrue(
            commands[0].contains("com.example.talkback/com.example.talkback.Service:"),
        )
        assertTrue(commands[0].contains("com.hpplay/com.hpplay.UsbAutoConfirmService"))
    }

    @Test
    fun commandsDoNotDuplicateTheService() {
        val commands = UsbAccessibilityAutoConfirm.commands(
            "com.hpplay",
            "com.hpplay/com.hpplay.UsbAutoConfirmService",
        )

        val occurrences = commands[0].windowed(
            "com.hpplay/com.hpplay.UsbAutoConfirmService".length,
        ).count { it == "com.hpplay/com.hpplay.UsbAutoConfirmService" }
        assertEquals(1, occurrences)
    }

    @Test
    fun keeperComponentPointsAtTheKeepaliveService() {
        assertEquals(
            "com.hpplay/com.hpplay.StartupKeeperService",
            UsbAccessibilityAutoConfirm.component(
                "com.hpplay",
                "com.hpplay.StartupKeeperService",
            ),
        )
    }

    @Test
    fun keeperCommandsAddOnlyTheKeeperAndPreserveExistingServices() {
        val commands = UsbAccessibilityAutoConfirm.commands(
            "com.hpplay",
            "com.hpplay/com.hpplay.UsbAutoConfirmService",
            "com.hpplay.StartupKeeperService",
        )

        assertEquals(
            "settings put secure enabled_accessibility_services " +
                "\"com.hpplay/com.hpplay.UsbAutoConfirmService:" +
                "com.hpplay/com.hpplay.StartupKeeperService\"",
            commands[0],
        )
        assertEquals("settings put secure accessibility_enabled 1", commands[1])
    }
}
