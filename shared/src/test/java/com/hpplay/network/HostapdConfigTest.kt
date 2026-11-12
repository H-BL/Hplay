package com.hpplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostapdConfigTest {
    @Test
    fun stripsShellNoiseFromCatOutput() {
        val raw = "cat /data/vendor/wifi/hostapd/hostapd_ap0.conf\n" +
            "channel=165\n" +
            "ieee80211ac=0\n" +
            "hw_mode=a\n" +
            ":/ # exit\n"
        val cleaned = HostapdConfig.extractConfigText(raw)
        assertEquals("channel=165\nieee80211ac=0\nhw_mode=a", cleaned)
        assertEquals("165", HostapdConfig.readValue(cleaned, "channel"))
    }

    @Test
    fun reportsLineByLineDifferences() {
        assertNull(HostapdConfig.describeDifference("a=1\nb=2", "a=1\nb=2"))
        val difference = HostapdConfig.describeDifference("a=1\nb=2", "a=1\nb=3")
        assertTrue(difference != null && difference.contains("line 2"))
    }

    @Test
    fun readsAbsentKeyAsNull() {
        assertNull(HostapdConfig.readValue("channel=149", "ieee80211ac"))
        assertNull(HostapdConfig.readValue(null, "channel"))
    }
}
