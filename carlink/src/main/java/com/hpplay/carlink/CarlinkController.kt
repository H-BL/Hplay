package com.hpplay.carlink

import android.content.Context
import android.util.Log
import android.view.Surface
import com.hpplay.carlink.channel.CarlinkControl
import com.hpplay.carlink.conn.CarlinkConnection
import com.hpplay.carlink.proto.CarlinkPorts
import com.hpplay.carlink.video.CarlinkVideoSink
import com.ucarsink.sink.natives.SinkNative

/**
 * CarLink 会话编排：连接层（TCP/AUTH/CONTROL）+ 视频层（libovmsink.so + MediaCodec）。
 *
 * 认证完成拿到手机 IP 后，调用 SinkNative.start(phoneIp, 7236, …) 让 .so 作为 RTSP 客户端连向
 * 手机的视频端口拉流，帧经回调交给 [CarlinkVideoSink] 解码上屏。
 */
class CarlinkController(
    private val context: Context,
    private val config: CarlinkControl.CarConfig,
    /** 提供当前热点密码（PIN），用于 AUTH 证明车机知道热点密码。 */
    private val pinProvider: () -> String? = { null },
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(state: String, detail: String)
    }

    private val tag = "CarlinkCtrl"
    val videoSink = CarlinkVideoSink()
    private var connection: CarlinkConnection? = null
    @Volatile private var phoneIp: String? = null
    @Volatile private var started = false
    private var surface: Surface? = null

    fun attachSurface(s: Surface?) {
        surface = s
        videoSink.attachSurface(s)
    }

    fun start() {
        if (started) return
        started = true
        SinkNative.registerListener(videoSink)
        videoSink.setLowLatency(config.supportLowLatency)
        val conn = CarlinkConnection(config, pinProvider, object : CarlinkConnection.Listener {
            override fun onState(state: CarlinkConnection.State, detail: String) {
                Log.i(tag, "conn state=$state $detail")
                listener.onStatus(state.name, detail)
            }

            override fun onAuthDone(phoneIp: String, phoneModel: String, workModes: Int) {
                this@CarlinkController.phoneIp = phoneIp
                listener.onStatus("AUTHED", "认证完成 phone=$phoneModel ip=$phoneIp")
                startWfdSink(phoneIp)
            }

            override fun onDisconnected() {
                stopWfdSink()
                listener.onStatus("DISCONNECTED", "手机已断开")
            }
        })
        connection = conn
        conn.start()
    }

    private fun startWfdSink(host: String) {
        try {
            val ok = SinkNative.start(
                host,
                CarlinkPorts.RTSP,
                config.videoWidth,
                config.videoHeight,
                config.dpi,
                config.fps,
                /* uibcEnc */ false,
                config.supportLowLatency,
                config.supportHevc,
            )
            Log.i(tag, "SinkNative.start(host=$host) -> $ok")
            listener.onStatus("WFD", "视频拉流 start=$ok (host=$host:${CarlinkPorts.RTSP})")
        } catch (t: Throwable) {
            Log.e(tag, "SinkNative.start failed", t)
            listener.onStatus("ERROR", "视频拉流启动失败: ${t.message}")
        }
    }

    private fun stopWfdSink() {
        val host = phoneIp ?: return
        runCatching { SinkNative.stop(host) }
        phoneIp = null
    }

    fun stop() {
        started = false
        stopWfdSink()
        connection?.stop()
        connection = null
        SinkNative.unregisterListener()
        videoSink.release()
    }
}
