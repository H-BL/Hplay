package com.hpplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact shell bytes the wireless-ADB enabler sends.
 *
 * These are the contract with the vendor init rule: `sys.wifiadb.enable` only restarts adbd on a
 * *change*, and readiness must be judged from `service.adb.tcp.port` (never the lingering
 * `persist.*` copies), so both behaviours are asserted here where a regression is visible.
 */
class WirelessAdbCommandsTest {
    @Test
    fun wifiadbPropertyMatchesTheVendorInitRule() {
        assertEquals("sys.wifiadb.enable", WirelessAdbCommands.WIFIADB_PROP)
    }

    @Test
    fun readinessReadsTheLivePortProperty() {
        assertEquals("getprop service.adb.tcp.port", WirelessAdbCommands.readProp(WirelessAdbCommands.ADB_TCP_PORT_PROP))
        assertEquals("service.adb.tcp.port", WirelessAdbCommands.ADB_TCP_PORT_PROP)
        assertEquals("init.svc.adbd", WirelessAdbCommands.ADBD_SVC_PROP)
    }

    @Test
    fun rootFlagTargetsTheAdbdProperty() {
        assertEquals("setprop service.adb.root 1", WirelessAdbCommands.setRoot())
    }

    @Test
    fun reArmTogglesFalseThenTrue() {
        // init only reacts to a change, so the false->true flip is what actually restarts adbd.
        assertEquals(
            listOf("setprop sys.wifiadb.enable false", "setprop sys.wifiadb.enable true"),
            WirelessAdbCommands.reArm(),
        )
    }

    @Test
    fun fallbackEnablesSettingsAndForcesThePort() {
        val commands = WirelessAdbCommands.fallback()
        assertTrue(commands.contains("settings put global adb_enabled 1"))
        assertTrue(commands.contains("settings put global adb_wifi_enabled 1"))
        assertTrue(commands.contains("setprop service.adb.tcp.port 5557"))
        assertTrue(commands.contains("setprop ctl.restart adbd"))
    }

    @Test
    fun fallbackNeverReadsBackThePersistCopiesAsProof() {
        // persist.adb.tcp.port / persist.adb.tcpip are often left at 5557 even when nothing listens,
        // so they may be written but must never appear as a getprop readiness check.
        val fallback = WirelessAdbCommands.fallback()
        assertTrue(fallback.any { it.startsWith("setprop persist.adb.tcp.port") })
        assertFalse(fallback.any { it.startsWith("getprop persist.") })
    }

    @Test
    fun stateIsReadyOnlyWhenAdbdListensOnTheVendorPort() {
        assertTrue(WirelessAdbEnabler.State(port = 5557, adbdRunning = true, root = true).ready)
        assertFalse(WirelessAdbEnabler.State(port = 0, adbdRunning = true, root = true).ready)
        assertFalse(WirelessAdbEnabler.State(port = 5557, adbdRunning = false, root = true).ready)
    }
}
