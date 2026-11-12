package com.hpplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.hpplay.airplay.VideoInCar
import com.hpplay.orchestration.CarPlayController
import com.hpplay.orchestration.CarPlayVideoListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The host's video surface, driven by [CarPlayVideo] on the main thread. */
interface VideoHost {
    /** Show the player for [url], starting at [startMillis]; [streaming] marks an HLS item. */
    fun showVideo(url: String, startMillis: Int, streaming: Boolean)
    /** Hide the player; the item stays paused where it was. */
    fun hideVideo()
    /** Apply the iPhone's play/pause (open the player if it is not shown yet). */
    fun applyRate(playing: Boolean)
    /** Apply the iPhone's seek position in milliseconds. */
    fun applySeek(millis: Int)
    /** The player's state for playbackInfo/property answers, or null while closed. */
    fun playerState(): VideoInCar.PlayerState?
}

/**
 * iOS 26/27 video in car (see [VideoInCar]). The iPhone hands the car a media URL (insertPlayQueueItem)
 * and drives it (setRate, seek, stop); the car plays it in an overlay over CarPlay, which stays
 * connected underneath, and only while the car reports P (forced by the VideoInCar switch).
 */
internal object CarPlayVideo : CarPlayVideoListener {
    private const val TAG = "Hplay-Video"
    const val SKIP_MILLIS = 10_000
    private const val URL_TIMEOUT_SECONDS = 10L

    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "hplay-video-reply").apply { isDaemon = true } }
    @Volatile private var appContext: Context? = null
    @Volatile private var controller: CarPlayController? = null
    @Volatile private var host: VideoHost? = null

    // Main-thread state; the host applies it. The stream and item are also read by loader threads.
    @Volatile private var streamId: Long? = null
    @Volatile private var itemUuid: Any? = null
    var url: String? = null
        private set
    var streaming = false
        private set
    var startMillis = 0
        private set
    var playing = false
        private set

    fun attach(context: Context, next: CarPlayController, videoHost: VideoHost) {
        appContext = context.applicationContext
        controller = next
        host = videoHost
        next.videoListener = this
    }

    fun detach() {
        controller?.videoListener = null
        controller = null
        host = null
    }

    override fun onVideoAllowedChanged(allowed: Boolean) {
        if (!allowed) main.post { hidePlayer("video no longer allowed") }
    }

    override fun onVideoSessionEnded() {
        main.post {
            stop()
            streamId = null
        }
    }

    override fun onVideoUiRequested() {
        main.post { show() }
    }

    override fun onVideoMessage(streamId: Long, message: Map<String, Any?>) {
        main.post { handle(streamId, message) }
    }

    /** Play or pause from the car; tells the iPhone at once. Main thread. */
    fun setPlaying(next: Boolean) {
        playing = next
        host?.applyRate(next)
        streamId?.let { reply(it, VideoInCar.playbackStateNotification(next, itemUuid)) }
    }

    /** The car's player cannot play the item; tell the iPhone and go back to CarPlay. Main thread. */
    fun onPlayerFailed(code: Int) {
        Log.w(TAG, "item cannot play here code=$code")
        streamId?.let { reply(it, VideoInCar.errorNotification(itemUuid, code)) }
        appContext?.let { Toast.makeText(it, "无法在此车机播放该视频", Toast.LENGTH_LONG).show() }
        stop()
    }

    /** The player closed on the car: pause, so the iPhone shows it paused. */
    fun onPlayerClosed(positionMillis: Int?) {
        positionMillis?.let { startMillis = it }
        if (playing) setPlaying(false)
    }

    private fun handle(streamId: Long, message: Map<String, Any?>) {
        this.streamId = streamId
        val request = message["kind"] == "request"
        when (val type = message["type"] as? String) {
            "insertPlayQueueItem" -> {
                val item = VideoInCar.parseItem(message, iphoneLoadsAppSchemes = true)
                if (item == null) {
                    Log.w(TAG, "queue item this player cannot play")
                    return
                }
                url = item.url
                itemUuid = item.uuid
                val queued = message["item"] as? Map<*, *>
                streaming = queued?.get("mediaType") == "streaming"
                startMillis = item.startMillis
                Log.i(TAG, "queue item host=${android.net.Uri.parse(item.url).host} streaming=$streaming")
                host?.showVideo(item.url, item.startMillis, streaming)
            }
            "setRate" -> {
                playing = ((message["rate"] as? Number)?.toDouble() ?: 0.0) > 0.0
                if (playing) show()
                host?.applyRate(playing)
            }
            "seek" -> {
                VideoInCar.seekMillis(message)?.let { host?.applySeek(it) }
                if (request) reply(streamId, VideoInCar.seekResponse(message["messageID"]))
            }
            "playbackInfo" -> if (request) {
                reply(streamId, VideoInCar.playbackInfoResponse(message["messageID"], itemUuid, host?.playerState()))
            }
            "property" -> if (request) {
                reply(streamId, VideoInCar.propertyResponse(message["messageID"], message["property"], host?.playerState()))
            }
            "unhandledURL" -> onUrlLoaded(message)
            "stop", "removePlayQueueItem" -> stop()
            "setProperty" -> Unit
            else -> Log.i(TAG, "video message $type keys=${message.keys}")
        }
    }

    /** What the iPhone answered to [resolveOnIphone]. */
    class LoadedUrl(val status: Int?, val data: ByteArray?, val location: String?)

    private val pendingUrls = ConcurrentHashMap<Long, CompletableFuture<Map<*, *>>>()
    private val nextUrlRequest = AtomicLong(1)

    /**
     * Asks the iPhone to load a URL the car cannot (an app's own scheme): unhandledURL request answered
     * with FCUP_Response_Data, _StatusCode and _Headers (Location). Blocking; a loader thread.
     */
    fun resolveOnIphone(url: String): LoadedUrl? {
        val stream = streamId ?: return null
        val id = nextUrlRequest.getAndIncrement()
        val answer = CompletableFuture<Map<*, *>>()
        pendingUrls[id] = answer
        reply(stream, linkedMapOf(
            "type" to "unhandledURL",
            "kind" to "request",
            "request" to linkedMapOf(
                "FCUP_Response_URL" to url,
                "FCUP_Response_IsContentKeyRequest" to false,
                "FCUP_Response_RequestID" to id,
            ),
            "item" to linkedMapOf("uuid" to itemUuid),
        ))
        Log.i(TAG, "asked the iPhone to load a ${android.net.Uri.parse(url).scheme} URL request=$id")
        val response = try {
            answer.get(URL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            Log.w(TAG, "no iPhone answer for request=$id")
            null
        } finally {
            pendingUrls.remove(id)
        }
        response ?: return null
        val headers = response["FCUP_Response_Headers"] as? Map<*, *>
        return LoadedUrl(
            status = (response["FCUP_Response_StatusCode"] as? Number)?.toInt(),
            data = response["FCUP_Response_Data"] as? ByteArray,
            location = (headers?.get("Location") ?: headers?.get("location")) as? String,
        )
    }

    private fun onUrlLoaded(message: Map<String, Any?>) {
        val response = message["response"] as? Map<*, *>
        val id = (response?.get("FCUP_Response_RequestID") as? Number)?.toLong()
        if (id != null) pendingUrls[id]?.complete(response)
    }

    private fun reply(streamId: Long, message: Map<String, Any?>) {
        val target = controller ?: return
        sender.execute {
            val sent = runCatching { target.sendVideoMessage(streamId, message) }
                .onFailure { Log.w(TAG, "reply ${message["type"]} failed: ${it.javaClass.simpleName}") }
                .getOrDefault(false)
            if (!sent) Log.w(TAG, "reply ${message["type"]} not sent")
        }
    }

    private fun show() {
        val current = url ?: run { Log.w(TAG, "video player requested without a playable item"); return }
        host?.showVideo(current, startMillis, streaming)
    }

    private fun hidePlayer(reason: String) {
        Log.i(TAG, "closing the video player: $reason")
        host?.hideVideo()
    }

    private fun stop() {
        url = null
        itemUuid = null
        playing = false
        host?.hideVideo()
    }
}
