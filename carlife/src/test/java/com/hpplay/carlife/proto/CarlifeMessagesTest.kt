package com.hpplay.carlife.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CarlifeMessagesTest {

    @Test
    fun `base64 codec roundtrip`() {
        val samples = listOf(
            ByteArray(0),
            byteArrayOf(0),
            byteArrayOf(1, 2, 3),
            ByteArray(57) { it.toByte() },
        )
        samples.forEach { data ->
            val encoded = com.hpplay.carlife.crypto.Base64Codec.encode(data)
            assertArrayEquals(data, com.hpplay.carlife.crypto.Base64Codec.decode(encoded))
        }
        // 标准向量
        assertEquals("", com.hpplay.carlife.crypto.Base64Codec.encode(ByteArray(0)))
        assertEquals("AQID", com.hpplay.carlife.crypto.Base64Codec.encode(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `device info roundtrip`() {
        val info = CarlifeMessages.DeviceInfo(
            os = "android", board = "mt8666", brand = "hplay", model = "car",
            sdkInt = 28, carlifeVersion = "1.9.8",
        )
        val back = CarlifeMessages.DeviceInfo.decode(info.encode())
        assertEquals(info.os, back.os)
        assertEquals(info.board, back.board)
        assertEquals(info.model, back.model)
        assertEquals(28, back.sdkInt)
        assertEquals("1.9.8", back.carlifeVersion)
    }

    @Test
    fun `feature config list roundtrip`() {
        val list = CarlifeMessages.FeatureConfigList(
            features = listOf(
                CarlifeMessages.FeatureConfig("connect_type", 1),
                CarlifeMessages.FeatureConfig("content_encryption", 1),
            ),
            huBtName = "HPLAY-BT",
            huBtMac = "AA:BB:CC:DD:EE:FF",
        )
        val back = CarlifeMessages.FeatureConfigList.decode(list.encode())
        assertEquals(2, back.features.size)
        assertEquals("connect_type", back.features[0].key)
        assertEquals(1, back.features[0].value)
        assertEquals("HPLAY-BT", back.huBtName)
        assertEquals("AA:BB:CC:DD:EE:FF", back.huBtMac)
    }

    @Test
    fun `video encoder info roundtrip`() {
        val info = CarlifeMessages.VideoEncoderInfo(1280, 720, 30)
        val back = CarlifeMessages.VideoEncoderInfo.decode(info.encode())
        assertEquals(info, back)
    }

    @Test
    fun `unknown fields are skipped`() {
        // 手工构造带未知字段（field 99 varint + field 98 bytes）的 ProtocolVersion
        val w = ProtoIO.Writer().apply {
            int32(99, 7)
            bytes(98, byteArrayOf(1, 2))
            int32(1, 4)
            int32(2, 1)
        }.toByteArray()
        val v = CarlifeMessages.ProtocolVersion.decode(w)
        assertEquals(4, v.majorVersion)
        assertEquals(1, v.minorVersion)
    }
}
