package com.hpplay.network

/**
 * The shell vocabulary used to move the head unit's *station* (client) link onto 5 GHz.
 *
 * "Same LAN" mode does not raise an access point: it keeps the car on whatever external Wi-Fi the
 * user joined, and CarPlay needs that link to be 5 GHz. On this MT8666 Android 9 ROM the public
 * `WifiManager` API cannot pick a band, so the switch is driven through the head unit's own adb
 * channel with the platform's `cmd wifi` shell and (as a fallback) `wpa_cli`.
 *
 * Every string is kept here, free of Android types, so the exact bytes are unit-tested — a typo in
 * one of them is otherwise invisible until the feature runs on a car.
 */
object WifiBandCommands {
    /** Station interface on these head units. */
    const val INTERFACE = "wlan0"

    /** Enables or disables the station interface. */
    fun setWifiEnabled(enabled: Boolean): String =
        if (enabled) "svc wifi enable" else "svc wifi disable"

    /** Kicks off a scan (results land a few seconds later). */
    fun startScan(): String = "cmd wifi start-scan"

    /** Lists the latest scan results through the platform shell. */
    fun listScanResults(): String = "cmd wifi list-scan-results"

    /** Raw driver-level scan: stable `freq:`/`SSID:` fields, used when `cmd wifi` is absent. */
    fun iwScan(): String = "iw dev $INTERFACE scan"

    /** `wpa_cli` connection status (contains `ssid=`, `freq=`, `bssid=`). */
    fun wpaStatus(): String = "wpa_cli -i $INTERFACE status"

    /** Saved networks as `id\tssid\tbssid\tflags` lines. */
    fun wpaListNetworks(): String = "wpa_cli -i $INTERFACE list_networks"

    /** Pins a saved network to a specific BSSID (the 5 GHz radio of the same SSID). */
    fun wpaSetNetworkBssid(id: Int, bssid: String): String =
        "wpa_cli -i $INTERFACE set_network $id bssid $bssid"

    /** Selects, enables and reassociates the saved network so the pinned BSSID takes effect. */
    fun wpaSelectNetwork(id: Int): String = "wpa_cli -i $INTERFACE select_network $id"

    fun wpaEnableNetwork(id: Int): String = "wpa_cli -i $INTERFACE enable_network $id"

    fun wpaReassociate(): String = "wpa_cli -i $INTERFACE reassociate"
}

/**
 * Pure parsers for the Wi-Fi band commands above, kept Android-free so they can be unit-tested.
 *
 * The two scan sources differ in shape: `iw dev wlan0 scan` prints one `BSS <mac>` block per AP
 * with `freq:` and `SSID:` lines, while `cmd wifi list-scan-results` prints one row per AP with a
 * BSSID, a frequency and a quoted SSID. Both are accepted so the caller does not depend on which
 * tool a given ROM ships.
 */
object WifiBandParser {
    /** One access point seen in a scan. */
    data class Ap(val ssid: String, val bssid: String, val frequencyMhz: Int)

    /** True when [frequencyMhz] is a 5 GHz (U-NII) frequency. */
    fun is5Ghz(frequencyMhz: Int): Boolean = frequencyMhz in 5150..5895

    private val IW_BSS = Regex("""(?m)^BSS\s+([0-9a-fA-F:]{17})""")
    private val IW_FREQ = Regex("""(?m)^\s*freq:\s*(\d+)""")
    private val IW_SSID = Regex("""(?m)^\s*SSID:\s*(.*)$""")

    /** Parses `iw dev wlan0 scan` output into APs (skipping hidden/blank SSIDs). */
    fun parseIwScan(raw: String): List<Ap> {
        val out = mutableListOf<Ap>()
        val blocks = raw.split(Regex("""(?m)^BSS\s+""")).drop(1)
        for (block in blocks) {
            val bssid = IW_BSS.find("BSS " + block)?.groupValues?.get(1) ?: continue
            val freq = IW_FREQ.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val ssid = IW_SSID.find(block)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() && it != "\u0000" } ?: continue
            out.add(Ap(ssid, bssid.lowercase(), freq))
        }
        return out
    }

    // One row: optional leading columns, a BSSID, a 4-5 digit frequency, then the quoted SSID
    // somewhere on the line. Tolerant to column order differences between ROMs.
    private val CMD_ROW = Regex("""(?m)^.*?([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}).*?\b(\d{4,5})\b.*?$""")

    /** Parses `cmd wifi list-scan-results` output into APs. */
    fun parseCmdScan(raw: String): List<Ap> {
        val out = mutableListOf<Ap>()
        for (line in raw.lineSequence()) {
            val m = CMD_ROW.find(line) ?: continue
            val bssid = m.groupValues[1].lowercase()
            val freq = m.groupValues[2].toIntOrNull() ?: continue
            val quoted = Regex(""""([^"]+)"""").find(line)?.groupValues?.get(1)
            val ssid = quoted ?: continue
            out.add(Ap(ssid, bssid, freq))
        }
        return out
    }

    /** Best-effort union of both scan formats. */
    fun parseScan(raw: String): List<Ap> {
        val iw = parseIwScan(raw)
        return if (iw.isNotEmpty()) iw else parseCmdScan(raw)
    }

    private val STATUS_SSID = Regex("""(?m)^\s*ssid\s*[=:]\s*(.+)$""")
    private val STATUS_FREQ = Regex("""(?m)^\s*freq(?:uency)?\s*[=:]\s*(\d+)""")

    /** Reads the SSID from `wpa_cli status` or `cmd wifi status` output. */
    fun currentSsid(raw: String): String? =
        STATUS_SSID.find(raw)?.groupValues?.get(1)?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }

    /** Reads the current frequency (MHz) from `wpa_cli status` or `cmd wifi status` output. */
    fun currentFrequencyMhz(raw: String): Int? =
        STATUS_FREQ.find(raw)?.groupValues?.get(1)?.toIntOrNull()

    /** Parses `wpa_cli list_networks` into SSID -> network id. */
    fun parseWpaListNetworks(raw: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (line in raw.lineSequence()) {
            val cols = line.split('\t')
            if (cols.size < 2) continue
            val id = cols[0].trim().toIntOrNull() ?: continue
            val ssid = cols[1].trim()
            if (ssid.isEmpty()) continue
            out.putIfAbsent(ssid, id)
        }
        return out
    }
}
