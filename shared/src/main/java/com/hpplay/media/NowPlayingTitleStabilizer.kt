package com.hpplay.media

import com.hpplay.iap2.message.Iap2NowPlayingState

/**
 * Keeps the published track title from being rewritten by per-line lyric updates.
 *
 * iPhone apps that render lyrics through CarPlay (QQ音乐 with its "car lyrics" switch on, for one)
 * leave the track's artist, album, duration and queue position alone but push the current lyric
 * line through the Now Playing *title* attribute several times a minute. A head unit that looks
 * lyrics up by the title then re-searches on every line, so the desktop flickers and hammers the
 * lookup service.
 *
 * Holding the first title that arrives for a given track keeps the real song name in place. A
 * genuine track change is still recognised because the artist, album, duration or queue position
 * moves with it, which is what re-arms the next title.
 */
internal class NowPlayingTitleStabilizer {
    /** Everything a track change moves but a lyric line does not. */
    private data class TrackIdentity(
        val artist: String?,
        val album: String?,
        val durationMillis: Long?,
        val queueIndex: Long?,
        val queueCount: Long?,
    )

    private var identity: TrackIdentity? = null
    private var publishedTitle: String? = null

    /**
     * Returns [state] with its title replaced only when this frame announces the title of a track
     * that has not been titled yet. Later title-only updates for the same track are ignored, so a
     * lyric line never becomes the published song name.
     */
    fun apply(
        state: Iap2NowPlayingState,
        frameHasTitle: Boolean,
        frameTitle: String?,
    ): Iap2NowPlayingState {
        val next = TrackIdentity(
            artist = state.artist,
            album = state.album,
            durationMillis = state.durationMillis,
            queueIndex = state.queueIndex,
            queueCount = state.queueCount,
        )
        if (next != identity) {
            identity = next
            publishedTitle = null
        }
        if (frameHasTitle && publishedTitle == null) {
            publishedTitle = frameTitle
        }
        // The merged state may already hold a lyric line, so the stabilised title always wins.
        return publishedTitle?.let { state.copy(title = it) } ?: state
    }

    fun reset() {
        identity = null
        publishedTitle = null
    }
}
