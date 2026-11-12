package com.hpplay.network

/**
 * Resolves the live Wi-Fi channel of a running hotspot from the kernel driver.
 *
 * This is the Android 9 fallback path: on those ROMs the SoftAP configuration is not exposed, so
 * [LegacyHotspotRadio] is asked directly and its IEEE 802.11 frequency is converted with the same
 * mapping the rest of the app uses. A frequency that cannot be mapped stays unknown (`null`) instead
 * of being guessed, so the iPhone is never told a channel the radio is not actually using.
 */
internal object HotspotRadioChannel {
    /**
     * Returns the driver-reported channel of [interfaceName], or null when it is unknown or the
     * native library is unavailable.
     */
    fun readChannel(interfaceName: String?): Int? {
        if (interfaceName.isNullOrEmpty()) return null
        val response = LegacyHotspotRadio.query(interfaceName) ?: return null
        if (response.size < 3 || response[0] != 0) return null
        return channelFromDriverFrequency(response[1], response[2])
    }

    /**
     * Maps an `iw_freq` mantissa/exponent pair to a channel.
     *
     * Different Wi-Fi drivers fill the struct differently, so three encodings are accepted:
     * a bare channel number, a frequency in MHz, and a frequency in kHz. Anything else is unknown.
     */
    internal fun channelFromDriverFrequency(mantissa: Int, exponent: Int): Int? {
        if (mantissa <= 0 || exponent < 0 || exponent > 9) return null

        // Some drivers report the channel itself with a zero exponent.
        if (exponent == 0 && mantissa < 1000) {
            return when (mantissa) {
                in 1..13 -> wifiFrequencyMhzToChannel(mantissa * 5 + 2407)
                14 -> wifiFrequencyMhzToChannel(2484)
                in 32..177 -> wifiFrequencyMhzToChannel(mantissa * 5 + 5000)
                else -> null
            }
        }

        var value = mantissa.toLong()
        repeat(exponent) { value *= 10 }
        if (value <= 0 || value > Int.MAX_VALUE) return null
        val scaled = value.toInt()

        // Prefer MHz; fall back to kHz for drivers that report the base unit.
        wifiFrequencyMhzToChannel(scaled)?.let { return it }
        if (scaled % 1000 == 0) {
            wifiFrequencyMhzToChannel(scaled / 1000)?.let { return it }
        }
        return null
    }
}
