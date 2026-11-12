package com.hpplay.carlink.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarlinkCryptoTest {

    /** RFC 5869 Test Case 1 (HKDF-SHA256)。 */
    @Test
    fun hkdf_matchesRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c,
        )
        val info = byteArrayOf(0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte())
        val expected = hexToBytes("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
        val okm = CarlinkCrypto.hkdf(ikm, salt, info, 42)
        assertArrayEquals(expected, okm)
    }

    @Test
    fun ecdh_isSymmetric() {
        val a = CarlinkCrypto.generateKeyPair()
        val b = CarlinkCrypto.generateKeyPair()
        val sa = CarlinkCrypto.ecdh(a.private, b.public)
        val sb = CarlinkCrypto.ecdh(b.private, a.public)
        assertArrayEquals(sa, sb)
        assertEquals(32, sa.size)
    }

    @Test
    fun publicKey_encodeDecodeRoundTrip() {
        val kp = CarlinkCrypto.generateKeyPair()
        val encoded = CarlinkCrypto.encodePublicKey(kp.public)
        assertEquals(65, encoded.size)
        assertEquals(0x04.toByte(), encoded[0])
        val decoded = CarlinkCrypto.decodePublicKey(encoded)
        assertArrayEquals(encoded, CarlinkCrypto.encodePublicKey(decoded))
    }

    @Test
    fun aesGcm_roundTrip() {
        val key = ByteArray(16) { it.toByte() }
        val pt = "hello carlink 你好".toByteArray()
        val ct = CarlinkCrypto.aesGcmEncrypt(key, pt)
        val back = CarlinkCrypto.aesGcmDecrypt(key, ct)
        assertArrayEquals(pt, back)
    }

    /** 加密输出必须是 pgy 分帧：[4B ivLen=12][iv][4B ctLen=ptLen+16][ct||tag]。 */
    @Test
    fun aesGcm_framingMatchesPgyLayout() {
        val key = ByteArray(16) { it.toByte() }
        val pt = ByteArray(20) { (it + 1).toByte() }
        val framed = CarlinkCrypto.aesGcmEncrypt(key, pt)
        // 4 + 12 + 4 + (20 + 16)
        assertEquals(4 + 12 + 4 + 36, framed.size)
        val ivLen = readIntBE(framed, 0)
        assertEquals(12, ivLen)
        val ctLen = readIntBE(framed, 4 + 12)
        assertEquals(20 + 16, ctLen) // plaintext + 16-byte tag
    }

    /** 多段拼接解密（pgy R0/a.b 支持把多个分帧拼在一起解）。 */
    @Test
    fun aesGcm_decryptMultiSegment() {
        val key = ByteArray(16) { (it * 3).toByte() }
        val p1 = "segment-one".toByteArray()
        val p2 = "segment-two-更长的第二段".toByteArray()
        val seg1 = CarlinkCrypto.aesGcmEncrypt(key, p1)
        val seg2 = CarlinkCrypto.aesGcmEncrypt(key, p2)
        val joined = seg1 + seg2
        val back = CarlinkCrypto.aesGcmDecrypt(key, joined)
        assertArrayEquals(p1 + p2, back)
    }

    /** 会话密钥派生：确定性 + 依赖 phoneRandom/serverRandom。 */
    @Test
    fun deriveSessionKey_isDeterministicAndSaltSensitive() {
        val shared = ByteArray(32) { it.toByte() }
        val phone = ByteArray(32) { 0x11 }
        val server = ByteArray(32) { 0x22 }
        val k1 = CarlinkCrypto.deriveSessionKey(shared, phone, server)
        val k2 = CarlinkCrypto.deriveSessionKey(shared, phone, server)
        assertEquals(16, k1.size)
        assertArrayEquals(k1, k2)
        // 换 serverRandom 必须变
        val k3 = CarlinkCrypto.deriveSessionKey(shared, phone, ByteArray(32) { 0x23 })
        assertNotEquals(k1.toList(), k3.toList())
        // 换 phoneRandom 必须变
        val k4 = CarlinkCrypto.deriveSessionKey(shared, ByteArray(32) { 0x12 }, server)
        assertNotEquals(k1.toList(), k4.toList())
    }

    /** 两端用各自私钥 + 对方公钥，派生出相同会话密钥（模拟真实握手）。 */
    @Test
    fun deriveSessionKey_bothSidesAgree() {
        val carKp = CarlinkCrypto.generateKeyPair()
        val phoneKp = CarlinkCrypto.generateKeyPair()
        val carShared = CarlinkCrypto.ecdh(carKp.private, phoneKp.public)
        val phoneShared = CarlinkCrypto.ecdh(phoneKp.private, carKp.public)
        val phoneRandom = ByteArray(32) { 0x5a }
        val serverRandom = ByteArray(32) { 0x3c }
        val carKey = CarlinkCrypto.deriveSessionKey(carShared, phoneRandom, serverRandom)
        val phoneKey = CarlinkCrypto.deriveSessionKey(phoneShared, phoneRandom, serverRandom)
        assertArrayEquals(carKey, phoneKey)
        assertTrue(carKey.size == 16)
    }

    private fun readIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
