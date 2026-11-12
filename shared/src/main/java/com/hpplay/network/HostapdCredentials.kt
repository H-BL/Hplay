package com.hpplay.network

/**
 * Reads the live SSID/passphrase out of a hostapd configuration body.
 *
 * The forced 5 GHz flow raises the SoftAP through the system tethering service, which keeps using
 * the credentials already stored in hostapd. To hand those same credentials to the iPhone the flow
 * has to read them back: hostapd stores the SSID either as a quoted `ssid=` string or as the hex
 * `ssid2=` field, and the passphrase in `wpa_passphrase=`. Parsing lives here, free of Android APIs,
 * so the field layouts can be unit-tested.
 */
internal object HostapdCredentials {
    /** Returns the SSID, preferring a plain `ssid=` over the hex `ssid2=` form. */
    fun ssid(config: String?): String? {
        if (config.isNullOrEmpty()) return null
        HostapdConfig.readValue(config, "ssid")
            ?.let(::unquote)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return HostapdConfig.readValue(config, "ssid2")
            ?.let(::decodeHex)
            ?.takeIf { it.isNotEmpty() }
    }

    /** Returns the WPA passphrase, or null when the configuration has none. */
    fun passphrase(config: String?): String? {
        if (config.isNullOrEmpty()) return null
        return HostapdConfig.readValue(config, "wpa_passphrase")?.let(::unquote)
    }

    /** True when the configuration enables WPA/WPA2/WPA3 (`wpa=` is non-zero). */
    fun isSecured(config: String?): Boolean {
        if (config.isNullOrEmpty()) return false
        val wpa = HostapdConfig.readValue(config, "wpa")?.toIntOrNull() ?: return false
        return wpa > 0
    }

    private fun unquote(value: String): String {
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return value.substring(1, value.length - 1)
        }
        return value
    }

    /** Decodes a hostapd `ssid2=` hex field into text; returns null when it is not valid hex. */
    private fun decodeHex(hex: String): String? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return buildString(hex.length / 2) {
            for (index in hex.indices step 2) {
                append(hex.substring(index, index + 2).toInt(16).toChar())
            }
        }
    }
}
