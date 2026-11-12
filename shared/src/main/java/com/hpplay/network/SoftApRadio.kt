package com.hpplay.network

/**
 * Reads the real operating band of the SoftAP from hostapd's own status output.
 *
 * On this ROM neither the on-disk hostapd config nor the framework's `mApConfig.apBand` proves what
 * the radio is doing — the config is a HAL product and the framework only states its *intent*. The
 * one authoritative source is hostapd itself:
 *
 * ```
 * $ hostapd_cli -i ap0 status
 * state=ENABLED
 * freq=5240
 * channel=48
 * ```
 *
 * A `freq` in the 5 GHz range (roughly 5150–5895 MHz) means the hotspot is genuinely on 5 GHz; a
 * 2.4 GHz `freq` (2400–2500) means the "force" did not take. The parsing is kept free of Android
 * types so the exact format is unit-tested — a silent change here would reintroduce the fake success
 * this check exists to prevent.
 */
internal object SoftApRadio {
    /** Inclusive bounds of the 5 GHz band, with a little slack for regional channel plans. */
    private const val FIVE_GHZ_LOW_MHZ = 4900
    private const val FIVE_GHZ_HIGH_MHZ = 5925

    private val FREQUENCY = Regex("""(?m)^\s*freq\s*=\s*(\d+)""")
    private val STATE = Regex("""(?m)^\s*state\s*=\s*(\w+)""")

    /** The frequency hostapd reports in MHz, or null when it did not report one. */
    fun frequencyMHz(status: String?): Int? =
        FREQUENCY.find(status.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    /** True when [frequencyMHz] is in the 5 GHz band. */
    fun isFiveGhz(frequencyMHz: Int?): Boolean =
        frequencyMHz != null && frequencyMHz in FIVE_GHZ_LOW_MHZ..FIVE_GHZ_HIGH_MHZ

    /** True when hostapd reports the BSS as enabled. */
    fun isEnabled(status: String?): Boolean =
        STATE.find(status.orEmpty())?.groupValues?.get(1).equals("ENABLED", ignoreCase = true)

    /** True when `ip link show` output shows the interface administratively up. */
    fun isLinkUp(linkOutput: String?): Boolean {
        val text = linkOutput.orEmpty()
        return text.contains("<") && text.substringAfter("<").substringBefore(">")
            .split(',')
            .any { it.trim().equals("UP", ignoreCase = true) }
    }

    /** Human-readable band name for [frequencyMHz], or null when it is unknown. */
    fun label(frequencyMHz: Int?): String? = when {
        frequencyMHz == null -> null
        frequencyMHz in FIVE_GHZ_LOW_MHZ..FIVE_GHZ_HIGH_MHZ -> "5 GHz"
        frequencyMHz in 2400..2500 -> "2.4 GHz"
        else -> "$frequencyMHz MHz"
    }
}
