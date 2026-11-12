package com.hpplay

import android.content.Context
import com.hpplay.airplay.AirPlayInfoPlist
import com.hpplay.airplay.AirPlayViewArea

/**
 * Where CarPlay puts its dock (app shortcuts, clock and status). Automatic leaves it to the iPhone,
 * which usually picks the driver's side. A fixed choice declares one full-screen view area per edge,
 * so switching between the driver's side and the bottom moves the dock without reconnecting.
 */
enum class CarPlayDock(val edge: Int?) {
    AUTOMATIC(null),
    BOTTOM(AirPlayInfoPlist.DOCK_EDGE_BOTTOM),
    DRIVER_SIDE(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE);

    /**
     * The view areas to advertise for a [widthPixels] x [heightPixels] full-screen canvas, and the
     * index of the area to start on. Automatic returns (null, 0): one full-panel area with no dock
     * edge, exactly the pre-existing behaviour. A fixed choice returns both full-screen edges so the
     * dock can be moved live with updateViewArea.
     */
    fun viewAreas(widthPixels: Int, heightPixels: Int): Pair<List<AirPlayViewArea>?, Int> {
        if (edge == null) return null to 0
        val bottom = AirPlayViewArea(widthPixels, heightPixels, dockEdge = AirPlayInfoPlist.DOCK_EDGE_BOTTOM)
        val driver = AirPlayViewArea(widthPixels, heightPixels, dockEdge = AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE)
        val areas = listOf(bottom, driver)
        val initial = areas.indexOfFirst { it.dockEdge == edge }.coerceAtLeast(0)
        return areas to initial
    }

    companion object {
        fun load(context: Context): CarPlayDock =
            entries.firstOrNull { it.name.equals(AirPlayPersistence.loadCarPlayDock(context), ignoreCase = true) }
                ?: AUTOMATIC

        fun save(context: Context, dock: CarPlayDock) =
            AirPlayPersistence.saveCarPlayDock(context, dock.name.lowercase())
    }
}
