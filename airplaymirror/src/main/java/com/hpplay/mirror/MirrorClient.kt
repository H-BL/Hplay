package com.hpplay.mirror

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.hpplay.mirror.service.AirPlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 供宿主(Hplay)使用的 AirPlay 镜像控制门面。
 *
 * 用法：
 * ```
 * val client = MirrorClient(context)
 * client.start()                       // 启动镜像接收服务（可选传入显示模式）
 * client.attachSurface(surface)        // 分屏模式：绑定宿主提供的 Surface
 * client.detachSurface(surface)        // 解绑
 * client.stop()                        // 停止
 * ```
 *
 * 该门面内部绑定 [AirPlayService]，对外暴露服务状态流；调用方无需直接持有服务。
 */
class MirrorClient(private val appContext: Context) {

    private val tag = "MirrorClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var service: AirPlayService? = null

    private val _connected = MutableStateFlow(false)
    /** 服务是否已绑定就绪 */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _serverState = MutableStateFlow(AirPlayService.ServerState.STOPPED)
    val serverState: StateFlow<AirPlayService.ServerState> = _serverState.asStateFlow()

    private val _mirroringActive = MutableStateFlow(false)
    /** 是否已收到真实镜像画面（可用于让宿主切换布局） */
    val mirroringActive: StateFlow<Boolean> = _mirroringActive.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? AirPlayService.LocalBinder)?.service
            service = svc
            _connected.value = svc != null
            if (svc != null) {
                scope.launch { svc.serverState.collect { _serverState.value = it } }
                scope.launch { svc.mirroringActive.collect { _mirroringActive.value = it } }
                scope.launch { svc.videoAspect.collect { _videoAspect.value = it } }
            }
            Log.i(tag, "service connected=$_connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _connected.value = false
            Log.i(tag, "service disconnected")
        }
    }

    /** 绑定服务（不启动接收）。幂等。 */
    fun bind() {
        if (service != null) return
        val intent = Intent(appContext, AirPlayService::class.java)
        runCatching { appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w(tag, "bind failed: ${it.message}") }
    }

    fun unbind() {
        if (service == null) return
        runCatching { appContext.unbindService(connection) }
        service = null
        _connected.value = false
    }

    /**
     * 启动 AirPlay 镜像接收服务。
     * @param displayMode [Prefs.DISPLAY_MODE_EXTERNAL]（宿主分屏）或 [Prefs.DISPLAY_MODE_OVERLAY]（系统悬浮窗）
     */
    fun start(displayMode: String = Prefs.DEF_DISPLAY_MODE) {
        prefs().edit().putString(Prefs.DISPLAY_MODE, displayMode).apply()
        val intent = Intent(appContext, AirPlayService::class.java)
            .setAction(AirPlayService.ACTION_START_SERVER)
        ContextCompat.startForegroundService(appContext, intent)
        Log.i(tag, "start requested, mode=$displayMode")
    }

    fun stop() {
        val intent = Intent(appContext, AirPlayService::class.java)
            .setAction(AirPlayService.ACTION_STOP_SERVER)
        runCatching { appContext.startService(intent) }
            .onFailure { Log.w(tag, "stop failed: ${it.message}") }
    }

    /** 分屏模式：绑定宿主提供的镜像画面 Surface。 */
    fun attachSurface(surface: android.view.Surface) {
        service?.setVideoSurface(surface)
    }

    fun detachSurface(surface: android.view.Surface) {
        service?.clearVideoSurface(surface)
    }

    /** 运行时切换显示目标。 */
    fun setDisplayMode(mode: String) {
        service?.applyDisplayMode(mode)
    }

    fun currentVideoAspect(): Float = service?.videoAspect?.value ?: (16f / 9f)

    private fun prefs() = appContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
}
