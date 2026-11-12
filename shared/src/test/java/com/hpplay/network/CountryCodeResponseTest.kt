package com.hpplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryCodeResponseTest {
    @Test
    fun parsesHexParcelLittleEndian() {
        val raw = "Result: Parcel(00000000 00000002 00530055 00000000 '........U.S.....')"
        assertEquals("US", CountryCodeResponse.parse(raw))
        assertTrue(CountryCodeResponse.isExpected(raw))
    }

    @Test
    fun parsesTheVerbatimReplyFromAnAndroid9Device() {
        // Captured from a real Android 9 (API 28) device for `service call wifi 28`; this is exactly
        // what the head unit's ROM answers, so the parser is pinned against the real bytes.
        val raw = "Result: Parcel(00000000 00000002 00530055 00000000 '........U.S.....')"
        assertEquals("US", CountryCodeResponse.parse(raw))
        assertTrue(CountryCodeResponse.isExpected(raw))
    }

    @Test
    fun parsesHexParcelWithoutPrintableHint() {
        assertEquals("US", CountryCodeResponse.parse("Result: Parcel(00530055)"))
    }

    @Test
    fun parsesPlainAndDottedForms() {
        assertEquals("US", CountryCodeResponse.parse("US"))
        assertEquals("US", CountryCodeResponse.parse("Result: US"))
        assertEquals("US", CountryCodeResponse.parse("country=U.S"))
    }

    @Test
    fun lowercaseUsInProseIsNotMistakenForACountryCode() {
        // Regression: a ROM error string containing the pronoun "us" must not look like a set
        // country code, or the flow would skip the injection and the hotspot would never come up.
        assertNull(CountryCodeResponse.parse("Result: Parcel(00000000 fffffffc '..........')"))
        assertNull(CountryCodeResponse.parse("Unknown error, please tell us about it"))
        assertNull(CountryCodeResponse.parse("failed to us the service"))
        assertFalse(CountryCodeResponse.isExpected("country code is not set for us"))
    }

    @Test
    fun uppercaseCodeEmbeddedInTextIsAccepted() {
        assertEquals("US", CountryCodeResponse.parse("Result: US set successfully"))
    }

    @Test
    fun rejectsUnrelatedOrEmptyResponses() {
        assertNull(CountryCodeResponse.parse(null))
        assertNull(CountryCodeResponse.parse(""))
        assertNull(CountryCodeResponse.parse("Result: Parcel(00000000 00000000 00000000 00000000)"))
        assertNull(CountryCodeResponse.parse("Result: Parcel(0043004e)")) // "NC"
        assertNull(CountryCodeResponse.parse("Result: Parcel(00550053)")) // "SU"
        assertFalse(CountryCodeResponse.isExpected("no country here"))
    }
}
