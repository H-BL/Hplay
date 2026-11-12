package com.hpplay.network

/**
 * Pure text helpers for the F510C hostapd configuration.
 *
 * Note the on-disk `hostapd_ap0.conf` is a **vendor-HAL product** on this ROM, not an input: hostapd
 * is started without a config file and receives its parameters over hwbinder, so this file is read
 * only to learn the SSID/passphrase the SoftAP broadcasts, never to select the band. The band is
 * chosen through the framework SoftAp configuration (see [ForceFiveGhzHotspotManager]).
 *
 * Everything here is free of Android APIs so it can be unit-tested.
 */
internal object HostapdConfig {
    private val SETTING_LINE = Regex("""^[A-Za-z0-9_]+=.*$""")

    /**
     * Strips shell echo/noise from raw `cat` output and keeps only the configuration body: comment
     * lines and `key=value` settings, plus blank lines that appear between them.
     */
    fun extractConfigText(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val kept = ArrayList<String>()
        var seenSetting = false

        for (line in normalized.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (seenSetting) kept.add("")
                continue
            }
            if (isShellNoise(trimmed)) continue
            if (trimmed.startsWith("#") || SETTING_LINE.matches(trimmed)) {
                kept.add(trimmed)
                seenSetting = true
            }
        }
        while (kept.isNotEmpty() && kept.last().isEmpty()) {
            kept.removeAt(kept.size - 1)
        }
        return kept.joinToString("\n")
    }

    /** Reads a single `key=value` from a configuration body, or null when absent. */
    fun readValue(config: String?, key: String): String? {
        if (config.isNullOrEmpty()) return null
        val prefix = "$key="
        return config.lineSequence()
            .firstOrNull { it.startsWith(prefix) }
            ?.substring(prefix.length)
            ?.trim()
    }

    /**
     * Compares two configuration bodies line by line and returns a human-readable difference
     * description, or null when they are identical.
     */
    fun describeDifference(expected: String, actual: String): String? {
        val expectedLines = expected.replace("\r\n", "\n").split("\n")
        val actualLines = actual.replace("\r\n", "\n").split("\n")
        for (index in 0 until maxOf(expectedLines.size, actualLines.size)) {
            val left = expectedLines.getOrNull(index)
            val right = actualLines.getOrNull(index)
            if (left != right) {
                return "line ${index + 1}: expected '${left ?: "<missing>"}', " +
                    "found '${right ?: "<missing>"}'"
            }
        }
        return null
    }

    private fun isShellNoise(trimmed: String): Boolean {
        if (trimmed.contains(":/ #")) return true
        val lower = trimmed.lowercase()
        return lower.startsWith("cat ") ||
            lower.startsWith("sh -c ") ||
            lower.startsWith("printf ") ||
            lower.startsWith("echo ") ||
            lower.startsWith("service call wifi ") ||
            lower == "exit"
    }
}
