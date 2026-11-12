package com.hpplay.rail

import android.content.Context
import com.hpplay.AirPlayPersistence

/**
 * Which edge hosts the status band, persisted as a user choice.
 *
 * [OFF] keeps the full-panel projection. Any other value reserves a strip and shrinks the display
 * advertised to the phone, so nothing is drawn on top of the projected UI.
 */
enum class StatusRailEdge { OFF, TOP, BOTTOM, LEFT, RIGHT }

/** While projecting, keep the band at full height or collapse it into a slim strip. */
enum class StatusRailStrategy { AUTO, STAY }

/** How hplay's own day/night follow the head unit. */
enum class ThemeMode { FOLLOW, DAY, NIGHT }

/** Band colour presets; AUTO follows the resolved day/night mode. */
enum class BandStyle { AUTO, DARK, LIGHT, BLUE, GREEN }

/**
 * Band geometry plus the single, user-ordered component list.
 *
 * Status readouts and car controls share one list: the user decides what exists and in what order,
 * and the band simply renders that order left to right.
 */
object StatusRailSettings {

    private const val PREFS = "hplay_status_band"
    private const val KEY_STRATEGY = "strategy"
    private const val KEY_COMPONENTS = "components"
    private const val KEY_ORDER = "component_order"
    private const val KEY_SHOW_SEAT = "show_seat"
    private const val KEY_SHOW_SCENE = "show_scene"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_BAND_STYLE = "band_style"

    private const val BOTTOM_DP = 96
    private const val TOP_DP = 72
    private const val SIDE_DP = 116
    private const val SLIM_BOTTOM_DP = 44
    private const val SLIM_TOP_DP = 36
    private const val SLIM_SIDE_DP = 56

    /** Every available component. Readouts and controls are peers. */
    val COMPONENTS = listOf(
        "soc", "range", "gear", "speed", "doors", "sentry", "temp", "trip", "location", "media",
        "ac", "seat", "fog", "drive", "tailgate", "scene",
    )

    /** Components that act on the car instead of only showing a reading. */
    val CONTROLS = setOf("ac", "seat", "fog", "drive", "tailgate", "scene")

    val DEFAULT_COMPONENTS = listOf(
        "soc", "range", "gear", "speed", "doors", "sentry", "seat", "fog", "ac", "drive",
    )

    fun loadEdge(context: Context): StatusRailEdge {
        val stored = AirPlayPersistence.loadStatusRailEdge(context)
        return StatusRailEdge.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) }
            ?: StatusRailEdge.OFF
    }

    fun saveEdge(context: Context, edge: StatusRailEdge) {
        AirPlayPersistence.saveStatusRailEdge(context, edge.name.lowercase())
    }

    fun isHorizontal(edge: StatusRailEdge): Boolean =
        edge == StatusRailEdge.TOP || edge == StatusRailEdge.BOTTOM

    // ---------------------------------------------------------------- components

    fun loadComponents(context: Context): List<String> =
        loadList(context, KEY_COMPONENTS, COMPONENTS, DEFAULT_COMPONENTS)

    fun saveComponents(context: Context, ids: List<String>) =
        saveList(context, KEY_COMPONENTS, ids)

    fun loadOrder(context: Context): List<String> = loadOrder(context, KEY_ORDER, COMPONENTS)

    fun saveOrder(context: Context, order: List<String>) =
        saveOrder(context, KEY_ORDER, order)

    fun isControl(id: String): Boolean = id in CONTROLS

    // ---------------------------------------------------------------- strategy / sections

    fun loadStrategy(context: Context): StatusRailStrategy {
        val stored = prefs(context).getString(KEY_STRATEGY, null)
        return StatusRailStrategy.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) }
            ?: StatusRailStrategy.STAY
    }

    fun saveStrategy(context: Context, strategy: StatusRailStrategy) {
        prefs(context).edit().putString(KEY_STRATEGY, strategy.name.lowercase()).apply()
    }

    /** Some cars simply do not have seat heat/vent or scene modes; the panel hides those sections. */
    fun loadThemeMode(context: Context): ThemeMode {
        val stored = prefs(context).getString(KEY_THEME_MODE, null)
        return ThemeMode.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) } ?: ThemeMode.FOLLOW
    }

    fun saveThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.name.lowercase()).apply()
    }

    fun loadBandStyle(context: Context): BandStyle {
        val stored = prefs(context).getString(KEY_BAND_STYLE, null)
        return BandStyle.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) } ?: BandStyle.AUTO
    }

    fun saveBandStyle(context: Context, style: BandStyle) {
        prefs(context).edit().putString(KEY_BAND_STYLE, style.name.lowercase()).apply()
    }

    fun showSeatSection(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_SEAT, true)

    fun saveShowSeatSection(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_SEAT, show).apply()
    }

    fun showSceneSection(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_SCENE, true)

    fun saveShowSceneSection(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_SCENE, show).apply()
    }

    // ---------------------------------------------------------------- geometry

    fun thicknessPx(density: Float, panelWidth: Int, edge: StatusRailEdge, slim: Boolean = false): Int {
        if (edge == StatusRailEdge.OFF) return 0
        val base = when (edge) {
            StatusRailEdge.TOP -> if (slim) SLIM_TOP_DP else TOP_DP
            StatusRailEdge.BOTTOM -> if (slim) SLIM_BOTTOM_DP else BOTTOM_DP
            else -> if (slim) SLIM_SIDE_DP else SIDE_DP
        }
        val scale = (panelWidth / 1920f).coerceIn(0.75f, 1.25f)
        return (base * density * scale).toInt().coerceAtLeast(if (slim) 28 else 48)
    }

    fun spec(
        density: Float,
        panelWidth: Int,
        panelHeight: Int,
        edge: StatusRailEdge,
        slim: Boolean = false,
    ): StatusRailSpec {
        if (edge == StatusRailEdge.OFF) return StatusRailSpec.OFF
        val railEdge = when (edge) {
            StatusRailEdge.TOP -> RailEdge.TOP
            StatusRailEdge.LEFT -> RailEdge.LEFT
            StatusRailEdge.RIGHT -> RailEdge.RIGHT
            else -> RailEdge.BOTTOM
        }
        return StatusRailSpec(railEdge, thicknessPx(density, panelWidth, edge, slim))
    }

    // ---------------------------------------------------------------- storage

    private fun loadList(context: Context, key: String, known: List<String>, fallback: List<String>): List<String> {
        val stored = prefs(context).getString(key, null) ?: return fallback
        val parsed = stored.split(",").filter { it.isNotBlank() && it in known }
        return parsed.ifEmpty { fallback }
    }

    private fun saveList(context: Context, key: String, ids: List<String>) {
        prefs(context).edit().putString(key, ids.joinToString(",")).apply()
    }

    private fun loadOrder(context: Context, key: String, known: List<String>): List<String> {
        val stored = prefs(context).getString(key, null) ?: return known
        val parsed = stored.split(",").filter { it.isNotBlank() && it in known }
        return parsed + known.filterNot { it in parsed }
    }

    private fun saveOrder(context: Context, key: String, order: List<String>) {
        prefs(context).edit().putString(key, order.joinToString(",")).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
