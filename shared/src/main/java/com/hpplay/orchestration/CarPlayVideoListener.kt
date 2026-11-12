package com.hpplay.orchestration

/**
 * iOS 26/27 video in car, played by the host in the car's own player (see `VideoInCar`). The host
 * reports whether video may play through `VideoInCar.allowed`; the VideoInCar switch keeps it true
 * for the whole session, so the iPhone always believes the car is in P.
 */
interface CarPlayVideoListener {
    /** Video became allowed or not; when not, the player must close. Any thread. */
    fun onVideoAllowedChanged(allowed: Boolean)

    /** A playback message from the iPhone, e.g. insertPlayQueueItem, setRate, seek, stop. Any thread. */
    fun onVideoMessage(streamId: Long, message: Map<String, Any?>)

    /** The iPhone asks the car to show its video player (requestUI "videoplayback:"). Any thread. */
    fun onVideoUiRequested()

    /** The CarPlay session ended; the player must close. Any thread. */
    fun onVideoSessionEnded()
}
