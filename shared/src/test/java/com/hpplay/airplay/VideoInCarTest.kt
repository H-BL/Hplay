package com.hpplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VideoInCarTest {
    @Test
    fun videoPlaybackInfoOnlyWhenVideoInCarIsOn() {
        val base = AirPlayConfig(
            deviceName = "t", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:02",
            sourceVersion = "950.7.1", main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        )
        assertNull(AirPlayInfoPlist.build(base)["videoPlaybackInfo"])
        val info = AirPlayInfoPlist.build(base.copy(videoInCar = true))
        val playback = info["videoPlaybackInfo"] as Map<*, *>
        assertNotNull(playback)
        assertEquals(VideoInCar.allowed, playback["videoPlaybackAllowed"])
        assertTrue(playback["featuresEx"] is String)
        assertTrue((playback["playbackCapabilities"] as Map<*, *>).isNotEmpty())
    }

    @Test
    fun featuresExSetsAdditionalBitsOverLegacy() {
        val bytes = Base64.getDecoder().decode(VideoInCar.featuresEx(0L))
        // Bit 0 and bit 64 set -> little-endian first byte 0x01 and a 0x01 eight bytes later.
        assertEquals(0x01, bytes[0].toInt() and 0xff)
        assertTrue(bytes.size >= 9)
        assertEquals(0x01, bytes[8].toInt() and 0xff)
    }

    @Test
    fun isPlayableUrlFollowsSchemes() {
        assertTrue(VideoInCar.isPlayableUrl("https://x/y.m3u8"))
        assertTrue(VideoInCar.isPlayableUrl("http://x/y.mp4"))
        assertFalse(VideoInCar.isPlayableUrl("file:///sdcard/x.mp4"))
        assertFalse(VideoInCar.isPlayableUrl("content://x/y"))
        // An app's own scheme is only accepted when the iPhone can serve it.
        assertFalse(VideoInCar.isPlayableUrl("myapp://clip/1"))
        assertTrue(VideoInCar.isPlayableUrl("myapp://clip/1", iphoneLoadsAppSchemes = true))
        assertFalse(VideoInCar.isPlayableUrl("skd://key", iphoneLoadsAppSchemes = true))
    }

    @Test
    fun parseItemReadsUrlAndStart() {
        val message = mapOf(
            "item" to mapOf(
                "Content-Location" to "https://example.com/a.mp4",
                "Start-Position-Seconds" to 3.5,
                "uuid" to "u1",
            ),
        )
        val item = VideoInCar.parseItem(message)!!
        assertEquals("https://example.com/a.mp4", item.url)
        assertEquals(3500, item.startMillis)
        assertEquals("u1", item.uuid)
    }

    @Test
    fun availabilityQueuesUntilFeatureAndEventAreReadyThenDedupes() {
        val sent = mutableListOf<Boolean>()
        val availability = VideoPlaybackAvailability { sent.add(it); true }

        assertEquals(VideoPlaybackDelivery.QUEUED, availability.setDesired(true))
        assertEquals(emptyList<Boolean>(), sent)

        assertEquals(VideoPlaybackDelivery.QUEUED, availability.setFeatureEnabled(true))
        assertEquals(emptyList<Boolean>(), sent)

        assertEquals(VideoPlaybackDelivery.SENT, availability.setEventReady(true))
        assertEquals(listOf(true), sent)

        // Same value again is not resent.
        assertEquals(VideoPlaybackDelivery.UNCHANGED, availability.setDesired(true))
        assertEquals(listOf(true), sent)

        // A change is sent at once while ready.
        assertEquals(VideoPlaybackDelivery.SENT, availability.setDesired(false))
        assertEquals(listOf(true, false), sent)
    }
}
