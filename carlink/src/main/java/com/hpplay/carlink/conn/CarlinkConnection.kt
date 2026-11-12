package com.hpplay.carlink.conn

import android.util.Log
import com.hpplay.carlink.auth.CarlinkAuth
import com.hpplay.carlink.auth.CarlinkCrypto
import com.hpplay.carlink.channel.CarlinkChannel
import com.hpplay.carlink.channel.CarlinkControl
import com.hpplay.carlink.proto.CarlinkPorts
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * CarLink 车机端连接层：监听 AUTH/CONTROL/SENSOR/MEDIA/CERT/VIDEO 六个端口，等待手机连入。
 *
 * 车机 = TCP 服务端（与 libovmsink.so 作为 RTSP 客户端相对）。手机连入 AUTH 完成握手后，
 * 拿到手机 IP（= 视频 RTSP 源 host）与 AES 会话密钥；随后 CONTROL/SENSOR/MEDIA/CERT/VIDEO
 * 通道用该密钥加密通信。
 *
 * 端口集合逐字节对齐蒲公英：pgy 的 I0/g、U0/a、a1/b、T0/m 分别监听 AUTH/CONTROL/VIDEO/
 * SENSOR/MEDIA/CERT 六个端口；此前只绑了 4 个，缺 VIDEO(57259) 与 CERT(57249) 会导致手机
 * 连这两端口时 connection refused。
 */
