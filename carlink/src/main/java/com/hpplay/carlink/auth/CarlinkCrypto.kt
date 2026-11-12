package com.hpplay.carlink.auth

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CarLink AUTH 会话密钥派生与 AES-128-GCM 分帧（逐字节对齐蒲公英 I0/a + R0/a）。
 *
 * 关键点（此前实现有误，已按 pgy smali 修正）：
 *  1. 会话密钥 = HKDF-SHA256(ikm=ECDH共享密钥, salt=SHA256(phoneRandom‖serverRandom), info="session_key", 16B)。
 *     - salt 必须先整体 SHA-256（pgy I0/a.a() 对 concat 后的随机数做了 MessageDigest.digest）。
 *     - session_key 与 uibc_session_key 是**同一把**密钥（pgy 把同一个 SecretKey 放进两个 key）。
 *  2. AES-GCM 载荷是**分帧**的：`[4B BE ivLen][iv][4B BE ctLen][ct‖tag]`（pgy R0/a.c() 加密、
 *     R0/a.b() 解密，支持多段拼接）。CONTROL / MEDIA / SENSOR / AuthConfirm 全部用此格式。
 */
object CarlinkCrypto {
    private const val EC_CURVE = "secp256r1" // == P-256
    private const val AES_KEY_BYTES = 16
    private const val GCM_TAG_BITS = 128
    private const val SESSION_INFO = "session_key"

    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE), SecureRandom())
        return kpg.generateKeyPair()
    }

    /** ECDH 共享密钥（X 坐标）。 */
    fun ecdh(privateKey: java.security.PrivateKey, peerPublicKey: java.security.PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peerPublicKey, true)
        return ka.generateSecret()
    }

    /**
     * 派生 16B 会话密钥（逐字节对齐 pgy I0/a.a()）：
     *   salt = SHA-256(phoneRandom ‖ serverRandom)
     *   sessionKey = HKDF-SHA256(sharedSecret, salt, info="session_key", 16)
     */
    fun deriveSessionKey(sharedSecret: ByteArray, phoneRandom: ByteArray, serverRandom: ByteArray): ByteArray {
        val salt = sha256(phoneRandom + serverRandom)
        return hkdf(sharedSecret, salt, SESSION_INFO.toByteArray(Charsets.UTF_8), AES_KEY_BYTES)
    }

    /**
     * HKDF-SHA256（RFC 5869）。
     * @param salt 为空时用全零（长度 = hashLen）。
     * @param info 上下文标签。
     */
    fun hkdf(
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
        length: Int = AES_KEY_BYTES,
    ): ByteArray {
        val hashLen = 32
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(hashLen) else salt
        // extract
        val prk = hmacSha256(actualSalt, ikm)
        // expand
        val n = (length + hashLen - 1) / hashLen
        require(n <= 255) { "hkdf length too large" }
        val okm = ByteArray(n * hashLen)
        var t = ByteArray(0)
        var pos = 0
        for (i in 1..n) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            System.arraycopy(t, 0, okm, pos, hashLen)
            pos += hashLen
        }
        return okm.copyOf(length)
    }

    /** HMAC-SHA256(key, data)，用于 authPkHmac。 */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * AES-128-GCM 加密，输出 pgy 分帧格式：`[4B BE ivLen][iv][4B BE ctLen][ct‖tag]`。
     * ctLen = 密文 + 16 字节 tag 的长度（等价 Cipher.getOutputSize(ptLen)）。
     */
    fun aesGcmEncrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), SecureRandom())
        val iv = cipher.iv // 12B 随机 IV
        aad?.let { cipher.updateAAD(it) }
        val ct = cipher.doFinal(plaintext) // ptLen + 16(tag)
        val out = java.io.ByteArrayOutputStream(4 + iv.size + 4 + ct.size)
        writeIntBE(out, iv.size)
        out.write(iv)
        writeIntBE(out, ct.size)
        out.write(ct)
        return out.toByteArray()
    }

    /**
     * AES-128-GCM 解密 pgy 分帧格式（支持多段拼接）：
     * 循环解析 `[4B ivLen][iv][4B ctLen][ct‖tag]`，各段 GCM 解密后拼接返回。
     */
    fun aesGcmDecrypt(key: ByteArray, framed: ByteArray, aad: ByteArray? = null): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var off = 0
        while (off + 4 <= framed.size) {
            val ivLen = readIntBE(framed, off); off += 4
            require(ivLen in 0..framed.size - off) { "bad ivLen=$ivLen" }
            val iv = framed.copyOfRange(off, off + ivLen); off += ivLen
            require(off + 4 <= framed.size) { "missing ctLen" }
            val ctLen = readIntBE(framed, off); off += 4
            require(ctLen in 0..framed.size - off) { "bad ctLen=$ctLen" }
            val ct = framed.copyOfRange(off, off + ctLen); off += ctLen
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            aad?.let { cipher.updateAAD(it) }
            out.write(cipher.doFinal(ct))
        }
        require(off == framed.size) { "trailing bytes after decrypt framing" }
        return out.toByteArray()
    }

    /** 未压缩点编码（0x04 || X || Y），用于 authPk/agreementPk 传输。 */
    fun encodePublicKey(pub: java.security.PublicKey): ByteArray {
        val w = pub as java.security.interfaces.ECPublicKey
        val x = toFixed(w.w.affineX.toByteArray(), 32)
        val y = toFixed(w.w.affineY.toByteArray(), 32)
        return byteArrayOf(0x04) + x + y
    }

    /** 解析未压缩点编码为 P-256 公钥。 */
    fun decodePublicKey(bytes: ByteArray): java.security.PublicKey {
        require(bytes.size == 65 && bytes[0] == 0x04.toByte()) { "not an uncompressed P-256 point" }
        val x = java.math.BigInteger(1, bytes.copyOfRange(1, 33))
        val y = java.math.BigInteger(1, bytes.copyOfRange(33, 65))
        val params = java.security.AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec(EC_CURVE))
        }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val pubSpec = java.security.spec.ECPublicKeySpec(
            java.security.spec.ECPoint(x, y), params,
        )
        return java.security.KeyFactory.getInstance("EC").generatePublic(pubSpec)
    }

    private fun writeIntBE(out: java.io.OutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun readIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun toFixed(v: ByteArray, len: Int): ByteArray {
        // BigInteger.toByteArray may carry a leading sign byte or be shorter than len.
        val out = ByteArray(len)
        if (v.size == len) {
            System.arraycopy(v, 0, out, 0, len)
        } else if (v.size == len + 1 && v[0] == 0.toByte()) {
            System.arraycopy(v, 1, out, 0, len)
        } else if (v.size < len) {
            System.arraycopy(v, 0, out, len - v.size, v.size)
        } else {
            System.arraycopy(v, v.size - len, out, 0, len)
        }
        return out
    }
}
