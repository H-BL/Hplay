package com.hpplay

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * On-disk home for the retained logs: one file per app session, plus the generated zip that the
 * uploader sends.
 *
 * Retention is deliberately simple and bounded: keep the newest [KEEP_DAYS] days of sessions and
 * never hold more than [MAX_TOTAL_BYTES]. Both are enforced after every new session starts, so a
 * head unit left running for months cannot fill its storage.
 */
internal object LogFileStore {
    const val KEEP_DAYS = 7
    const val MAX_TOTAL_BYTES = 50L * 1024 * 1024

    private const val SESSION_PREFIX = "hplay-"
    private const val SESSION_SUFFIX = ".log"
    private const val ZIP_PREFIX = "hplay-logs-"

    fun dir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "logs").apply { mkdirs() }
    }

    /**
     * Starts a fresh session file and returns it. Prunes old sessions first.
     *
     * [protocol] 会把来源写进文件名（`hplay-carlife-…`），这样 CarPlay / CarLife / CarLink
     * 的日志混在同一个上传包里也能一眼分辨。
     */
    fun newSessionFile(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        protocol: String? = null,
    ): File {
        val dir = dir(context)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(java.util.Date(nowMillis))
        val tag = protocol?.let { protocolTag(it) + "-" } ?: ""
        val file = File(dir, "$SESSION_PREFIX$tag$stamp$SESSION_SUFFIX")
        prune(context, nowMillis)
        return file
    }

    fun sessions(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.startsWith(SESSION_PREFIX) &&
            f.name.endsWith(SESSION_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun totalBytes(files: List<File>): Long = files.sumOf { if (it.isFile) it.length() else 0L }

    /** Deletes sessions older than [KEEP_DAYS] and then the oldest until under [MAX_TOTAL_BYTES]. */
    fun prune(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        runCatching {
            val cutoff = nowMillis - TimeUnit.DAYS.toMillis(KEEP_DAYS.toLong())
            val remaining = sessions(context).filter { file ->
                if (file.lastModified() < cutoff) {
                    file.delete()
                    false
                } else {
                    true
                }
            }.toMutableList()

            var total = totalBytes(remaining)
            while (total > MAX_TOTAL_BYTES && remaining.size > 1) {
                val oldest = remaining.removeAt(remaining.lastIndex)
                total -= oldest.length()
                oldest.delete()
            }
        }
    }

    fun newestSession(context: Context): File? = sessions(context).firstOrNull()

    /**
     * Bundles the retained sessions into one zip so a single upload carries the whole story rather
     * than just the file that happened to still be open.
     */
    fun zipRecent(context: Context, limit: Int = 20): File? {
        val files = sessions(context).take(limit)
        if (files.isEmpty()) return null
        val dir = dir(context)
        val zip = File(dir, "$ZIP_PREFIX${System.currentTimeMillis()}.zip")
        runCatching {
            java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { out ->
                files.forEach { file ->
                    out.putNextEntry(java.util.zip.ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }.onFailure {
            zip.delete()
            return null
        }
        return zip
    }

    fun deleteZips(context: Context) {
        dir(context).listFiles { f -> f.isFile && f.name.startsWith(ZIP_PREFIX) }?.forEach {
            it.delete()
        }
    }
}
