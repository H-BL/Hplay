package com.hpplay

/**
 * Nanoseconds in one millisecond.
 *
 * Every timeout in the transport / network / mfi / airplay layers is expressed as a
 * [System.nanoTime] deadline, so this conversion shows up in about twenty files; it lives
 * here once instead of being redeclared in each companion object.
 */
internal const val NANOS_PER_MILLISECOND = 1_000_000L
