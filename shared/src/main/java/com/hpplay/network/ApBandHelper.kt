package com.hpplay.network

import android.util.Log

/**
 * Parses the output of the on-device `HotspotAp` helper.
 *
 * The helper is an `app_process` program that drives `IWifiManager.getWifiApConfiguration` (48) and
 * `setWifiApConfiguration` (49) — the only way to change the SoftAp band on this ROM, where the
 * on-disk hostapd config is a HAL product. It prints one line per fact, e.g.
 *
 * ```
 * HOTSPOTAP:BEFORE band=1 channel=0 ssid=F510C-000000
 * HOTSPOTAP:SET ok=true
 * HOTSPOTAP:AFTER band=1 channel=0 ssid=F510C-000000
 * ```
 *
 * The parsing is kept free of Android types so the exact line formats are unit-tested; a silent
 * change to them would otherwise turn "5 GHz is configured" into a false success.
 */
internal object HotspotBand {
    /** Band constants the framework uses in `WifiConfiguration.apBand`. */
    const val TWO_GHZ = 0
    const val FIVE_GHZ = 1

    private val GET = Regex("""HOTSPOTAP:GET\s+band=(-?\d+)""")
    private val GET_CHANNEL = Regex("""HOTSPOTAP:GET\s+band=-?\d+\s+channel=(-?\d+)""")
    private val GET_SSID = Regex("""HOTSPOTAP:GET\s+band=-?\d+\s+channel=-?\d+\s+ssid=(.*)""")
    private val SET_SSID = Regex("""HOTSPOTAP:SETSSID\s+ok=(\w+)""")
    private val AFTER_SSID = Regex("""HOTSPOTAP:AFTER\s+band=-?\d+\s+channel=-?\d+\s+ssid=(.*)""")
    private val AFTER = Regex("""HOTSPOTAP:AFTER\s+band=(-?\d+)""")
    private val AFTER_BAND_CHANNEL = Regex("""HOTSPOTAP:AFTER\s+band=(-?\d+)\s+channel=(-?\d+)""")
    private val SET = Regex("""HOTSPOTAP:SET\s+ok=(\w+)""")
    private val ERROR = Regex("""HOTSPOTAP:ERR\s*(.*)""")

