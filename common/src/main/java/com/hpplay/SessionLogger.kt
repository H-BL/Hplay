package com.hpplay

import android.content.Context
import android.os.Build
import android.util.Log
import java.util.Locale

/**
 * 任意宿主（CarPlay / CarLife / CarLink）都能用的会话日志。
 *
 * 之前只有 CarPlay 宿主会把日志写进 `logs/hplay-*.log`，CarLife 与 CarLink 只打 logcat，
 * 用户上传后只能看到 CarPlay 的内容 —— CarLife 出问题时基本没法定位。这里把写盘逻辑抽出来，
 * 让每个协议各开一个带协议前缀的会话文件，全部落在同一个目录，
 * [LogFileStore.zipRecent] 打包上传时自然一并带走。
 *
 * 文件命名：`hplay-<protocol>-YYYYMMDD-HHmmss.log`，服务器上一眼能看出来源。
 */
object SessionLogger {
    private const val TAG = "SessionLogger"

    @Volatile
    private var file: SessionLogFile? = null

    @Volatile
    private var currentProtocol: String? = null

    /**
     * 为 [protocol] 开一个会话日志文件。
     *
     * 日志留存开关（设置 → 日志留存）关闭时全程不写盘，与 CarPlay 宿主原有行为一致。
     */
    fun start(context: Context, protocol: String, versionName: String) {
        if (!AirPlayPersistence.loadLogRetentionEnabled(context)) {
            Log.i(TAG, "log retention off; $protocol log not persisted")
            return
        }
        stop("superseded")
        runCatching {
            val app = context.applicationContext
            val target = LogFileStore.newSessionFile(app, protocol = protocol)
            val active = SessionLogFile(target)
            active.reset(
                "hplay log started protocol=$protocol app=$versionName sdk=" +
                    Build.VERSION.SDK_INT + " model=" +
                    (Build.MANUFACTURER + " " + Build.MODEL).trim() +
                    " path=" + target.absolutePath,
            )
            file = active
            currentProtocol = protocol
            Log.i(TAG, "$protocol session log -> ${target.absolutePath}")
        }.onFailure { Log.w(TAG, "could not open $protocol log: ${it.message}") }
    }

    /** 写一行（可从任意线程调用）。[protocol] 用来防止不同协议串台。 */
    fun log(protocol: String, message: String) {
        if (currentProtocol != protocol) return
        file?.appendTimestamped(message, System.currentTimeMillis())
    }

    fun stop(reason: String = "stopped") {
        val active = file ?: return
        val protocol = currentProtocol
        file = null
        currentProtocol = null
        runCatching {
            active.appendTimestamped("log stopped: $reason", System.currentTimeMillis())
            active.close()
        }.onFailure { Log.w(TAG, "closing ${protocol ?: "?"} log failed: ${it.message}") }
    }

    /** 是否正在为 [protocol] 记录。 */
    fun isRecording(protocol: String): Boolean = currentProtocol == protocol
}

/** 协议名规范化，用作日志文件名的一部分。 */
fun protocolTag(raw: String): String =
    raw.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "").ifBlank { "unknown" }
