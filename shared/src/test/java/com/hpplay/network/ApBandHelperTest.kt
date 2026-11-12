package com.hpplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [ApBandHelper] against a fake shell, so the deploy-then-drive sequence and every failure
 * mode of the framework SoftAp band write are covered without a device.
 */
class ApBandHelperTest {
    /** Records the commands it is given and answers each from a canned rule. */
    private class FakeShell(private val answer: (String) -> String) {
        val commands = mutableListOf<String>()
        fun run(command: String): String {
            commands += command
            return answer(command)
        }
    }

    private fun helper(shell: FakeShell, asset: String? = "QUJD"): ApBandHelper =
        ApBandHelper(callerPackage = "com.hpplay", assetBase64 = { asset }, shell = shell::run)

    @Test
    fun deploysTheHelperThenReadsTheBand() {
        val shell = FakeShell { command ->
            if (command.contains("HotspotAp get")) "HOTSPOTAP:GET band=1 channel=0 ssid=F510C-000000"
            else ""
        }
        assertEquals(1, helper(shell).readBand())
        assertTrue(shell.commands.any { it.contains("<<'XCP'") })
    }

    @Test
    fun reportsAnAppliedWriteWhenTheFrameworkReadsItBack() {
        val shell = FakeShell { command ->
            when {
                command.contains("HotspotAp get") -> "HOTSPOTAP:GET band=0 channel=0 ssid=F510C-000000"
                command.contains("HotspotAp set") ->
                    "HOTSPOTAP:BEFORE band=0 channel=0 ssid=F510C-000000\n" +
                        "HOTSPOTAP:SET ok=true\n" +
                        "HOTSPOTAP:AFTER band=1 channel=0 ssid=F510C-000000"
                else -> ""
            }
        }
        val result = helper(shell).writeBand(HotspotBand.FIVE_GHZ)
        assertEquals(ApBandHelper.Result.Applied(1), result)
        assertTrue(shell.commands.any { it.contains("set 1 'com.hpplay'") })
    }

    @Test
    fun surfacesTheFrameworksRefusalVerbatim() {
        val shell = FakeShell { command ->
            when {
                command.contains("HotspotAp get") -> "HOTSPOTAP:GET band=1 channel=0 ssid=x"
                command.contains("HotspotAp set") ->
                    "HOTSPOTAP:ERR java.lang.SecurityException: App not allowed to read or update " +
                        "stored WiFi Ap config (uid = 0)"
                else -> ""
            }
        }
        val result = helper(shell).writeBand(HotspotBand.FIVE_GHZ)
        assertTrue(result is ApBandHelper.Result.Refused)
        assertTrue((result as ApBandHelper.Result.Refused).detail.contains("SecurityException"))
    }

    @Test
    fun refusesWhenTheBandDoesNotStick() {
        val shell = FakeShell { command ->
            when {
                command.contains("HotspotAp get") -> "HOTSPOTAP:GET band=0 channel=0 ssid=x"
                command.contains("HotspotAp set") ->
                    "HOTSPOTAP:SET ok=true\nHOTSPOTAP:AFTER band=0 channel=0 ssid=x"
                else -> ""
            }
        }
        val result = helper(shell).writeBand(HotspotBand.FIVE_GHZ)
        assertTrue(result is ApBandHelper.Result.Refused)
        assertTrue((result as ApBandHelper.Result.Refused).detail.contains("did not stick"))
    }

    @Test
    fun isUnavailableWhenTheHelperIsMissing() {
        val shell = FakeShell { "" }
        val result = helper(shell, asset = null).writeBand(HotspotBand.FIVE_GHZ)
        assertTrue(result is ApBandHelper.Result.Unavailable)
        assertNull(helper(shell, asset = null).readBand())
    }

    @Test
    fun reportsAnAppliedBandChannelWriteWhenTheFrameworkReadsBothBack() {
        val shell = FakeShell { command ->
            when {
                command.contains("HotspotAp get") -> "HOTSPOTAP:GET band=0 channel=0 ssid=x"
                command.contains("HotspotAp setbc") ->
                    "HOTSPOTAP:BEFORE band=0 channel=0 ssid=x\n" +
                        "HOTSPOTAP:SET ok=true\n" +
                        "HOTSPOTAP:AFTER band=1 channel=149 ssid=x"
                else -> ""
            }
        }
        val result = helper(shell).writeBandChannel(HotspotBand.FIVE_GHZ, 149)
        assertEquals(ApBandHelper.Result.Applied(1), result)
        assertTrue(shell.commands.any { it.contains("setbc 1 149 'com.hpplay'") })
    }

    @Test
    fun refusesWhenTheChannelDoesNotStick() {
        val shell = FakeShell { command ->
            when {
                command.contains("HotspotAp get") -> "HOTSPOTAP:GET band=0 channel=0 ssid=x"
                command.contains("HotspotAp setbc") ->
                    "HOTSPOTAP:SET ok=true\nHOTSPOTAP:AFTER band=1 channel=36 ssid=x"
                else -> ""
            }
        }
        val result = helper(shell).writeBandChannel(HotspotBand.FIVE_GHZ, 149)
        assertTrue(result is ApBandHelper.Result.Refused)
        assertTrue((result as ApBandHelper.Result.Refused).detail.contains("channel did not stick"))
    }

    @Test
    fun doesNotClaimDeployedWhenTheHelperNeverAnswers() {
        // Deploy "succeeds" (no output) but the helper cannot be run: the read yields nothing, so the
        // helper must not be treated as usable.
        val shell = FakeShell { command ->
            if (command.contains("<<'XCP'")) "" else "sh: HotspotAp: not found"
        }
        val helper = helper(shell)
        assertNull(helper.readBand())
        assertTrue(helper.writeBand(HotspotBand.FIVE_GHZ) is ApBandHelper.Result.Unavailable)
    }
}
