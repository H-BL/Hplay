package com.hpplay.carlife.protocol

import com.hpplay.carlife.proto.CarlifeMessages
import com.hpplay.carlife.proto.ProtoIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CarLife 硬键（方向盘）与导航 TTS 通道的协议常量校验。
 *
 * 这些数值取自百度 CarLife SDK 的 ServiceTypes / CarlifeCarHardKeyCode，改动会让手机端
 * 静默忽略消息，所以固定住。
 */
class CarLifeHardKeyAndTtsTest {

    @Test
    fun `hard key message id and channel match baidu sdk`() {
        // ServiceTypes.MSG_TOUCH_CAR_HARD_KEY_CODE = 425992 (0x68008)
        assertEquals(425992, CarLife.MSG_TOUCH_CAR_HARD_KEY_CODE)
        // 走 TOUCH 通道（短命令头 8 字节）
        assertEquals(6, CarLife.CHANNEL_TOUCH)
        assertEquals(8, CarLifeFrameCodec.commandSize(CarLife.CHANNEL_TOUCH))
    }

    @Test
    fun `seek key codes match baidu sdk`() {
        // 百度 CarLife SDK ServiceTypes：KEYCODE_SEEK_SUB(上一首)=15、KEYCODE_SEEK_ADD(下一首)=16
        // 外挂引擎规则表同为 88 -> 15、87 -> 16
        assertEquals(15, CarLife.HardKeyCode.KEYCODE_SEEK_SUB)
        assertEquals(16, CarLife.HardKeyCode.KEYCODE_SEEK_ADD)
    }

    @Test
    fun `hard key payload is protobuf field 1 varint`() {
        val payload = CarlifeMessages.carHardKeyCode(CarLife.HardKeyCode.KEYCODE_SEEK_ADD)
        // field 1, wire type 0 -> tag byte 0x08, then varint 16
        assertEquals(2, payload.size)
        assertEquals(0x08, payload[0].toInt() and 0xff)
        assertEquals(16, payload[1].toInt() and 0xff)
    }

    @Test
    fun `hard key payload decodes back`() {
        val payload = CarlifeMessages.carHardKeyCode(CarLife.HardKeyCode.KEYCODE_SEEK_SUB)
        val reader = ProtoIO.Reader(payload)
        val (field, _) = reader.nextField()
        assertEquals(1, field)
        assertEquals(15, reader.varint().toInt())
    }

    @Test
    fun `tts channel uses its own port and long command header`() {
        assertEquals(4, CarLife.CHANNEL_AUDIO_TTS)
        assertEquals(9241, CarLife.CHANNEL_PORTS[CarLife.CHANNEL_AUDIO_TTS])
        // TTS 消息号与百度 SDK 一致
        assertEquals(262145, CarLife.MSG_NAV_TTS_INIT)
        assertEquals(262146, CarLife.MSG_NAV_TTS_END)
        assertEquals(262147, CarLife.MSG_NAV_TTS_DATA)
    }

    @Test
    fun `vr channel constants match sdk`() {
        assertEquals(5, CarLife.CHANNEL_AUDIO_VR)
        assertEquals(9242, CarLife.CHANNEL_PORTS[CarLife.CHANNEL_AUDIO_VR])
        assertEquals(327682, CarLife.MSG_VR_AUDIO_INIT)
        assertEquals(327683, CarLife.MSG_VR_AUDIO_DATA)
    }

    @Test
    fun `audio format info decodes sample rate channels and bits`() {
        val payload = ProtoIO.Writer().apply {
            int32(1, 48000)
            int32(2, 2)
            int32(3, 16)
        }.toByteArray()
        val info = CarlifeMessages.AudioFormatInfo.decode(payload)
        assertEquals(48000, info.sampleRate)
        assertEquals(2, info.channelCount)
        assertEquals(16, info.bitDepth)
    }

    @Test
    fun `audio format info falls back to defaults on empty payload`() {
        val info = CarlifeMessages.AudioFormatInfo.decode(ByteArray(0))
        assertEquals(44100, info.sampleRate)
        assertEquals(2, info.channelCount)
        assertEquals(16, info.bitDepth)
    }

    @Test
    fun `tts and media channels are distinct`() {
        assertFalse(CarLife.CHANNEL_AUDIO_TTS == CarLife.CHANNEL_AUDIO)
        assertTrue(CarLife.CHANNEL_PORTS[CarLife.CHANNEL_AUDIO_TTS] != CarLife.CHANNEL_PORTS[CarLife.CHANNEL_AUDIO])
    }
}
