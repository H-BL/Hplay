package com.hpplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SoftAP radio-frequency parsing that replaces the old "is ap0 UP" fake-success check.
 *
 * `hostapd_cli -i ap0 status` is the authoritative source of the band the radio actually came up on,
 * so the exact formats — and the 2.4 vs 5 GHz decision — are asserted here.
 */
class SoftApRadioTest {
    private val fiveGhzStatus = "state=ENABLED\nfreq=5240\nchannel=48\nssid[0]=F510C-000000\n"
    private val twoGhzStatus = "state=ENABLED\nfreq=2462\nchannel=11\nssid[0]=F510C-000000\n"

    @Test
    fun readsTheReportedFrequency() {
        assertEquals(5240, SoftApRadio.frequencyMHz(fiveGhzStatus))
        assertEquals(2462, SoftApRadio.frequencyMHz(twoGhzStatus))
        assertEquals(5825, SoftApRadio.frequencyMHz("freq=5825\nchannel=165"))
    }

    @Test
    fun classifiesTheBandFromTheFrequency() {
        assertTrue(SoftApRadio.isFiveGhz(5240))
        assertTrue(SoftApRadio.isFiveGhz(5825))
        assertFalse(SoftApRadio.isFiveGhz(2462))
        assertFalse(SoftApRadio.isFiveGhz(null))
    }

    @Test
    fun readsTheEnabledState() {
        assertTrue(SoftApRadio.isEnabled(fiveGhzStatus))
        assertFalse(SoftApRadio.isEnabled("state=DISABLED"))
        assertFalse(SoftApRadio.isEnabled(""))
    }

    @Test
    fun treatsAnUpInterfaceAsUp() {
        assertTrue(
            SoftApRadio.isLinkUp(
                "13: ap0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP",
            ),
        )
        assertFalse(SoftApRadio.isLinkUp("Device \"ap0\" does not exist."))
        assertFalse(SoftApRadio.isLinkUp(""))
    }

    @Test
    fun namesTheBand() {
        assertEquals("5 GHz", SoftApRadio.label(5240))
        assertEquals("2.4 GHz", SoftApRadio.label(2437))
        assertEquals("6000 MHz", SoftApRadio.label(6000))
        assertNull(SoftApRadio.label(null))
    }

    @Test
    fun missingFrequencyIsUnknownNotZero() {
        assertNull(SoftApRadio.frequencyMHz("state=ENABLED"))
        assertNull(SoftApRadio.frequencyMHz(null))
    }
}
