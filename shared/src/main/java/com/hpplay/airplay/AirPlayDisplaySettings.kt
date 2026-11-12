package com.hpplay.airplay

enum class AirPlayPhysicalSizeBasis {
    WIDTH,
    HEIGHT,
}

data class AirPlayPhysicalSizeMm(
    val widthMm: Int,
    val heightMm: Int,
)

/**
 * The three one-tap "CarPlay size" presets.
 *
 * A preset drives two things that both travel to the phone during the handshake, so it changes the
 * real CarPlay UI instead of scaling a picture locally:
 *
 *  - [widthPhysicalMm]: the panel length reported in `/info`. A *larger* panel makes CarPlay draw
 *    *smaller* icons/text; a smaller panel makes them larger. Values mirror the reference app's
 *    Large / Medium / Small (250 / 300 / 350 mm).
 *  - [resolutionTenths]: the pixel canvas advertised to the phone (same lever as the Resolution
 *    slider). A *lower* canvas is upscaled to fill the screen, so the icons and text grow; a higher
 *    canvas shrinks them and looks sharper. This is the lever that makes the change obvious.
 *
 * Both point the same way: LARGE = bigger icons (smaller reported panel + smaller canvas), SMALL =
 * smaller icons (larger panel + full-resolution canvas). Picking a preset reconnects so the phone
 * re-negotiates; nothing is scaled on the local surface, so the picture never overflows the screen.
 */
enum class CarPlaySizePreset(val widthPhysicalMm: Int, val resolutionTenths: Int) {
    LARGE(250, 8),
    MEDIUM(300, 9),
    SMALL(350, 10),
    ;

    companion object {
        val DEFAULT = MEDIUM

        /** Nearest preset for a physical length, so the UI can highlight the current choice. */
        fun nearest(widthPhysicalMm: Int): CarPlaySizePreset =
            entries.minByOrNull { kotlin.math.abs(it.widthPhysicalMm - widthPhysicalMm) } ?: DEFAULT
    }
}

/** User-adjustable display values with the same stepped ranges used by the settings UI. */
object AirPlayDisplaySettings {
    const val MIN_FPS = 30
    const val MAX_FPS = 60
    const val FPS_STEP = 5
    const val DEFAULT_FPS = MAX_FPS

    const val MIN_WIDTH_PHYSICAL_MM = 100
    const val MAX_WIDTH_PHYSICAL_MM = 400
    const val WIDTH_PHYSICAL_MM_STEP = 50

    /** Matches [CarPlaySizePreset.MEDIUM]; a `const` cannot read the enum's property. */
    const val DEFAULT_WIDTH_PHYSICAL_MM = 300
    val DEFAULT_PHYSICAL_SIZE_BASIS = AirPlayPhysicalSizeBasis.WIDTH
    const val MIN_REPORTED_PHYSICAL_MM = 1
    const val MAX_REPORTED_PHYSICAL_MM = 2_000

    /**
     * The custom "CarPlay size" percentage. It scales the whole reported physical size, which is the
     * lever CarPlay uses for its icon and text size, so 100% is untouched and the reference app's
     * 30–160% range is preserved.
     */
    const val MIN_SIZE_PERCENT = 30
    const val MAX_SIZE_PERCENT = 160
    const val SIZE_PERCENT_STEP = 5
    const val DEFAULT_SIZE_PERCENT = 100

    fun sanitizeFps(value: Int): Int =
        snapToStep(value, MIN_FPS, MAX_FPS, FPS_STEP)

    fun sanitizeSizePercent(value: Int): Int =
        snapToStep(value, MIN_SIZE_PERCENT, MAX_SIZE_PERCENT, SIZE_PERCENT_STEP)

