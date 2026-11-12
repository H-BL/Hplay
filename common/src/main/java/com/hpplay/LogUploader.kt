package com.hpplay

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Ships the retained logs to the relay, which stores them and pushes a Bark notification.
 *
 * The relay is the only party that talks to Bark, so no personal push key ever ships in the APK.
 */
internal object LogUploader {
    const val URL = "http://101.43.92.212:18080/logsapi/api/logs"
    const val TOKEN = "yhWHrFBw7qssvaeW7lkXwxHjMLHW3bTm"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val CRLF = "\r\n"

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "hplay-log-upload").apply { isDaemon = true }
    }

    sealed interface Outcome {
        data class Success(val id: String, val bytes: Long, val url: String) : Outcome
        data class Failure(val reason: String) : Outcome
    }

    fun endpoint(context: Context): String =
        AirPlayPersistence.loadLogUploadUrl(context).ifBlank { URL }

    fun token(context: Context): String =
        AirPlayPersistence.loadLogUploadToken(context).ifBlank { TOKEN }

    /**
     * Zips the retained sessions on a background thread and posts them to the relay.
     * [onDone] is invoked exactly once, off the main thread.
     */
    fun upload(context: Context, note: String, onDone: (Outcome) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val outcome = runCatching { doUpload(app, note) }
                .getOrElse { Outcome.Failure(it.message ?: it.javaClass.simpleName) }
            onDone(outcome)
        }
    }

    private fun doUpload(context: Context, note: String): Outcome {
        val zip = LogFileStore.zipRecent(context)
            ?: return Outcome.Failure("本机还没有可上传的日志")
        return try {
            post(context, zip, note)
        } finally {
            zip.delete()
        }
    }

    private fun post(context: Context, zip: File, note: String): Outcome {
        val boundary = "----hplay" + UUID.randomUUID().toString().replace("-", "")
        val contentBoundary = "multipart/form-data; boundary=" + boundary
        val fields = linkedMapOf(
            "device" to deviceTag(),
            "app_version" to appVersion(context),
            "model" to (Build.MANUFACTURER + " " + Build.MODEL).trim(),
            "note" to note,
            "protocols" to protocolSummary(context),
            "session_count" to LogFileStore.sessions(context).size.toString(),
        )
        val connection = (URL(endpoint(context)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", contentBoundary)
            setRequestProperty("X-Token", token(context))
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.buffered().use { out ->
                fields.forEach { (name, value) ->
                    out.write(partHeader(boundary, name, value).toByteArray(Charsets.UTF_8))
                }
                out.write(fileHeader(boundary).toByteArray(Charsets.UTF_8))
                zip.inputStream().use { it.copyTo(out) }
                out.write(("" + CRLF + "--" + boundary + "--" + CRLF).toByteArray(Charsets.UTF_8))
            }
            return readResponse(connection, zip.length())
        } catch (io: IOException) {
            return Outcome.Failure("网络错误：" + (io.message ?: io.javaClass.simpleName))
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection, byteCount: Long): Outcome {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            return Outcome.Failure("服务器返回 HTTP " + code + " " + body.take(160))
        }
        val id = JSON_ID.find(body)?.groupValues?.get(1) ?: ""
        val link = JSON_URL.find(body)?.groupValues?.get(1).orEmpty()
        return Outcome.Success(id, byteCount, link)
    }

    private fun partHeader(boundary: String, name: String, value: String): String {
        val sb = StringBuilder()
        sb.append("--").append(boundary).append(CRLF)
        sb.append("Content-Disposition: form-data; name=").append(DQ).append(name).append(DQ)
        sb.append(CRLF).append(CRLF)
        sb.append(value).append(CRLF)
        return sb.toString()
    }

    private fun fileHeader(boundary: String): String {
        val sb = StringBuilder()
        sb.append("--").append(boundary).append(CRLF)
        sb.append("Content-Disposition: form-data; name=").append(DQ).append("file").append(DQ)
        sb.append("; filename=").append(DQ).append("hplay-logs.zip").append(DQ).append(CRLF)
        sb.append("Content-Type: application/zip").append(CRLF).append(CRLF)
        return sb.toString()
    }

    /**
     * 包里包含哪些协议的日志（carplay / carlife / carlink …）。
     *
     * 以前上传包里只有 CarPlay 的会话文件，现在每个协议各写一份，这里让服务器端一眼看出覆盖面。
     */
    private fun protocolSummary(context: Context): String =
        LogFileStore.sessions(context)
            .map { file ->
                // 命名规则：
                //   hplay-carlife-20261103-081500.log  -> 4 段，协议在 [1]
                //   hplay-20261103-081500.log          -> 3 段，旧版 CarPlay 日志，无协议段
                val parts = file.nameWithoutExtension.split("-")
                if (parts.size >= 4) parts[1] else "carplay"
            }
            .groupingBy { it }
            .eachCount()
            .entries
            .joinToString(",") { it.key + ":" + it.value }

    private fun deviceTag(): String {
        val raw = Build.MODEL.ifBlank { "device" }
        return raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(32).ifBlank { "device" }
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName + "(" + info.longVersionCode + ")"
    }.getOrDefault("")

    private const val DQ = "\""
    private val JSON_ID = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
    private val JSON_URL = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
}
