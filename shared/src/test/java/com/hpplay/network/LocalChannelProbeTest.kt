package com.hpplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the channel observation used to decide what the iPhone is told in the iAP2 Wi-Fi payload.
 *
 * The shapes asserted here come from the head unit itself (`hostapd_cli`, `iw dev`, `iw reg get`,
 * the on-disk hostapd product). A silent change to one of them would advertise a channel the radio
 * is not on, which is a link that comes up and never connects.
 */
class LocalChannelProbeTest {
    @Test
    fun hostapdStatusIsTheTrustedSource() {
        val observed = LocalChannelProbe.fromHostapdStatus(
            """
            state=ENABLED
            freq=5745
            channel=149
            """.trimIndent(),
        )
        assertEquals(149, observed?.channel)
        assertEquals(5745, observed?.frequencyMhz)
        assertEquals(ChannelSource.HOSTAPD, observed?.source)
        assertTrue(observed!!.trustworthy)
        assertTrue(observed.isUnii3)
        assertFalse(observed.isUnii1)
    }

    @Test
    fun hostapdStatusWithoutFrequencyIsUnknownInsteadOfGuessed() {
        assertNull(LocalChannelProbe.fromHostapdStatus("state=ENABLED"))
        assertNull(LocalChannelProbe.fromHostapdStatus(null))
    }

    @Test
    fun iwDevInfoYieldsTheChannelAndFrequency() {
        val observed = LocalChannelProbe.fromIwDevInfo(
            """
            Interface ap0
            	addr 52:c3:a2:e4:9e:cd
            	ssid lampcat
            	channel 36 (5180 MHz), width: 80 MHz
            """.trimIndent(),
        )
        assertEquals(36, observed?.channel)
        assertEquals(5180, observed?.frequencyMhz)
        assertEquals(ChannelSource.IW, observed?.source)
        assertTrue(observed!!.isUnii1)
    }

    @Test
    fun hostapdProductIsAVendorHintAndMustNotBeAdvertised() {
        val observed = LocalChannelProbe.fromHostapdConfig("interface=ap0\nchannel=149\nhw_mode=a\n")
        assertEquals(149, observed?.channel)
        assertEquals(ChannelSource.CONFIG, observed?.source)
        assertFalse(observed!!.trustworthy)
    }

    @Test
    fun worldRegulatoryDomainFlagsUnii3AsNoInitiatingRadiation() {
        val world = """
            global
            country 00: DFS-UNSET
            	(2402 - 2472 @ 40), (6, 20), (N/A)
            	(5150 - 5250 @ 80), (6, 20), (N/A), PASSIVE-SCAN
            	(5250 - 5350 @ 80), (6, 20), (N/A), NO-OUTDOOR, DFS, PASSIVE-SCAN
            	(5470 - 5725 @ 160), (6, 20), (N/A), PASSIVE-SCAN
            	(5725 - 5850 @ 80), (6, 20), (N/A), NO-IR
        """.trimIndent()
        assertEquals(true, LocalChannelProbe.regDomainBlocksUnii3(world))
    }

    @Test
    fun usRegulatoryDomainAllowsUnii3() {
        val us = """
            global
            country US: DFS-FCC
            	(2402 - 2472 @ 40), (6, 30), (N/A)
            	(5170 - 5250 @ 80), (6, 24), (N/A)
            	(5725 - 5850 @ 80), (6, 30), (N/A)
        """.trimIndent()
        assertEquals(false, LocalChannelProbe.regDomainBlocksUnii3(us))
    }

    @Test
    fun regDomainWithoutTheRangeIsUnknown() {
        assertNull(LocalChannelProbe.regDomainBlocksUnii3(null))
        assertNull(LocalChannelProbe.regDomainBlocksUnii3("global\ncountry 00: DFS-UNSET\n"))
    }

    @Test
    fun probeFallsBackFromHostapdToIwAndThenToConfig() {
        val shells = mutableListOf<String>()
        val observed = LocalChannelProbe.probe(
            interfaceName = "ap0",
            configText = "channel=149\n",
            shell = { command ->
                shells += command
                when {
                    command.contains("hostapd_cli") -> "state=ENABLED\nfreq=5745\n"
                    else -> "channel 40 (5200 MHz)\n"
                }
            },
        )
        assertEquals(ChannelSource.HOSTAPD, observed.source)
        assertEquals(149, observed.channel)

        val iwOnly = LocalChannelProbe.probe(
            interfaceName = "ap0",
            configText = "channel=149\n",
            shell = { command -> if (command.contains("hostapd_cli")) "" else "channel 40 (5200 MHz)" },
        )
        assertEquals(ChannelSource.IW, iwOnly.source)
        assertEquals(40, iwOnly.channel)
    }

    @Test
    fun probeReportsUnknownRatherThanTheConfiguredChannel() {
        val observed = LocalChannelProbe.probe(
            interfaceName = null,
            configText = null,
            shell = null,
        )
        assertEquals(0, observed.channel)
        assertEquals(ChannelSource.UNKNOWN, observed.source)
        assertFalse(observed.trustworthy)
    }

    @Test
    fun reportLineCarriesRequestedAndActual() {
        val line = LocalChannelProbe.reportLine(
            requested = 36,
            observed = ApChannel(149, 5745, ChannelSource.HOSTAPD),
            backend = "Forced 5 GHz hotspot",
        )
        assertTrue("requested=36" in line)
        assertTrue("actual=149" in line)
        assertTrue("source=hostapd" in line)
        assertTrue("freq=5745MHz" in line)
    }
}