    /** The band the framework reports for the SoftAp config, or null when the helper did not answer. */
    fun parseRead(raw: String?): Int? = GET.find(raw.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The channel the framework currently reports for the SoftAp config, or null when unknown.
     *
     * `0` is the framework's "auto", not a real channel, so it is returned as null: the whole point
     * of reading it back is to keep "no channel" distinguishable from "channel 0".
     */
    fun parseReadChannel(raw: String?): Int? =
        GET_CHANNEL.find(raw.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    /** The band the framework reports *after* a write, or null when the helper did not answer. */
    fun parseAfter(raw: String?): Int? = AFTER.find(raw.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    /** The channel the framework reports *after* a write, or null when the helper did not answer. */
    fun parseAfterChannel(raw: String?): Int? =
        AFTER_BAND_CHANNEL.find(raw.orEmpty())?.groupValues?.get(2)?.toIntOrNull()

    /** True when the helper reported the write succeeded; null when it reported no result at all. */
    fun parseSetOk(raw: String?): Boolean? {
        val value = SET.find(raw.orEmpty())?.groupValues?.get(1) ?: return null
        return value.equals("true", ignoreCase = true)
    }

    /** The SSID the framework currently reports, or null when the helper did not answer. */
    fun parseGetSsid(raw: String?): String? =
        GET_SSID.find(raw.orEmpty())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** True when the helper reported the SSID write succeeded; null when it reported no result. */
    fun parseSetSsidOk(raw: String?): Boolean? {
        val value = SET_SSID.find(raw.orEmpty())?.groupValues?.get(1) ?: return null
        return value.equals("true", ignoreCase = true)
    }

    /** The SSID the framework reports after a write, or null when the helper did not answer. */
    fun parseAfterSsid(raw: String?): String? =
        AFTER_SSID.find(raw.orEmpty())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** The helper's own error text, when it failed; null on success. */
    fun parseError(raw: String?): String? =
        ERROR.find(raw.orEmpty())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** True when [band] is the 5 GHz band. */
    fun isFiveGhz(band: Int?): Boolean = band == FIVE_GHZ

    /** Human-readable band name, or null when the band is unknown. */
    fun label(band: Int?): String? = when (band) {
        TWO_GHZ -> "2.4 GHz"
        FIVE_GHZ -> "5 GHz"
        else -> null
    }
}

/**
 * Reads and writes the framework SoftAp band on the head unit through the `HotspotAp` helper.
 *
 * The helper dex is staged to a world-readable path once per process and then driven with
 * `runconuid -u 1000 … app_process`, because `setWifiApConfiguration` only accepts the system uid.
 * The class never throws for the ordinary "the device said no" cases: a refused or unparsable reply
 * comes back as null / a [Result], so the caller can report an actionable message instead of a crash.
 */
internal class ApBandHelper(
    private val callerPackage: String,
    private val assetBase64: () -> String?,
    private val shell: (String) -> String = { RootShell.run(it) },
) {
    private companion object {
        const val TAG = "hplay-usb"
    }

    /** Outcome of a band write. */
    sealed interface Result {
        /** The framework accepted the write and now reports [band]. */
        data class Applied(val band: Int) : Result

        /** The write was refused or did not stick; [detail] says what the device answered. */
        data class Refused(val detail: String) : Result

        /** The helper could not be staged or run at all. */
        data class Unavailable(val detail: String) : Result
    }

    @Volatile
    private var deployed = false

    /** Outcome of an SSID write. */
    sealed interface SsidResult {
        /** The framework accepted the write and now reports [ssid]. */
        data class Applied(val ssid: String) : SsidResult

        /** The write was refused or did not stick; [detail] says what the device answered. */
        data class Refused(val detail: String) : SsidResult

        /** The helper could not be staged or run at all. */
        data class Unavailable(val detail: String) : SsidResult
    }

    /** The band the framework currently reports, or null when it could not be read. */
    fun readBand(): Int? {
        val raw = run(FiveGhzHotspotCommands.readApConfig(callerPackage)) ?: return null
        return HotspotBand.parseRead(raw)
    }

    /**
     * The channel the framework currently reports, or null when it is unknown or "auto".
     *
     * This is the read-back half of the channel lock: a write the framework accepts but does not
     * apply (the classic "asked for 36, kept 149") is only visible by asking it again afterwards.
     */
    fun readChannel(): Int? {
        val raw = run(FiveGhzHotspotCommands.readApConfig(callerPackage)) ?: return null
        return HotspotBand.parseReadChannel(raw)
    }

    /** The SSID the framework currently reports, or null when it could not be read. */
    fun readSsid(): String? = run(FiveGhzHotspotCommands.readApConfig(callerPackage))
        ?.let { HotspotBand.parseGetSsid(it) }

    /** Writes [band] and confirms the framework read it back. */
    fun writeBand(band: Int): Result {
        val raw = run(FiveGhzHotspotCommands.writeApConfig(band, callerPackage))
            ?: return Result.Unavailable("the hotspot helper did not run")
        val error = HotspotBand.parseError(raw)
        if (error != null) {
            // The framework's own message is the most actionable thing we have (e.g. it names the
            // uid that was refused), so it is surfaced verbatim.
            return Result.Refused(error)
        }
        val ok = HotspotBand.parseSetOk(raw)
        if (ok != true) return Result.Refused("the hotspot configuration was not accepted")
        val after = HotspotBand.parseAfter(raw)
        if (after != band) {
            return Result.Refused(
                "the hotspot band did not stick (asked for ${HotspotBand.label(band) ?: band}, " +
                    "device reports ${HotspotBand.label(after) ?: "unknown"})",
            )
        }
        return Result.Applied(after)
    }

    /**
     * Writes [band] and [channel] together and confirms the framework read both back.
     *
     * Used to lock the hotspot to a specific 5 GHz channel (e.g. 149). A device that accepts the
     * write but keeps a different channel is reported as [Result.Refused] rather than a fake success,
     * so the caller can fall back to auto instead of telling the phone a channel the radio is not on.
     */
    fun writeBandChannel(band: Int, channel: Int): Result {
        val raw = run(FiveGhzHotspotCommands.writeApBandChannel(band, channel, callerPackage))
            ?: return Result.Unavailable("the hotspot helper did not run")
        val error = HotspotBand.parseError(raw)
        if (error != null) return Result.Refused(error)
        val ok = HotspotBand.parseSetOk(raw)
        if (ok != true) return Result.Refused("the hotspot configuration was not accepted")
        val afterBand = HotspotBand.parseAfter(raw)
        if (afterBand != band) {
            return Result.Refused(
                "the hotspot band did not stick (asked for ${HotspotBand.label(band) ?: band}, " +
                    "device reports ${HotspotBand.label(afterBand) ?: "unknown"})",
            )
        }
        val afterChannel = HotspotBand.parseAfterChannel(raw)
        if (afterChannel != null && afterChannel != channel) {
            return Result.Refused(
                "the hotspot channel did not stick (asked for $channel, device reports $afterChannel)",
            )
        }
        return Result.Applied(afterBand)
    }

    /**
     * Writes [ssid] and confirms the framework read it back.
     *
     * The band and the SSID live in the same SoftAp configuration, so this reuses the same helper
     * and the same uid-1000 identity. A write that the framework silently drops (the classic
     * "non-ASCII SSID is advertised as \`ssid2=<hex>\` and the iPhone will not join") is reported as
     * [SsidResult.Refused] instead of a fake success.
     */
    fun writeSsid(ssid: String): SsidResult {
        val raw = run(FiveGhzHotspotCommands.writeApSsid(ssid, callerPackage))
            ?: return SsidResult.Unavailable("the hotspot helper did not run")
        val error = HotspotBand.parseError(raw)
        if (error != null) return SsidResult.Refused(error)
        val ok = HotspotBand.parseSetSsidOk(raw)
        if (ok != true) return SsidResult.Refused("the hotspot SSID was not accepted")
        val after = HotspotBand.parseAfterSsid(raw)
        if (after != null && after != ssid) {
            return SsidResult.Refused(
                "the hotspot SSID did not stick (asked for '$ssid', device reports '$after')",
            )
        }
        return SsidResult.Applied(after ?: ssid)
    }

    private fun run(command: String): String? {
        if (!ensureDeployed()) return null
        return try {
            shell(command)
        } catch (error: Throwable) {
            Log.w(TAG, "hotspot helper run failed: ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    private fun ensureDeployed(): Boolean {
        if (deployed) return true
        val base64 = assetBase64() ?: run {
            Log.w(TAG, "hotspot helper asset is missing")
            return false
        }
        val staged = try {
            shell(FiveGhzHotspotCommands.deployHelper(base64))
            shell(FiveGhzHotspotCommands.readApConfig(callerPackage))
        } catch (error: Throwable) {
            Log.w(TAG, "hotspot helper deploy failed: ${error.javaClass.simpleName}: ${error.message}")
            null
        }
        if (staged == null || HotspotBand.parseRead(staged) == null) {
            Log.w(TAG, "hotspot helper did not answer: ${staged?.take(200)}")
            return false
        }
        deployed = true
        return true
    }
}
