package com.hpplay

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

/**
 * Interface theme preference, matching design "05 · hybrid adaptive".
 *
 * [AUTO] follows the system day/night mode; [LIGHT] and [DARK] pin the palette regardless of the
 * system setting. The choice is persisted and also drives the AirPlay night-mode hint.
 */
enum class UiTheme {
    AUTO,
    LIGHT,
    DARK;

    companion object {
        fun fromName(value: String?): UiTheme =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/** Resolves the effective day/night state from the user's choice and the system uiMode. */
internal fun resolveDarkMode(theme: UiTheme, systemUiMode: Int): Boolean = when (theme) {
    UiTheme.AUTO -> isDarkMode(systemUiMode)
    UiTheme.LIGHT -> false
    UiTheme.DARK -> true
}

/**
 * The "05 · hybrid adaptive" palette: warm cream / near-black pages, glass cards, and an orange
 * accent, with a light and a dark variant. Values are plain ARGB ints so the whole UI can be
 * recoloured without touching XML resources.
 */
class SettingsPalette private constructor(val dark: Boolean) {
    // Page and surfaces (glass is approximated with translucent fills; real blur is API 31+).
    val scrim: Int = if (dark) 0xB3000000.toInt() else 0x59000000
    val page: Int = if (dark) 0xFF0C0D11.toInt() else 0xFFEDE7DF.toInt()
    val pageTop: Int = if (dark) 0xFF12141A.toInt() else 0xFFF3EDE5.toInt()
    val pageBottom: Int = if (dark) 0xFF08090C.toInt() else 0xFFE6DFD5.toInt()
    val glowWarm: Int = if (dark) 0x33FF7A1A else 0x3DFF8A1E
    val glowCool: Int = if (dark) 0x2238E1D0 else 0x1A78B4FF

    val surface: Int = if (dark) 0x1CFFFFFF else 0x99FFFFFF.toInt()
    val surfaceElevated: Int = if (dark) 0x24FFFFFF else 0xB8FFFFFF.toInt()
    val navSurface: Int = if (dark) 0x14FFFFFF else 0x8CFFFFFF.toInt()
    val divider: Int = if (dark) 0x2BFFFFFF else 0x2E785A3C
    val navSelected: Int = if (dark) 0x2EFF7A1A else 0x21FF6A00
    val cardBorder: Int = if (dark) 0x2BFFFFFF else 0x99FFFFFF.toInt()

    // Text.
    val title: Int = if (dark) 0xFFFFFFFF.toInt() else 0xFF1C140C.toInt()
    val text: Int = if (dark) 0xFFF2F5F8.toInt() else 0xFF241C14.toInt()
    val secondary: Int = if (dark) 0xFFBCC5CF.toInt() else 0xFF6B5B49.toInt()
    val tertiary: Int = if (dark) 0xFF9AA4B0.toInt() else 0xFF8A7A66.toInt()

    // Accent.
    val accent: Int = if (dark) 0xFFFF7A1A.toInt() else 0xFFFF6A00.toInt()
    val accentSoft: Int = if (dark) 0x2EFF7A1A else 0x21FF6A00
    val accentTrack: Int = if (dark) 0xFFFFB35C.toInt() else 0xFFFF9440.toInt()
    val onAccent: Int = if (dark) 0xFF1B1206.toInt() else 0xFFFFFFFF.toInt()
    val trackOff: Int = if (dark) 0x29FFFFFF else 0x38785A3C

    val danger: Int = if (dark) 0xFFFF6B6B.toInt() else 0xFFBE2D2D.toInt()
    val ok: Int = if (dark) 0xFF38E1D0.toInt() else 0xFF189A57.toInt()

    /** Page background: a soft vertical wash plus a warm/cool diagonal glow, evoking the glass look. */
    fun pageBackground(): Drawable = LayerDrawable(
        arrayOf(
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(pageTop, page, pageBottom),
            ),
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(glowWarm, 0x00FFFFFF, glowCool),
            ),
        ),
    )

    /** Rounded translucent card, used for grouping rows. */
    fun card(cornerRadiusPx: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        this.cornerRadius = cornerRadiusPx
        setColor(surface)
        setStroke(1, cardBorder)
    }

    companion object {
        fun of(dark: Boolean): SettingsPalette = SettingsPalette(dark)
    }
}
