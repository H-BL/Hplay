package com.hpplay.lingfans

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Presence probe for the LingFans co-creation desktop (`com.zzh.launcher50s.alpha`).
 *
 * The only trustworthy "the feature may be enabled" signal is the cross-process `getApiInfo`
 * handshake. Package name and provider lookups exist to explain *why* it is unavailable, never to
 * enable anything on their own: a desktop can be installed with an old build that has no
 * co-creation provider, and a provider can resolve while its process is dead.
 *
 * The probe blocks, so callers must be on a worker thread. Results are cached for [TTL_MS]; install,
 * uninstall and upgrade clear the cache through [onPackageChanged].
 *
 * Contract reference: `菱粉接口/lingfans-cocreate-api.md` (apiVersion 1).
 */
object LingFansPresence {

    private const val TAG = "lingfans-probe"
    private const val MIN_API = 1
    private const val TTL_MS = 60_000L
    private const val CALL_TIMEOUT_MS = 800L
    private const val RUNTIME_READY = "READY"

    /**
     * Known desktops and their co-creation authority. The authority is `<applicationId>.cocreate`,
     * so a reskinned or debug-variant desktop needs an extra entry (and a matching `<queries>` entry
     * in the consuming manifest, otherwise package visibility hides it on Android 11+).
     */
    val CANDIDATES: List<Candidate> = listOf(
        Candidate("com.zzh.launcher50s.alpha", "com.zzh.launcher50s.alpha.cocreate"),
        Candidate("com.zzh.launcher50s", "com.zzh.launcher50s.cocreate"),
    )

    data class Candidate(val pkg: String, val authority: String) {
        val uri: Uri get() = Uri.parse("content://$authority")
    }

    sealed interface State {
        /** Not installed, or invisible because the manifest lacks the matching `<queries>` entry. */
        data object NotDetected : State

        /** Installed, but the build predates the co-creation provider. */
        data object NoProvider : State

        /** Provider exists but is disabled or not exported, so it cannot be called. */
        data object ProviderDisabled : State

        /** Provider resolved, but the call itself failed or timed out. Retry rather than disable. */
        data object BackendMissing : State

        data class Outdated(val apiVersion: Int) : State

        /** Handshake worked but the desktop is not ready (NOT_READY / UNSUPPORTED / RECOVERY). */
        data class NotReady(val runtime: String) : State

        data class Ready(
            val authority: String,
            val apiVersion: Int,
            val runtime: String,
            val desktopVersion: String?,
        ) : State

        /** True only for [Ready]; the settings switch must stay disabled otherwise. */
        val available: Boolean get() = this is Ready
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, TAG).apply { isDaemon = true }
    }

    @Volatile private var cached: State? = null

    @Volatile private var cachedAt = 0L

    /** Fresh cached verdict, or null when the cache is stale/empty. Safe on the main thread. */
    fun cached(now: Long = System.currentTimeMillis()): State? =
        cached?.takeIf { now - cachedAt in 0..TTL_MS }

    /** Drop the cache so the next [probe] really talks to the desktop. */
    fun invalidate() {
        cached = null
        cachedAt = 0L
    }

    /** Called from a package broadcast: only our candidates invalidate the verdict. */
    fun onPackageChanged(pkg: String?) {
        if (pkg == null || CANDIDATES.any { it.pkg == pkg }) invalidate()
    }

    /**
     * Full probe, blocking until a verdict or [CALL_TIMEOUT_MS]. Never call on the main thread.
     *
     * @param useCache returns the cached verdict while it is younger than [TTL_MS].
     */
    fun probe(context: Context, useCache: Boolean = true): State {
        if (useCache) cached()?.let { return it }
        val verdict = probeNow(context.applicationContext)
        cached = verdict
        cachedAt = System.currentTimeMillis()
        Log.d(TAG, "probe -> $verdict")
        return verdict
    }

    private fun probeNow(context: Context): State {
        val pm = context.packageManager
        var installed = false
        var sawProvider = false

        for (candidate in CANDIDATES) {
            if (isInstalled(pm, candidate.pkg)) installed = true

            val info = providerInfo(pm, candidate.authority) ?: continue
            sawProvider = true
            if (!info.exported || !info.enabled) return State.ProviderDisabled

            val reply = callApiInfo(context, candidate) ?: continue
            return classify(reply, candidate) ?: continue
        }

        return when {
            sawProvider -> State.BackendMissing
            installed -> State.NoProvider
            else -> State.NotDetected
        }
    }

    /**
     * Interprets the `getApiInfo` bundle. Returns null when this candidate answered but is not the
     * interface we speak, so the caller moves on to the next one.
     */
    private fun classify(bundle: Bundle, candidate: Candidate): State? {
        if (!bundle.getBoolean("ok", false)) return null

        val apiVersion = bundle.getInt("apiVersion", 0)
        if (apiVersion < MIN_API) return State.Outdated(apiVersion)

        // Field names come from the desktop's payload; runtime defaults to READY because a successful
        // handshake already proves the interface is alive.
        val payload = bundle.getString("payloadJson")
            ?.let { json -> runCatching { JSONObject(json) }.getOrNull() }
        val runtime = payload?.optString("runtime").orEmpty().ifEmpty { RUNTIME_READY }
        val desktopVersion = payload?.optString("desktopVersion")?.takeIf { it.isNotBlank() }

        return if (runtime == RUNTIME_READY) {
            State.Ready(candidate.authority, apiVersion, runtime, desktopVersion)
        } else {
            State.NotReady(runtime)
        }
    }

    /**
     * `ContentResolver.call` can sit behind a busy desktop main thread, so it is bounded here. The
     * call itself is not interruptible: a timed-out probe may leave one worker blocked, which is the
     * accepted cost of never stalling the caller.
     */
    private fun callApiInfo(context: Context, candidate: Candidate): Bundle? {
        val task = executor.submit<Bundle?> {
            runCatching { context.contentResolver.call(candidate.uri, "getApiInfo", null, null) }
                .getOrNull()
        }
        val reply = runCatching { task.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
        if (reply == null) task.cancel(true)
        return reply
    }

    private fun providerInfo(pm: PackageManager, authority: String): ProviderInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveContentProvider(authority, 0)
        }
    }.getOrNull()

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
    }.isSuccess

    /**
     * Optional: drop the cache as soon as the desktop is installed, removed or upgraded, so the
     * settings page does not have to wait out the TTL. Call once from Application/onCreate.
     */
    fun observePackages(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                onPackageChanged(intent?.data?.schemeSpecificPart)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }
}
