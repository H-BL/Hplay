package com.hpplay.carlife.session

import com.hpplay.carlife.proto.CarlifeMessages
import com.hpplay.carlife.proto.ProtoIO
import com.hpplay.carlife.protocol.CarLife
import com.hpplay.carlife.protocol.CarLifeFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 TTS（导航播报）通道的分发与方向盘硬键的上行编码。
 *
 * 关键回归点：之前 `onFrame` 的 when 只有通道 1/2/3，TTS 帧被静默丢弃，
 * 于是手机压低音乐却没有声音。
 */
class CarLifeSessionTtsTest {

    private class RecordingCallback : CarLifeSession.Callback {
        val sent = mutableListOf<CarLifeFrame>()
        var ttsFormat: CarlifeMessages.AudioFormatInfo? = null
        val ttsData = mutableListOf<ByteArray>()
        var ttsFinished = 0

        val deviceInfo = CarlifeMessages.DeviceInfo(
            os = "android", brand = "hplay", model = "MTK8666",
            sdk = "28", sdkInt = 28, release = "9", carlifeVersion = "1.9.8",
        )

        override fun send(frame: CarLifeFrame) {
            sent.add(frame)
        }

        override fun huDeviceInfo(): CarlifeMessages.DeviceInfo = deviceInfo
        override fun onEstablished() = Unit
        override fun onTerminated(reason: String) = Unit
        override fun onVideoEncoderInfo(info: CarlifeMessages.VideoEncoderInfo) = Unit
        override fun onVideoData(payload: ByteArray) = Unit
        override fun onAudioFormat(sampleRate: Int, channels: Int, bits: Int) = Unit
        override fun onAudioData(payload: ByteArray) = Unit

        override fun onTtsFormat(info: CarlifeMessages.AudioFormatInfo) {
            ttsFormat = info
        }

        override fun onTtsData(payload: ByteArray) {
            ttsData.add(payload)
        }

        override fun onTtsFinished() {
            ttsFinished++
        }
    }

    private fun session(callback: RecordingCallback) =
        CarLifeSession(callback, "t", 1, 800, 480, 24)

    @Test
    fun `tts init reaches callback with format`() {
        val cb = RecordingCallback()
        val s = session(cb)
        val payload = ProtoIO.Writer().apply {
            int32(1, 24000)
            int32(2, 1)
            int32(3, 16)
        }.toByteArray()

        s.onFrame(CarLifeFrame(CarLife.CHANNEL_AUDIO_TTS, CarLife.MSG_NAV_TTS_INIT, payload))

        val info = cb.ttsFormat
        assertNotNull("TTS init 必须到达回调（旧实现会丢弃）", info)
        assertEquals(24000, info!!.sampleRate)
        assertEquals(1, info.channelCount)
        assertEquals(16, info.bitDepth)
    }

    @Test
    fun `tts pcm data reaches callback`() {
        val cb = RecordingCallback()
        val s = session(cb)
        val pcm = byteArrayOf(1, 2, 3, 4)

        s.onFrame(CarLifeFrame(CarLife.CHANNEL_AUDIO_TTS, CarLife.MSG_NAV_TTS_DATA, pcm))

        assertEquals(1, cb.ttsData.size)
        assertArrayEquals(pcm, cb.ttsData[0])
    }

    @Test
    fun `tts end releases navigation`() {
        val cb = RecordingCallback()
        val s = session(cb)

        s.onFrame(CarLifeFrame(CarLife.CHANNEL_AUDIO_TTS, CarLife.MSG_NAV_TTS_END, ByteArray(0)))

        assertEquals(1, cb.ttsFinished)
    }

    @Test
    fun `tts is independent from media channel`() {
        val cb = RecordingCallback()
        val s = session(cb)

        s.onFrame(
            CarLifeFrame(
                CarLife.CHANNEL_AUDIO_TTS,
                CarLife.MSG_NAV_TTS_DATA,
                byteArrayOf(9),
            ),
        )

        // 媒体通道的回调不应被 TTS 触发
        assertEquals(1, cb.ttsData.size)
    }

    @Test
    fun `sendHardKey emits touch channel hard key frame`() {
        val cb = RecordingCallback()
        val s = session(cb)

        s.sendHardKey(CarLife.HardKeyCode.KEYCODE_SEEK_ADD)

        val frame = cb.sent.lastOrNull()
        assertNotNull(frame)
        assertEquals(CarLife.CHANNEL_TOUCH, frame!!.channel)
        assertEquals(CarLife.MSG_TOUCH_CAR_HARD_KEY_CODE, frame.serviceType)

        // 载荷是 protobuf CarlifeCarHardKeyCode{keycode=16}（百度 SDK 的 KEYCODE_SEEK_ADD）
        val r = ProtoIO.Reader(frame.payload)
        val (field, _) = r.nextField()
        assertEquals(1, field)
        assertEquals(16, r.varint().toInt())
    }

    @Test
    fun `previous track maps to seek sub`() {
        val cb = RecordingCallback()
        val s = session(cb)

        s.sendHardKey(CarLife.HardKeyCode.KEYCODE_SEEK_SUB)

        val frame = cb.sent.last()
        val r = ProtoIO.Reader(frame.payload)
        r.nextField()
        assertEquals(15, r.varint().toInt())
        assertTrue(frame.channel == CarLife.CHANNEL_TOUCH)
    }
}
