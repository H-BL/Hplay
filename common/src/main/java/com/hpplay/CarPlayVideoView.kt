package com.hpplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.hpplay.airplay.VideoInCar
import java.util.Locale
import kotlin.math.roundToInt
import androidx.media3.ui.R as Media3R

/**
 * iOS 26/27 video in car's player (see [CarPlayVideo]), shown as a panel over CarPlay so both stay
 * visible: by default the video sits on the right (about half the width) and CarPlay is visible and
 * touchable in the rest; a button expands it to full screen. The panel itself is touch-interactive
 * (Back, play/pause, 10 s back/forward, time bar). Media3 ExoPlayer parses the media in the app; URLs
 * the car cannot load go to the iPhone ([IphoneResolvingDataSource]).
 *
 * The root fills the screen but is transparent and not clickable, so touches outside the panel fall
 * through to the CarPlay gesture layer underneath.
 */
@OptIn(UnstableApi::class)
internal class CarPlayVideoView(context: Context) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val panel: FrameLayout
    private val playerView: PlayerView
    private val controls: View
    private lateinit var playPause: ImageView
    private lateinit var position: TextView
    private lateinit var length: TextView
    private lateinit var timeBar: SeekBar
    private lateinit var sizeToggle: TextView
    private var player: ExoPlayer? = null
    private var prepared = false
    private var scrubbing = false
    private var playing = false
    private var expanded = false

    private val hideControls = Runnable {
        controls.visibility = View.GONE
        main.removeCallbacks(tick)
    }
    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            main.postDelayed(this, TICK_MILLIS)
        }
    }

    init {
        visibility = View.GONE
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        playerView = PlayerView(context).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
        }
        controls = buildControls()
        panel = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            // Consume taps inside the panel (the video layer) so they re-show the controls instead of
            // falling through to CarPlay underneath; outside the panel touches still reach CarPlay.
            isClickable = true
            setOnClickListener { showControls() }
            addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        addView(panel)
        applyPanelMode(expanded = false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyPanelMode(expanded)
    }

    private fun applyPanelMode(expanded: Boolean) {
        this.expanded = expanded
        val params = if (expanded || width == 0) {
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        } else {
            LayoutParams((width * PANEL_WIDTH_FRACTION).roundToInt(), LayoutParams.MATCH_PARENT, Gravity.END)
        }
        panel.layoutParams = params
    }

    // ---- called by CarPlayVideo on the main thread ----

    fun showVideo(url: String, startMillis: Int, streaming: Boolean) {
        visibility = View.VISIBLE
        val exo = ensurePlayer()
        prepared = false
        exo.setMediaItem(MediaItem.Builder().setUri(url).build())
        if (startMillis > 0) exo.seekTo(startMillis.toLong())
        exo.prepare()
        exo.playWhenReady = true
        playing = true
        Log.i(TAG, "video shown streaming=$streaming start=$startMillis panel=$expanded")
        showControls()
    }

    fun hideVideo() {
        visibility = View.GONE
        controls.visibility = View.GONE
        main.removeCallbacks(tick)
        main.removeCallbacks(hideControls)
        player?.let {
            it.pause()
            it.stop()
            it.clearMediaItems()
        }
        prepared = false
        playing = false
    }

    fun applyRate(play: Boolean) {
        val exo = ensurePlayer()
        playing = play
        exo.playWhenReady = play
        if (play && visibility != View.VISIBLE) visibility = View.VISIBLE
        updatePlayPause()
    }

    fun applySeek(millis: Int) {
        player?.seekTo(millis.toLong())
    }

    fun playerState(): VideoInCar.PlayerState? {
        val exo = player ?: return null
        if (visibility != View.VISIBLE) return null
        val duration = exo.duration.takeIf { it > 0 && it != C.TIME_UNSET }?.div(1000.0) ?: 0.0
        val buffered = exo.bufferedPosition.coerceAtLeast(0).div(1000.0)
        return VideoInCar.PlayerState(
            prepared = prepared,
            playing = exo.isPlaying || (playing && prepared),
            positionSeconds = exo.currentPosition.coerceAtLeast(0).div(1000.0),
            durationSeconds = duration,
            bufferedSeconds = buffered,
        )
    }

    fun skip(millis: Int) {
        player?.let { it.seekTo((it.currentPosition + millis).coerceIn(0, it.duration.coerceAtLeast(0))) }
        showControls()
    }

    fun release() {
        main.removeCallbacks(tick)
        main.removeCallbacks(hideControls)
        player?.release()
        player = null
        prepared = false
    }

    // ---- internals ----

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val sources = IphoneResolvingDataSource.Factory(DefaultDataSource.Factory(context, http))
        val exo = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(sources)).build()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !prepared) {
                    prepared = true
                    Log.i(TAG, "prepared duration=${exo.duration} size=${exo.videoSize.width}x${exo.videoSize.height}")
                    updateTime()
                }
                if (state == Player.STATE_ENDED) CarPlayVideo.onPlayerClosed(null)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayPause()

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "player error ${error.errorCodeName}")
                CarPlayVideo.onPlayerFailed(
                    when (error.errorCode) {
                        in 2000..2999 -> VideoInCar.ERROR_NETWORK
                        in 4000..4999 -> VideoInCar.ERROR_DECODER
                        else -> VideoInCar.ERROR_INCOMPATIBLE_ASSET
                    },
                )
            }
        })
        playerView.player = exo
        player = exo
        return exo
    }

    private fun buildControls(): View {
        val back = TextView(context).apply {
            text = "‹  返回 CarPlay"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(6), dp(18), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xB3000000.toInt()) }
            setOnClickListener { closeByUser() }
        }
        sizeToggle = TextView(context).apply {
            text = "全屏"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(6), dp(18), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xB3000000.toInt()) }
            setOnClickListener {
                applyPanelMode(!expanded)
                sizeToggle.text = if (expanded) "小窗" else "全屏"
                showControls()
            }
        }
        fun round(icon: Int, label: String, onClick: () -> Unit) = ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            contentDescription = label
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x80000000.toInt()) }
            setOnClickListener { onClick(); showControls() }
        }
        playPause = round(Media3R.drawable.exo_icon_play, "播放/暂停") { CarPlayVideo.setPlaying(!playing) }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(round(Media3R.drawable.exo_icon_rewind, "快退") { skip(-CarPlayVideo.SKIP_MILLIS) },
                LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(playPause, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginStart = dp(32); marginEnd = dp(32) })
            addView(round(Media3R.drawable.exo_icon_fastforward, "快进") { skip(CarPlayVideo.SKIP_MILLIS) },
                LinearLayout.LayoutParams(dp(56), dp(56)))
        }
        fun timeText() = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            fontFeatureSettings = "tnum"
        }
        position = timeText()
        length = timeText()
        timeBar = SeekBar(context).apply {
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            secondaryProgressTintList = ColorStateList.valueOf(0x80FFFFFF.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0x4DFFFFFF)
            minimumHeight = dp(36)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) position.text = time(progress.toLong())
                }
                override fun onStartTrackingTouch(bar: SeekBar) { scrubbing = true; main.removeCallbacks(hideControls) }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    scrubbing = false
                    player?.seekTo(bar.progress.toLong())
                    showControls()
                }
            })
        }
        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(position)
            addView(timeBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(length)
        }
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xCC000000.toInt()))
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(back)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(sizeToggle)
        }
        return FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showControls() }
            addView(topRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                setMargins(dp(10), dp(10), dp(10), 0)
            })
            addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
    }

    private fun showControls() {
        if (visibility != View.VISIBLE) return
        controls.visibility = View.VISIBLE
        updateTime()
        main.removeCallbacks(hideControls)
        main.removeCallbacks(tick)
        main.post(tick)
        main.postDelayed(hideControls, CONTROLS_MILLIS)
    }

    private fun updatePlayPause() {
        playPause.setImageResource(if (playing) Media3R.drawable.exo_icon_pause else Media3R.drawable.exo_icon_play)
    }

    private fun updateTime() {
        val exo = player ?: return
        if (scrubbing) return
        val duration = exo.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
        timeBar.max = duration.toInt().coerceAtLeast(1)
        timeBar.progress = exo.currentPosition.toInt().coerceIn(0, timeBar.max)
        position.text = time(exo.currentPosition)
        length.text = time(duration)
    }

    private fun closeByUser() {
        CarPlayVideo.onPlayerClosed(player?.currentPosition?.toInt())
        hideVideo()
    }

    private fun time(millis: Long): String {
        if (millis <= 0) return "0:00"
        val total = millis / 1000
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "Hplay-Video"
        const val CONTROLS_MILLIS = 5_000L
        const val TICK_MILLIS = 500L
        const val PANEL_WIDTH_FRACTION = 0.30f
    }
}
