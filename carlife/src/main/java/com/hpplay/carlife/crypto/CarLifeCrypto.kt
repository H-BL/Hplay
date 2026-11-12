package com.hpplay.carlife.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * CarLife 内容加密（依据引擎 protocol/encrypt/ 反编译）：
 *  - 车机生成 RSA-2048 密钥对，X509 公钥 Base64 经 CarlifeHuRsaPublicKeyResponse 下发；
 *  - 手机生成随机 16 字节 AES 密钥（UUID 前 16 字符），RSA/ECB/PKCS1Padding 加密后
 *    Base64 经 CarlifeMdAesKeyRequest 回传；
 *  - 会话内容用 AES/ECB/PKCS5Padding（Cipher.getInstance("AES") 的默认变换，无 IV），
 *    只加密载荷，命令头保持明文；
 *  - 解密失败的 serviceType 加入 decryptExcludes，该类型降级为明文继续跑。
 */
class CarLifeCrypto private constructor(private val keyPair: KeyPair) {

    private val decryptExcludes = HashSet<Int>()

    /** Base64（X509）公钥，下发给手机。 */
    val publicKeyBase64: String by lazy {
        Base64Codec.encode(keyPair.public.encoded)
    }

    private var aesKey: SecretKeySpec? = null

    val encryptionReady: Boolean get() = aesKey != null

    /** 手机回传的 RSA 加密 AES 密钥（Base64）→ 解出会话密钥并清空明文降级集合。 */
    fun acceptAesKey(encryptedBase64: String) {
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private)
        val key = cipher.doFinal(Base64Codec.decode(encryptedBase64))
        aesKey = SecretKeySpec(key, "AES")
        decryptExcludes.clear()
    }

    fun forgetAesKey() {
        aesKey = null
    }

    /** 加密载荷；返回密文（明文长度 → PKCS5 填充后长度）。加密未就绪时原样返回。 */
    fun encryptPayload(serviceType: Int, payload: ByteArray): ByteArray {
        val key = aesKey ?: return payload
        if (payload.isEmpty() || serviceType == PLAINTEXT_EXEMPT) return payload
        return try {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.doFinal(payload)
        } catch (t: Throwable) {
            payload
        }
    }

    /**
     * 解密载荷；失败时把该 serviceType 加入明文降级集合并返回原文
     * （对应引擎 EncryptionTool.decrypt 的容错行为）。
     */
    fun decryptPayload(serviceType: Int, payload: ByteArray): ByteArray {
        val key = aesKey ?: return payload
        if (payload.isEmpty() || serviceType == PLAINTEXT_EXEMPT || serviceType in decryptExcludes) {
            return payload
        }
        return try {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key)
            cipher.doFinal(payload)
        } catch (t: Throwable) {
            decryptExcludes.add(serviceType)
            payload
        }
    }

    companion object {
        private const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
        private const val AES_TRANSFORMATION = "AES"

        /** MSG_CMD_HU_PROTOCOL_VERSION：协议版本消息恒为明文（getSupportEncrypt 豁免）。 */
        const val PLAINTEXT_EXEMPT = 98305

        /** AES 密钥 = UUID 前 16 字符（AesEncryptor.withRandomKey）。 */
        fun randomAesKey(): ByteArray =
            UUID.randomUUID().toString().substring(0, 16).toByteArray(Charsets.UTF_8)

        fun create(): CarLifeCrypto {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom())
            return CarLifeCrypto(generator.generateKeyPair())
        }

        /** 手机端视角：用 X509 Base64 公钥加密 AES 密钥并 Base64（测试/模拟手机用）。 */
        fun encryptAesKeyForPeer(publicKeyBase64: String, aesKey: ByteArray): String {
            val publicKey: PublicKey = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(Base64Codec.decode(publicKeyBase64)))
            val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            return Base64Codec.encode(cipher.doFinal(aesKey))
        }
    }
}
