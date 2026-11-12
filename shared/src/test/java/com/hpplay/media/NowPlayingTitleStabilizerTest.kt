package com.hpplay.media

import com.hpplay.iap2.message.Iap2NowPlayingState
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingTitleStabilizerTest {
    private fun state(
        title: String?,
        artist: String? = "Artist",
        album: String? = "Album",
        durationMillis: Long? = 180_000,
        queueIndex: Long? = 2,
        queueCount: Long? = 10,
    ) = Iap2NowPlayingState(
        title = title,
        artist = artist,
        album = album,
        durationMillis = durationMillis,
        queueIndex = queueIndex,
        queueCount = queueCount,
    )

    @Test
    fun keepsTheFirstTitleAndIgnoresLaterLyricLines() {
        val stabilizer = NowPlayingTitleStabilizer()

        val announced = stabilizer.apply(state("夜曲"), frameHasTitle = true, frameTitle = "夜曲")
        assertEquals("夜曲", announced.title)

        // QQ音乐 scrolls the lyric line through the title without touching the track identity.
        val line1 = stabilizer.apply(state("一群嗜血的蚂蚁"), true, "一群嗜血的蚂蚁")
        val line2 = stabilizer.apply(state("被腐肉所吸引"), true, "被腐肉所吸引")
        assertEquals("夜曲", line1.title)
        assertEquals("夜曲", line2.title)
    }

    @Test
    fun aRealTrackChangeAcceptsTheNewTitle() {
        val stabilizer = NowPlayingTitleStabilizer()
        stabilizer.apply(state("夜曲"), true, "夜曲")

        // Next track moves the identity, so its title is accepted.
        val next = stabilizer.apply(
            state("晴天", durationMillis = 269_000, queueIndex = 3),
            frameHasTitle = true,
            frameTitle = "晴天",
        )
        assertEquals("晴天", next.title)

        // And its lyric lines are ignored again.
        assertEquals("晴天", stabilizer.apply(state("故事的小黄花", durationMillis = 269_000, queueIndex = 3), true, "故事的小黄花").title)
    }

    @Test
    fun aFrameWithoutATitleLeavesTheStabilisedTitleAlone() {
        val stabilizer = NowPlayingTitleStabilizer()
        stabilizer.apply(state("夜曲"), true, "夜曲")
        // A later frame that only carries playback status: the merged state still holds a lyric
        // line, but no title attribute arrived, so the published title stays put.
        val paused = stabilizer.apply(state("一群嗜血的蚂蚁"), frameHasTitle = false, frameTitle = null)
        assertEquals("夜曲", paused.title)
    }

    @Test
    fun resetForgetsTheCurrentTrack() {
        val stabilizer = NowPlayingTitleStabilizer()
        stabilizer.apply(state("夜曲"), true, "夜曲")
        stabilizer.reset()
        val reAnnounced = stabilizer.apply(state("夜曲"), true, "夜曲")
        assertEquals("夜曲", reAnnounced.title)
    }
}
