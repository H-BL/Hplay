package com.hpplay.carlink.auth

import android.util.Log
import com.hpplay.carlink.channel.CarlinkChannel
import com.hpplay.carlink.proto.CarlinkCodec
import com.hpplay.carlink.proto.CarlinkFrame
import com.hpplay.carlink.proto.ChannelCategory
import com.hpplay.carlink.proto.MsgType
import com.ucar.databus.proto.UCarProto
import java.net.Socket
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature

/**
 * CarLink AUTH 握手（车机侧），逐字节对齐蒲公英 I0/f + I0/a。
 *
 * 流程：
 *  1. 手机连入 AUTH 端口 57209，发 AuthRequest（含 authPk / agreementPk / random(Na) / id / model）。
 *  2. 车机：生成 serverRandom(Nb, 32B)；用我方 agreement 私钥 + 手机 agreementPk 做 ECDH 得 shared；
 *     派生会话密钥 session_key = HKDF-SHA256(shared, SHA256(Na‖Nb), "session_key", 16B)
 *     （uibc_session_key 与 session_key 是**同一把**）。
 *  3. 车机回 AuthResponse：version(0x701)、authPk(我方 EC 公钥)、
 *     authPkHmac = HMAC-SHA256(SHA256(Nb‖PIN), authPkBytes)、
 *     agreementPk、agreementPkSig = SHA256withECDSA(authPk ‖ Nb ‖ Na)、random=Nb、result=0。
 *  4. 手机回 AuthConfirm（用 session_key 以 pgy 分帧 GCM 加密的本地 IP），车机解密得手机 IP。
 *
 * PIN 用于 authPkHmac/agreementPkSig，向手机证明"车机知道当前热点密码"，防中间人；
 * AP 模式下 PIN = 热点密码（与 ServerInfo.psk 一致）。若暂无 PIN（如热点未就绪）则降级为空字段。
 */
