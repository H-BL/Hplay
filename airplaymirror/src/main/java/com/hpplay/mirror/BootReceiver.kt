package com.hpplay.mirror

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.hpplay.mirror.service.AirPlayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "onReceive: $action")
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != ACTION_QUICKBOOT_POWERON &&
            action != Intent.ACTION_USER_PRESENT &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(Prefs.BOOT_AUTO_START, Prefs.DEF_BOOT_AUTO_START)
        Log.i(TAG, "autoStart=$autoStart")
        if (!autoStart) return

        try {
            val serviceIntent = Intent(context, AirPlayService::class.java)
                .setAction(AirPlayService.ACTION_START_SERVER)
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(TAG, "startForegroundService requested")
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService failed", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
