package com.hpplay.network

/**
 * Where a channel observation came from, ordered from most to least trustworthy.
 *
 * The head unit's on-disk hostapd product and the framework's own `apBand`/`apChannel` are both
 * *intent*, not radio state; only hostapd's live status and the kernel driver describe the channel
 * the radio is actually transmitting on. The source is carried along so a mismatch can be reported
 * ("asked for 36, hostapd says 149") instead of silently advertising the wrong channel to the
 * iPhone in the iAP2 Wi-Fi payload.
 */
enum class ChannelSource(val label: String) {
    /** `hostapd_cli -i ap0 status` — the radio's own report. */
    HOSTAPD("hostapd"),

    /** `SIOCGIWFREQ` through the legacy JNI shim — Android 9 driver read. */
    DRIVER("driver"),

    /** `iw dev ap0 info` — the driver through the platform shell. */
    IW("iw"),

    /** The hostapd product on disk — a vendor HAL output, never authoritative. */
    CONFIG("config"),

    /** The framework SoftAp configuration / connection info. */
    FRAMEWORK("framework"),

    UNKNOWN("unknown"),
}

/** One observation of the operating channel together with where it came from. */
data class ApChannel(
    val channel: Int,
    val frequencyMhz: Int?,
    val source: ChannelSource,
) {
    /** True when a real channel was observed (0 means "unknown", the iAP2 value for auto). */
    val hasChannel: Boolean get() = channel > 0

    /**
     * True when the observation comes from the radio (hostapd, the driver or `iw`) rather than from
     * a file the vendor HAL rewrites. Only a trusted observation may be sent to the iPhone: the
     * on-disk product can still name the channel a previous run asked for, and advertising it is
     * what makes the phone look for a network that is not there.
     */
    val trustworthy: Boolean
        get() = hasChannel && (source == ChannelSource.HOSTAPD ||
            source == ChannelSource.DRIVER ||
            source == ChannelSource.IW)

    /**
     * True when the channel is in UNII-3 (149-165).
     *
     * This is the range a `country 00` world regulatory domain commonly marks NO-IR, which is what
     * makes a 5 GHz station link come up but never carry data — the symptom that produced the
     * "149 cannot connect, 36 works" field report.
     */
    val isUnii3: Boolean get() = channel in 149..165

    /** True when the channel is in UNII-1 (36-48), the widest-compatibility 5 GHz range. */
    val isUnii1: Boolean get() = channel in 36..48
}

/**
 * Reads the head unit's *actual* Wi-Fi channel rather than echoing back what was requested.
 *
 * All parsing is kept here, free of Android types, so the exact hostapd/`iw`/regulatory shapes are
 * unit-tested: a silent change to one of them would turn "the hotspot is on 149" into a guess.
 */
object LocalChannelProbe {
    private val IW_CHANNEL = Regex("""(?m)^\s*channel\s+(\d+)\s*\((\d+)\s*MHz\)""")
    private val REG_RANGE = Regex("""\((\d+)\s*-\s*(\d+)\s*@\s*\d+\),\s*\(\d+,\s*\d+\),\s*\((?:[^)]*)\)\s*(.*)""")

    /** Reads the channel out of `hostapd_cli -i ap0 status` (`freq=5745`). */
    fun fromHostapdStatus(raw: String?): ApChannel? {
        val frequency = SoftApRadio.frequencyMHz(raw) ?: return null
        val channel = wifiFrequencyMhzToChannel(frequency) ?: return null
        return ApChannel(channel, frequency, ChannelSource.HOSTAPD)
    }

    /** Reads the channel out of `iw dev ap0 info` (`channel 36 (5180 MHz)`). */
    fun fromIwDevInfo(raw: String?): ApChannel? {
        val match = IW_CHANNEL.find(raw.orEmpty()) ?: return null
        val channel = match.groupValues[1].toIntOrNull() ?: return null
        val frequency = match.groupValues[2].toIntOrNull() ?: wifiChannelToFrequencyMhz(channel, 2)
        if (channel <= 0) return null
        return ApChannel(channel, frequency, ChannelSource.IW)
    }