class CarlinkAuth(
    private val socket: Socket,
    /** 提供当前热点密码（PIN）；返回 null 表示暂不可用。 */
    private val pinProvider: () -> String?,
    private val onHandshakeDone: (AuthResult) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    data class AuthResult(
        val phoneIp: String,
        val sessionKey: ByteArray,
        val uibcSessionKey: ByteArray,
        val phoneId: ByteArray,
        val phoneModel: String,
        val workModes: Int,
    )

    private val tag = "CarlinkAuth"
    private var channel: CarlinkChannel? = null
    private lateinit var authKeyPair: KeyPair
    private lateinit var agreementKeyPair: KeyPair
    @Volatile private var sessionKey: ByteArray? = null
    @Volatile private var uibcSessionKey: ByteArray? = null
    @Volatile private var serverRandom: ByteArray = ByteArray(0)
    @Volatile private var pendingId: ByteArray = ByteArray(0)
    @Volatile private var pendingModel: String = ""
    @Volatile private var pendingWorkModes: Int = 0

    fun start() {
        authKeyPair = CarlinkCrypto.generateKeyPair()
        agreementKeyPair = CarlinkCrypto.generateKeyPair()
        val ch = CarlinkChannel("auth", socket, ::onFrame, ::onClosed)
        channel = ch
        ch.start()
        Log.i(tag, "AUTH channel started, waiting for AuthRequest")
    }

    private fun onFrame(ch: CarlinkChannel, frame: CarlinkFrame, body: ByteArray) {
        try {
            when (frame.category) {
                ChannelCategory.AUTH -> handleAuthBody(frame, body)
                else -> Log.w(tag, "unexpected category ${frame.category} on AUTH channel")
            }
        } catch (t: Throwable) {
            Log.e(tag, "handle frame failed", t)
            onError(t)
        }
    }

    private fun handleAuthBody(frame: CarlinkFrame, body: ByteArray) {
        // method 号来自 AuthIndex 字段：1=auth_request, 2=auth_response, 3=auth_confirm
        when (frame.method) {
            1 -> handleAuthRequest(frame, body)
            3 -> handleAuthConfirm(frame, body)
            else -> Log.w(tag, "unexpected auth method ${frame.method}")
        }
    }

    private fun handleAuthRequest(frame: CarlinkFrame, body: ByteArray) {
        val req = CarlinkCodec.decodeAuthRequest(body)
        Log.i(
            tag,
            "AuthRequest version=${req.version} model=${req.model} idLen=${req.id.size()} " +
                "authPkLen=${req.authPk.size()} agreementPkLen=${req.agreementPk.size()} " +
                "randomLen=${req.random.size()} userConfirmed=${req.userConfirmed} workModes=${req.workModes}",
        )
        pendingId = req.id.toByteArray()
        pendingModel = req.model
        pendingWorkModes = req.workModes

        val phoneRandom = req.random.toByteArray()
        serverRandom = randomBytes(32)

        // ECDH：我方 agreement 私钥 + 手机 agreement 公钥。
        val peerAgreementPk = CarlinkCrypto.decodePublicKey(req.agreementPk.toByteArray())
        val shared = CarlinkCrypto.ecdh(agreementKeyPair.private, peerAgreementPk)

        // 会话密钥（pgy：salt=SHA256(Na‖Nb)，info="session_key"，16B；uibc 用同一把）。
        val key = CarlinkCrypto.deriveSessionKey(shared, phoneRandom, serverRandom)
        sessionKey = key
        uibcSessionKey = key
        Log.i(tag, "derived session_key (${key.size}B); uibc uses same key")

        val authPkBytes = CarlinkCrypto.encodePublicKey(authKeyPair.public)
        val agreementPkBytes = CarlinkCrypto.encodePublicKey(agreementKeyPair.public)

        val pin = pinProvider()?.toByteArray(Charsets.UTF_8)
        val pinOk = pin != null && pin.size >= 6

        // authPkHmac = HMAC-SHA256(SHA256(Nb‖PIN), authPkBytes)
        val authPkHmac: ByteArray = if (pinOk) {
            val hmacKey = CarlinkCrypto.sha256(serverRandom + pin!!)
            CarlinkCrypto.hmacSha256(hmacKey, authPkBytes)
        } else {
            ByteArray(0)
        }

        // agreementPkSig = SHA256withECDSA(authPk ‖ Nb ‖ Na)，用我方 auth 私钥签名。
        val agreementPkSig: ByteArray = if (pinOk) {
            signEcdsa(authKeyPair, authPkBytes + serverRandom + phoneRandom)
        } else {
            ByteArray(0)
        }

        val resp = UCarProto.AuthResponse.newBuilder()
            .setVersion(PROTOCOL_VERSION)
            .setAuthPk(com.google.protobuf.ByteString.copyFrom(authPkBytes))
            .setAuthPkHmac(com.google.protobuf.ByteString.copyFrom(authPkHmac))
            .setAgreementPk(com.google.protobuf.ByteString.copyFrom(agreementPkBytes))
            .setAgreementPkSig(com.google.protobuf.ByteString.copyFrom(agreementPkSig))
            .setRandom(com.google.protobuf.ByteString.copyFrom(serverRandom))
            .setResult(RESULT_OK)
            .setWorkModes(pendingWorkModes)
            .setDayOrNightMode(DAY_MODE)
            .setScreenInfo(screenInfo)
            .build()
        val out = CarlinkCodec.encodeAuthResponse(resp)
        channel?.send(ChannelCategory.AUTH, 2, out, MsgType.RES)
        Log.i(
            tag,
            "AuthResponse sent (result=$RESULT_OK, pinUsed=$pinOk, " +
                "authPkHmac=${authPkHmac.size}B, agreementPkSig=${agreementPkSig.size}B)",
        )
    }

    private fun handleAuthConfirm(frame: CarlinkFrame, body: ByteArray) {
        val confirm = CarlinkCodec.decodeAuthConfirm(body)
        val key = sessionKey ?: run {
            onError(IllegalStateException("AuthConfirm before key derivation"))
            return
        }
        val phoneIp = try {
            String(CarlinkCrypto.aesGcmDecrypt(key, confirm.cipher.toByteArray()))
        } catch (t: Throwable) {
            Log.w(tag, "AuthConfirm decrypt failed, falling back to socket peer ip", t)
            socket.inetAddress?.hostAddress ?: "0.0.0.0"
        }
        Log.i(tag, "AuthConfirm -> phoneIp=$phoneIp")
        onHandshakeDone(
            AuthResult(
                phoneIp = phoneIp,
                sessionKey = key,
                uibcSessionKey = uibcSessionKey ?: key,
                phoneId = pendingId,
                phoneModel = pendingModel,
                workModes = pendingWorkModes,
            ),
        )
    }

    private fun signEcdsa(kp: KeyPair, data: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(kp.private)
        sig.update(data)
        return sig.sign()
    }

    private fun onClosed(ch: CarlinkChannel, t: Throwable?) {
        if (t != null) Log.w(tag, "AUTH channel closed: ${t.message}")
    }

    fun close() {
        channel?.close()
    }

    private fun randomBytes(n: Int): ByteArray =
        ByteArray(n).also { SecureRandom().nextBytes(it) }

    companion object {
        const val PROTOCOL_VERSION = 1793 // 0x0701，蒲公英 AuthResponse.version
        const val RESULT_OK = 0
        const val DAY_MODE = 1
        // 屏幕信息格式 w_h_dpi_sww_swh（由宿主在 setScreenInfo 时覆盖）
        @Volatile var screenInfo: String = "1280_720_240_1280_720"
    }
}