    /**
     * Applies the custom size percentage to an already-resolved physical size. The result stays a
     * real panel measurement, so it composes with the reference/absolute length above.
     */
    fun scalePhysicalSizeMm(size: AirPlayPhysicalSizeMm, percent: Int): AirPlayPhysicalSizeMm {
        val value = sanitizeSizePercent(percent)
        if (value == DEFAULT_SIZE_PERCENT) return size
        return AirPlayPhysicalSizeMm(
            widthMm = sanitizeReportedPhysicalMm(
                ((size.widthMm.toLong() * value + 50L) / 100L).toInt(),
            ),
            heightMm = sanitizeReportedPhysicalMm(
                ((size.heightMm.toLong() * value + 50L) / 100L).toInt(),
            ),
        )
    }

    fun sanitizeWidthPhysicalMm(value: Int): Int =
        snapToStep(
            value,
            MIN_WIDTH_PHYSICAL_MM,
            MAX_WIDTH_PHYSICAL_MM,
            WIDTH_PHYSICAL_MM_STEP,
        )

    fun sanitizeReportedPhysicalMm(value: Int): Int =
        value.coerceIn(MIN_REPORTED_PHYSICAL_MM, MAX_REPORTED_PHYSICAL_MM)

    /**
     * Resolves the physical size reported to CarPlay.
     *
     * By default the configured length is a *reference* for the largest window ever observed, so a
     * shrunken window reports a proportionally smaller physical size. Pass [absoluteReference] to
     * report the configured length verbatim instead: the physical size then describes the panel and
     * no longer depends on the current window, which is what the settings UI labels "absolute".
     */
    fun resolvePhysicalSizeMm(
        currentWidthPixels: Int,
        currentHeightPixels: Int,
        maximumWidthPixels: Int,
        maximumHeightPixels: Int,
        referenceMillimeters: Int,
        basis: AirPlayPhysicalSizeBasis,
        absoluteReference: Boolean = false,
    ): AirPlayPhysicalSizeMm {
        require(currentWidthPixels > 0 && currentHeightPixels > 0) {
            "Current display dimensions must be positive"
        }
        require(maximumWidthPixels > 0 && maximumHeightPixels > 0) {
            "Maximum display dimensions must be positive"
        }
        // An absolute reference always anchors on the width; the height follows the aspect ratio.
        val basis = if (absoluteReference) AirPlayPhysicalSizeBasis.WIDTH else basis
        val referencePixels = if (absoluteReference) {
            1.0
        } else {
            when (basis) {
                AirPlayPhysicalSizeBasis.WIDTH ->
                    currentWidthPixels.toDouble() / maximumWidthPixels
                AirPlayPhysicalSizeBasis.HEIGHT ->
                    currentHeightPixels.toDouble() / maximumHeightPixels
            }
        }
        val referenceMm = referenceMillimeters.coerceAtLeast(1) * referencePixels
        val widthMm: Double
        val heightMm: Double
        when (basis) {
            AirPlayPhysicalSizeBasis.WIDTH -> {
                widthMm = referenceMm
                heightMm = widthMm * currentHeightPixels / currentWidthPixels
            }
            AirPlayPhysicalSizeBasis.HEIGHT -> {
                heightMm = referenceMm
                widthMm = heightMm * currentWidthPixels / currentHeightPixels
            }
        }
        return AirPlayPhysicalSizeMm(
            widthMm = sanitizeReportedPhysicalMm(Math.round(widthMm).toInt()),
            heightMm = sanitizeReportedPhysicalMm(Math.round(heightMm).toInt()),
        )
    }

    fun fpsProgress(value: Int): Int =
        (sanitizeFps(value) - MIN_FPS) / FPS_STEP

    fun sizePercentProgress(value: Int): Int =
        (sanitizeSizePercent(value) - MIN_SIZE_PERCENT) / SIZE_PERCENT_STEP

    fun widthPhysicalMmProgress(value: Int): Int =
        (sanitizeWidthPhysicalMm(value) - MIN_WIDTH_PHYSICAL_MM) / WIDTH_PHYSICAL_MM_STEP

    private fun snapToStep(value: Int, minimum: Int, maximum: Int, step: Int): Int {
        val clamped = value.coerceIn(minimum, maximum)
        return (((clamped - minimum) + step / 2) / step) * step + minimum
    }
}
