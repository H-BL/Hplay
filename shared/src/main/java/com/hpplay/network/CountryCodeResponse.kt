package com.hpplay.network

/**
 * Parses the Wi-Fi country code out of `service call wifi 28` output.
 *
 * The returned format differs per ROM, so three layers are tried in order: plain text, a dotted
 * `U.S` form, and a hex parcel whose country code is stored as little-endian UTF-16. Keeping all
 * three is deliberate — a ROM that only answers in one of them would otherwise never verify.
 */
internal object CountryCodeResponse {
    /** Country code that unlocks the 5 GHz channels on the F510C head unit. */
    const val EXPECTED = "US"

    /**
     * A bare country code, matched **case-sensitively**: country codes are upper-case, and matching
     * lower-case would treat the English pronoun "us" in a ROM error message as a set country code,
     * which would then make the hotspot fail silently.
     */
    private val DIRECT_UPPER = Regex("""\bUS\b""")
    private val DOTTED = Regex("""(?i)([A-Za-z])\.([A-Za-z])""")
    private val HEX_WORD = Regex("""(?i)\b[0-9a-f]{8}\b""")

    /** Returns the two-letter country code when it matches [EXPECTED], otherwise null. */
    fun parse(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null

        matchDirect(raw)?.let { return it }
        for (match in DOTTED.findAll(raw)) {
            val code = match.groupValues[1] + match.groupValues[2]
            if (code.equals(EXPECTED, ignoreCase = true)) return code.uppercase()
        }

        val decoded = StringBuilder()
        for (match in HEX_WORD.findAll(raw)) {
            val value = match.value.toLong(16)
            for (index in 0..1) {
                val character = ((value ushr (index * 16)) and 0xFFFFL).toInt().toChar()
                if (character.code in 32..126) decoded.append(character)
            }
        }
        return matchDirect(decoded.toString())
    }

    fun isExpected(raw: String?): Boolean = parse(raw) != null

    private fun matchDirect(text: String): String? {
        // The whole reply being the code is unambiguous, so case does not matter there.
        if (text.trim().equals(EXPECTED, ignoreCase = true)) return EXPECTED
        return if (DIRECT_UPPER.containsMatchIn(text)) EXPECTED else null
    }
}