    /** Reads the channel out of the on-disk hostapd product. Weakest source; used only as a hint. */
    fun fromHostapdConfig(raw: String?): ApChannel? {
        val channel = HostapdConfig.readValue(raw, "channel")?.trim()?.toIntOrNull() ?: return null
        if (channel <= 0) return null
        val frequency = wifiChannelToFrequencyMhz(channel, 2)
        return ApChannel(channel, frequency, ChannelSource.CONFIG)
    }

    /** Wraps a frequency reported by the framework or the driver, when it maps to a channel. */
    fun fromFrequency(frequencyMHz: Int?, source: ChannelSource): ApChannel? {
        if (frequencyMHz == null) return null
        val channel = wifiFrequencyMhzToChannel(frequencyMHz) ?: return null
        return ApChannel(channel, frequencyMHz, source)
    }

    /**
     * Whether the regulatory domain blocks UNII-3 (5725-5850), from `iw reg get`.
     *
     * In the `country 00` world domain these ranges are usually flagged `NO-IR`, which lets a
     * station associate passively but keeps it from initiating a transmission — a link that looks up
     * and carries no data. Returns null when the output does not describe that range at all.
     */
    fun regDomainBlocksUnii3(raw: String?): Boolean? {
        val text = raw.orEmpty()
        if (text.isBlank()) return null
        var found = false
        for (line in text.lineSequence()) {
            val match = REG_RANGE.find(line) ?: continue
            val start = match.groupValues[1].toIntOrNull() ?: continue
            val end = match.groupValues[2].toIntOrNull() ?: continue
            if (UNII3_FREQUENCY !in start..end) continue
            found = true
            val flags = match.groupValues[3].uppercase()
            if (flags.contains("DISABLED")) return true
            if (flags.contains("NO-IR")) return true
            if (flags.contains("PASSIVE-SCAN")) return true
            return false
        }
        return if (found) false else null
    }

    /**
     * Observes the AP interface's channel, trying the sources in descending trust order.
     *
     * [shell] runs one command on the head unit and returns its output; passing null disables the
     * shell-based sources so the driver read still works on a unit with no adb channel.
     */
    fun probe(
        interfaceName: String?,
        configText: String?,
        shell: ((String) -> String?)?,
    ): ApChannel {
        if (!interfaceName.isNullOrBlank() && shell != null) {
            runCatching { shell(FiveGhzHotspotCommands.softApStatus(interfaceName)) }
                .getOrNull()?.let { fromHostapdStatus(it) }?.let { return it }
        }
        if (!interfaceName.isNullOrBlank()) {
            runCatching { HotspotRadioChannel.readChannel(interfaceName) }
                .getOrNull()?.let { channel ->
                    if (channel > 0) {
                        return ApChannel(
                            channel = channel,
                            frequencyMhz = wifiChannelToFrequencyMhz(channel, 2),
                            source = ChannelSource.DRIVER,
                        )
                    }
                }
        }
        if (!interfaceName.isNullOrBlank() && shell != null) {
            runCatching { shell("iw dev $interfaceName info") }
                .getOrNull()?.let { fromIwDevInfo(it) }?.let { return it }
        }
        fromHostapdConfig(configText)?.let { return it }
        return ApChannel(0, null, ChannelSource.UNKNOWN)
    }

    /** One line for the session log: what was asked for versus what the radio reports. */
    fun reportLine(requested: Int, observed: ApChannel, backend: String): String {
        val actual = if (observed.hasChannel) observed.channel.toString() else "unknown"
        val frequency = observed.frequencyMhz?.let { "${it}MHz" } ?: "unknown"
        return "Wi-Fi: channel requested=${if (requested > 0) requested.toString() else "auto"} " +
            "actual=$actual source=${observed.source.label} freq=$frequency backend=$backend"
    }

    private const val UNII3_FREQUENCY = 5745
}