class CarlinkConnection(
    private val config: CarlinkControl.CarConfig,
    /** 提供当前热点密码（PIN），用于 AUTH 的 authPkHmac/agreementPkSig；null 表示暂不可用。 */
    private val pinProvider: () -> String? = { null },
    private val listener: Listener,
) {
    interface Listener {
        fun onState(state: State, detail: String)
        fun onAuthDone(phoneIp: String, phoneModel: String, workModes: Int)
        fun onDisconnected()
    }

    enum class State { IDLE, LISTENING, AUTHENTICATING, CONNECTED, ERROR }

    private val tag = "CarlinkConn"
    @Volatile private var running = false
    private var authServer: ServerSocket? = null
    private var controlServer: ServerSocket? = null
    private var sensorServer: ServerSocket? = null
    private var mediaServer: ServerSocket? = null
    private var certServer: ServerSocket? = null
    private var videoServer: ServerSocket? = null

    private var auth: CarlinkAuth? = null
    private var control: CarlinkControl? = null
    private val extraChannels = mutableListOf<CarlinkChannel>()
    @Volatile private var sessionKey: ByteArray? = null
    @Volatile private var uibcSessionKey: ByteArray? = null

    /** 发送加密闭包：逐帧动态读取当前 sessionKey；key 未就绪时原样透传（握手前帧）。 */
    private fun encryptor(): (ByteArray) -> ByteArray = { body ->
        val key = sessionKey
        if (key != null) CarlinkCrypto.aesGcmEncrypt(key, body) else body
    }

    private fun decryptor(): (ByteArray) -> ByteArray = { body ->
        val key = sessionKey
        if (key != null) CarlinkCrypto.aesGcmDecrypt(key, body) else body
    }

    fun start() {
        if (running) return
        running = true
        authServer = bind(CarlinkPorts.AUTH)
        controlServer = bind(CarlinkPorts.CONTROL)
        sensorServer = bind(CarlinkPorts.SENSOR)
        mediaServer = bind(CarlinkPorts.MEDIA)
        certServer = bind(CarlinkPorts.CERT)
        videoServer = bind(CarlinkPorts.VIDEO)
        if (authServer == null) {
            listener.onState(State.ERROR, "AUTH 端口 ${CarlinkPorts.AUTH} 绑定失败")
            return
        }
        listener.onState(
            State.LISTENING,
            "监听端口 AUTH=${CarlinkPorts.AUTH} CONTROL=${CarlinkPorts.CONTROL} " +
                "SENSOR=${CarlinkPorts.SENSOR} MEDIA=${CarlinkPorts.MEDIA} " +
                "CERT=${CarlinkPorts.CERT} VIDEO=${CarlinkPorts.VIDEO}",
        )
        acceptLoop("auth", authServer) { acceptAuth(it) }
        acceptLoop("control", controlServer) { acceptControl(it) }
        acceptLoop("sensor", sensorServer) { acceptSensor(it) }
        acceptLoop("media", mediaServer) { acceptMedia(it) }
        acceptLoop("cert", certServer) { acceptCert(it) }
        acceptLoop("video", videoServer) { acceptVideo(it) }
    }

    private fun bind(port: Int): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
    } catch (t: Throwable) {
        Log.e(tag, "bind $port failed", t)
        null
    }

    private fun acceptLoop(name: String, server: ServerSocket?, handler: (Socket) -> Unit) {
        if (server == null) {
            Log.w(tag, "[$name] server not bound, skip accept loop")
            return
        }
        Thread({
            while (running) {
                try {
                    val s = server.accept()
                    Log.i(tag, "[$name] phone connected from ${s.inetAddress?.hostAddress}")
                    handler(s)
                } catch (t: Throwable) {
                    if (running) Log.w(tag, "[$name] accept ended: ${t.message}")
                    break
                }
            }
        }, "carlink-accept-$name").apply { isDaemon = true; start() }
    }

    private fun acceptAuth(socket: Socket) {
        listener.onState(State.AUTHENTICATING, "手机已连入 AUTH，握手进行中…")
        val a = CarlinkAuth(
            socket,
            pinProvider = pinProvider,
            onHandshakeDone = { result ->
                sessionKey = result.sessionKey
                uibcSessionKey = result.uibcSessionKey
                listener.onState(State.CONNECTED, "认证完成，手机IP=${result.phoneIp}")
                listener.onAuthDone(result.phoneIp, result.phoneModel, result.workModes)
            },
            onError = { t ->
                listener.onState(State.ERROR, "AUTH 握手失败: ${t.message}")
            },
        )
        auth = a
        a.start()
    }

    private fun acceptControl(socket: Socket) {
        val c = CarlinkControl(
            socket,
            config,
            onDisconnect = {
                listener.onDisconnected()
                stop()
            },
            encrypt = encryptor(),
            decrypt = decryptor(),
        )
        control = c
        c.start()
        listener.onState(State.CONNECTED, "CONTROL 通道已建立")
    }

    /** 建立一条带加密的通用通道并加入管理列表（SENSOR/MEDIA/CERT/VIDEO 共用）。 */
    private fun startExtraChannel(name: String, socket: Socket) {
        val ch = CarlinkChannel(
            name,
            socket,
            { _, frame, _ -> Log.d(tag, "$name frame cat=${frame.category} method=${frame.method}") },
            encryptBody = encryptor(),
            decryptBody = decryptor(),
        )
        synchronized(extraChannels) { extraChannels.add(ch) }
        ch.start()
    }

    private fun acceptSensor(socket: Socket) {
        // 传感器下行（车机 → 手机）：GPS/灯光/档位等。建链并保活，具体上报按需扩展。
        startExtraChannel("sensor", socket)
    }

    private fun acceptMedia(socket: Socket) {
        // MEDIA 通道（音频/UIBC 控制流）。建链并保活，具体消息按需扩展。
        startExtraChannel("media", socket)
    }

    private fun acceptCert(socket: Socket) {
        // CERT 通道（证书/鉴权材料）。建链并保活，具体消息按需扩展。
        startExtraChannel("cert", socket)
    }

    private fun acceptVideo(socket: Socket) {
        // VIDEO 通道（视频控制/信令；媒体面 RTP 由 libovmsink.so 拉流）。建链并保活。
        startExtraChannel("video", socket)
    }

    fun stop() {
        running = false
        runCatching { authServer?.close() }
        runCatching { controlServer?.close() }
        runCatching { sensorServer?.close() }
        runCatching { mediaServer?.close() }
        runCatching { certServer?.close() }
        runCatching { videoServer?.close() }
        auth?.close()
        control?.close()
        synchronized(extraChannels) {
            extraChannels.forEach { runCatching { it.close() } }
            extraChannels.clear()
        }
        authServer = null; controlServer = null; sensorServer = null
        mediaServer = null; certServer = null; videoServer = null
        auth = null; control = null
        sessionKey = null; uibcSessionKey = null
        listener.onState(State.IDLE, "已停止监听")
    }
}
