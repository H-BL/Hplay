package com.hpplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the ASCII-only SSID rule that keeps a Chinese hotspot name from being unjoinable. */
class HotspotSsidTest {
    @Test
    fun asciiNamesAreAdvertisedVerbatim() {
        assertEquals("StarlightS", HotspotSsid.sanitize("StarlightS", "seed"))
        assertEquals("F510C-000000", HotspotSsid.sanitize("F510C-000000", "seed"))
    }

    @Test
    fun chineseNamesAreReplacedByTheDefault() {
        // "星光热点" would be stored as a UTF-8 SSID and advertised as ssid2=<hex>.
        assertEquals(HotspotSsid.defaultName("abcd1234"), HotspotSsid.sanitize("星光热点", "abcd1234"))
    }

    @Test
    fun blankAndWhitespaceNamesFallBackToTheDefault() {
        assertEquals(HotspotSsid.defaultName("abcd"), HotspotSsid.sanitize("", "abcd"))
        assertEquals(HotspotSsid.defaultName("abcd"), HotspotSsid.sanitize("   ", "abcd"))
        assertEquals(HotspotSsid.defaultName("abcd"), HotspotSsid.sanitize(null, "abcd"))
    }

    @Test
    fun defaultNameIsAsciiAndStableForTheSameSeed() {
        val name = HotspotSsid.defaultName("deadbeef")
        assertTrue(HotspotSsid.isAscii(name))
        assertTrue(name.startsWith(HotspotSsid.PREFIX))
        assertEquals(name, HotspotSsid.defaultName("deadbeef"))
    }

    @Test
    fun asciiCheckRejectsControlBytesAndEmptyNames() {
        assertFalse(HotspotSsid.isAscii(""))
        assertFalse(HotspotSsid.isAscii("bad\u0000name"))
        assertFalse(HotspotSsid.isAscii("tab\there"))
        assertTrue(HotspotSsid.isAscii("plain-5G"))
    }
}
