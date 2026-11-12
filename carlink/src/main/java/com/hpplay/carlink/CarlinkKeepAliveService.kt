package com.hpplay.carlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the CarLink process alive while it waits for a phone.
 *
 * 参考实现（ICCOA Carlink / 蒲公英）把 BLE 广播放在一个常驻 Service 里，所以车机页面切到后台、
 * 或者用户在设置里停留时，手机依然能扫到车机。我们原来把广播挂在 Activity 上，Activity 一被系统
 * 回收（或切协议重建），广播就没了——手机端就会表现为「有时能弹窗、有时完全没反应」。
 *
 * 这里用一个前台服务把进程钉住，BLE 广播与热点仍然由 [CarlinkHostActivity] 持有的对象负责。
 */
class CarlinkKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "carlink_wait"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(com.hpplay.host.R.string.carlink_keepalive_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle(getString(com.hpplay.host.R.string.carlink_keepalive_title))
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    companion object {
        private const val NOTIFICATION_ID = 0xCA11
    }
}
