package com.hpplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the parsing of the `HotspotAp` helper's output.
 *
 * The helper is the only way to read/write the framework SoftAp band on this ROM, and a silent change
 * to its line format would turn "5 GHz is selected" into a false success, so the exact strings are
 * asserted here.
 */
class HotspotBandTest {
    private val readLine = "HOTSPOTAP:GET band=1 channel=0 ssid=F510C-000000"
    private val setLine = "HOTSPOTAP:BEFORE band=0 channel=0 ssid=F510C-000000\n" +
        "HOTSPOTAP:SET ok=true\n" +
        "HOTSPOTAP:AFTER band=1 channel=0 ssid=F510C-000000"

    @Test
    fun readsTheBandFromTheGetterLine() {
        assertEquals(1, HotspotBand.parseRead(readLine))
        assertEquals(0, HotspotBand.parseRead("HOTSPOTAP:GET band=0 channel=6 ssid=x"))
    }

    @Test
    fun readsTheBandTheFrameworkReportsAfterAWrite() {
        assertEquals(1, HotspotBand.parseAfter(setLine))
    }

    @Test
    fun readsTheChannelTheFrameworkReportsAfterAWrite() {
        assertEquals(0, HotspotBand.parseAfterChannel(setLine))
        val locked = "HOTSPOTAP:BEFORE band=0 channel=0 ssid=x\n" +
            "HOTSPOTAP:SET ok=true\n" +
            "HOTSPOTAP:AFTER band=1 channel=149 ssid=x"
        assertEquals(149, HotspotBand.parseAfterChannel(locked))
        assertNull(HotspotBand.parseAfterChannel("HOTSPOTAP:SET ok=false"))
    }

    @Test
    fun readsTheConfiguredChannelBackAndKeepsAutoDistinguishable() {
        assertEquals(36, HotspotBand.parseReadChannel("HOTSPOTAP:GET band=1 channel=36 ssid=x"))
        assertEquals(149, HotspotBand.parseReadChannel("HOTSPOTAP:GET band=1 channel=149 ssid=x"))
        // 0 is the framework's "auto": it must not be confused with a real channel.
        assertNull(HotspotBand.parseReadChannel(readLine))
        assertNull(HotspotBand.parseReadChannel("HOTSPOTAP:ERR nope"))
        assertNull(HotspotBand.parseReadChannel(null))
    }

    @Test
    fun parsesTheSetOutcome() {
        assertEquals(true, HotspotBand.parseSetOk(setLine))
        assertEquals(false, HotspotBand.parseSetOk("HOTSPOTAP:SET ok=false"))
        assertNull(HotspotBand.parseSetOk("HOTSPOTAP:BEFORE band=0 channel=0"))
    }

    @Test
    fun surfacesTheHelpersOwnErrorText() {
        val error = HotspotBand.parseError(
            "HOTSPOTAP:ERR java.lang.SecurityException: App not allowed to read or update stored WiFi Ap config (uid = 0)",
        )
        assertTrue(error!!.contains("SecurityException"))
        assertNull(HotspotBand.parseError(setLine))
    }

    @Test
    fun rejectsOutputThatNeverAnswered() {
        assertNull(HotspotBand.parseRead(""))
        assertNull(HotspotBand.parseRead(null))
        assertNull(HotspotBand.parseRead("sh: HotspotAp: not found"))
        assertNull(HotspotBand.parseAfter("HOTSPOTAP:SET ok=false"))
    }

    @Test
    fun namesTheBands() {
        assertTrue(HotspotBand.isFiveGhz(1))
        assertFalse(HotspotBand.isFiveGhz(0))
        assertFalse(HotspotBand.isFiveGhz(null))
        assertEquals("5 GHz", HotspotBand.label(1))
        assertEquals("2.4 GHz", HotspotBand.label(0))
        assertNull(HotspotBand.label(7))
    }
    @Test
    fun readsAndConfirmsTheSsid() {
        assertEquals("F510C-000000", HotspotBand.parseGetSsid(readLine))
        assertEquals("Hplay-abcd", HotspotBand.parseGetSsid("HOTSPOTAP:GET band=1 channel=0 ssid=Hplay-abcd"))
        assertNull(HotspotBand.parseGetSsid("HOTSPOTAP:ERR get failed"))
    }

    @Test
    fun confirmsAnSsidWrite() {
        val writeLine = "HOTSPOTAP:BEFORE band=1 channel=0 ssid=星光热点\n" +
            "HOTSPOTAP:SETSSID ok=true\n" +
            "HOTSPOTAP:AFTER band=1 channel=0 ssid=Hplay-abcd"
        assertEquals(true, HotspotBand.parseSetSsidOk(writeLine))
        assertEquals("Hplay-abcd", HotspotBand.parseAfterSsid(writeLine))
        assertNull(HotspotBand.parseSetSsidOk("HOTSPOTAP:SET ok=true"))
        assertNull(HotspotBand.parseAfterSsid("HOTSPOTAP:SETSSID ok=false"))
    }
}
