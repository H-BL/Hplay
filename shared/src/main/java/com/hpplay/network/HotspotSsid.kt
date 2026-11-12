package com.hpplay.network

/**
 * Rules for the hotspot SSID this app advertises.
 *
 * The iPhone matches the SSID it was handed over iAP2 against the beacons it actually sees. A name
 * with non-ASCII characters is stored as a UTF-8 SSID and the ROM then advertises it as
 * `ssid2=<hex>`, which many iPhones will not match — the hotspot simply never shows up for them.
 * This keeps the advertised name to printable ASCII and rewrites anything else to a stable default.
 */
object HotspotSsid {
    /** Longest SSID the 802.11 field allows, in bytes. */
    const val MAX_BYTES = 32

    /** Prefix of the generated fallback name. */
    const val PREFIX = "Hplay"

    /** True when every character is printable ASCII (0x20..0x7E), so it can be advertised verbatim. */
    fun isAscii(name: String): Boolean = name.isNotEmpty() && name.all { it.code in 0x20..0x7E }

    /**
     * The SSID to advertise: [name] itself when it is already printable ASCII, otherwise a stable
     * [defaultName]. Blank names fall through to the default too.
     */
    fun sanitize(name: String?, seed: String): String {
        val trimmed = name?.trim().orEmpty()
        return if (isAscii(trimmed)) trimmed else defaultName(seed)
    }

    /**
     * A deterministic ASCII name derived from [seed], so the same head unit always advertises the
     * same SSID across restarts instead of a fresh random name on every boot.
     */
    fun defaultName(seed: String): String {
        val suffix = seed.filter { it.isLetterOrDigit() }.takeLast(4).padStart(4, '0')
        return "$PREFIX-$suffix"
    }
}
