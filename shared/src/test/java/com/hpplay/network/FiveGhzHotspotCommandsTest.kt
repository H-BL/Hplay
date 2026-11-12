package com.hpplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact shell bytes the force-5-GHz flow sends.
 *
 * These strings are the contract with the head unit's adbd: a wrong transaction number, a missing
 * `s16` argument or a mis-quoted helper argument fails silently on the car, so they are asserted
 * here where they can be seen.
 */
class FiveGhzHotspotCommandsTest {
    @Test
    fun countryCodeSetUsesBinderTransaction27WithAString16Argument() {
        assertEquals("service call wifi 27 s16 US", FiveGhzHotspotCommands.setCountryCode())
    }

    @Test
    fun countryCodeSetHonoursAnOverride() {
        assertEquals("service call wifi 27 s16 DE", FiveGhzHotspotCommands.setCountryCode("DE"))
    }

    @Test
    fun countryCodeReadUsesBinderTransaction28() {
        assertEquals("service call wifi 28", FiveGhzHotspotCommands.readCountryCode())
    }

    @Test
    fun rootProbeIsJustId() {
        assertEquals("id", FiveGhzHotspotCommands.probeRoot())
    }

    @Test
    fun hostapdListingQuotesNothingAndListsEveryPath() {
        val paths = listOf("/data/vendor/wifi/hostapd/hostapd_ap0.conf", "/data/misc/wifi/hostapd.conf")
        assertEquals("ls ${paths.joinToString(" ")}", FiveGhzHotspotCommands.listHostapdConfigs(paths))
    }

    @Test
    fun configReadQuotesThePathSoSpacesCannotSplitIt() {
        assertEquals("cat '/a b/hostapd.conf'", FiveGhzHotspotCommands.readConfig("/a b/hostapd.conf"))
    }

    @Test
    fun helperDeployDecodesThePayloadAndFixesTheMode() {
        val command = FiveGhzHotspotCommands.deployHelper("QUJD")
        assertTrue(command.contains("base64 -d > '${FiveGhzHotspotCommands.HELPER_PATH}' <<'XCP'"))
        assertTrue(command.contains("\nQUJD\n"))
        assertTrue(command.contains("chmod 644 '${FiveGhzHotspotCommands.HELPER_PATH}'"))
    }

    @Test
    fun apConfigReadRunsTheHelperAsSystemUid() {
        // The classpath must ride `-Djava.class.path=`, not the CLASSPATH env var: a non-PTY adb shell
        // (what the in-app client opens) ignores CLASSPATH and aborts app_process with SIGABRT.
        assertEquals(
            "/system/xbin/runconuid -u 1000 -g 1000 /system/bin/app_process " +
                "-Djava.class.path='/data/local/tmp/hotspot_ap.dex' /system/bin HotspotAp get 'com.hpplay' 2>&1 | cat",
            FiveGhzHotspotCommands.readApConfig("com.hpplay"),
        )
        assertFalse(FiveGhzHotspotCommands.readApConfig("com.hpplay").contains("CLASSPATH="))
    }

    @Test
    fun apConfigWritePassesTheBandAndTheCallerPackage() {
        assertEquals(
            "/system/xbin/runconuid -u 1000 -g 1000 /system/bin/app_process " +
                "-Djava.class.path='/data/local/tmp/hotspot_ap.dex' /system/bin HotspotAp set 1 'com.hpplay' 2>&1 | cat",
            FiveGhzHotspotCommands.writeApConfig(1, "com.hpplay"),
        )
        assertTrue(FiveGhzHotspotCommands.writeApConfig(0, "com.hpplay").endsWith("set 0 'com.hpplay' 2>&1 | cat"))
    }

    @Test
    fun apBandChannelWritePassesBandChannelAndPackage() {
        assertEquals(
            "/system/xbin/runconuid -u 1000 -g 1000 /system/bin/app_process " +
                "-Djava.class.path='/data/local/tmp/hotspot_ap.dex' /system/bin HotspotAp setbc 1 149 'com.hpplay' 2>&1 | cat",
            FiveGhzHotspotCommands.writeApBandChannel(1, 149, "com.hpplay"),
        )
    }

    @Test
    fun bandConstantsMatchTheFramework() {
        assertEquals(0, FiveGhzHotspotCommands.BAND_2GHZ)
        assertEquals(1, FiveGhzHotspotCommands.BAND_5GHZ)
    }

    @Test
    fun tetheringAppOpIsGrantedToTheAppPackage() {
        assertEquals(
            "appops set com.hpplay WRITE_SETTINGS allow",
            FiveGhzHotspotCommands.grantTetheringAppOp("com.hpplay"),
        )
    }

    @Test
    fun wifiToggleUsesTheSvcWrapper() {
        assertEquals("svc wifi enable", FiveGhzHotspotCommands.setWifiEnabled(true))
        assertEquals("svc wifi disable", FiveGhzHotspotCommands.setWifiEnabled(false))
    }

    @Test
    fun softApLinkTargetsTheGivenInterface() {
        assertEquals("ip link show ap0", FiveGhzHotspotCommands.softApLink("ap0"))
    }

    @Test
    fun softApStatusAsksHostapdItself() {
        assertEquals("hostapd_cli -i ap0 status", FiveGhzHotspotCommands.softApStatus("ap0"))
        assertFalse(FiveGhzHotspotCommands.softApStatus("ap0").contains("ap1"))
    }

    @Test
    fun hostapdPidProbesTheProcess() {
        assertEquals("pidof hostapd", FiveGhzHotspotCommands.hostapdPid())
    }

    @Test
    fun packageInstalledUsesPmPath() {
        assertEquals("pm path com.ccffee.mcuvolha", FiveGhzHotspotCommands.packageInstalled("com.ccffee.mcuvolha"))
    }

    @Test
    fun forceStopTargetsTheGivenPackage() {
        assertEquals("am force-stop com.ccffee.mcuvolha", FiveGhzHotspotCommands.forceStopPackage("com.ccffee.mcuvolha"))
    }

    @Test
    fun restartingASuppressedDaemonBroadcastsBootCompletedToItsReceiver() {
        // An explicit BOOT_COMPLETED to the boot receiver is what starts a force-stopped daemon again
        // without raising its launcher activity.
        assertEquals(
            "am broadcast -a android.intent.action.BOOT_COMPLETED -n com.ccffee.mcuvolha/.app.BootReceiver",
            FiveGhzHotspotCommands.broadcastBootCompleted("com.ccffee.mcuvolha/.app.BootReceiver"),
        )
    }
    @Test
    fun ssidWriteQuotesThePackageAndTheName() {
        val command = FiveGhzHotspotCommands.writeApSsid("Hplay-abcd", "com.hpplay")
        assertTrue(command.contains("HotspotAp ssid 'com.hpplay' 'Hplay-abcd'"))
    }
}
