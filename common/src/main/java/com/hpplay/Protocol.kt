package com.hpplay

/**
 * The phone-projection protocol the head unit is currently set to run.
 *
 * Only one protocol runs at a time ("single active"): the router sends the launcher to either the
 * CarPlay host or the CarLife host, never both. The choice is remembered so that after the first
 * run the app keeps using whatever the user picked, and can be changed again from Settings.
 */
enum class Protocol(val storageValue: String) {
    CARPLAY("carplay"),
    CARLIFE("carlife"),
    CARLINK("carlink");

    companion object {
        /** Anything unknown (or a missing value, e.g. a fresh install) falls back to CarPlay. */
        fun fromStorage(value: String?): Protocol =
            entries.firstOrNull { it.storageValue == value } ?: CARPLAY
    }
}
