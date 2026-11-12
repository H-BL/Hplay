package com.hpplay.mirror.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hpplay.mirror.Prefs
import com.hpplay.mirror.realDisplaySize
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 车机悬浮窗：TYPE_APPLICATION_OVERLAY 承载镜像画面。
 * 支持单指拖动、双指缩放、右下角拖拽缩放、最小化/还原/关闭，位置与大小自动记忆。
 * 一旦用户手动调整过大小，重连时不再按视频比例自动改写（只保留用户设定）。
 * Surface 生命周期通过 [SurfaceListener] 通知上层，解码器无需重启。
 */
class OverlayWindowController(context: Context) {

    interface SurfaceListener {
        fun onSurfaceAvailable(surface: Surface)
        fun onSurfaceDestroyed(surface: Surface)
    }

    var surfaceListener: SurfaceListener? = null

    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private val screenSize = ctx.realDisplaySize()
    private val screenW = screenSize.first
    private val screenH = screenSize.second

    private var fullRoot: FrameLayout? = null
    private var miniView: TextView? = null
    private var surfaceView: SurfaceView? = null
    private var params: WindowManager.LayoutParams? = null
    private var miniParams: WindowManager.LayoutParams? = null
    private var titleBar: LinearLayout? = null
    private var resizeHandle: View? = null
    private var minimized = false

    private val hideRunnable = Runnable { hide() }
    private val controlsHideRunnable = Runnable { titleBar?.visibility = View.GONE }
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    val isShowing: Boolean get() = fullRoot != null || miniView != null

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceListener?.onSurfaceAvailable(holder.surface)
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            surfaceListener?.onSurfaceAvailable(holder.surface)
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceListener?.onSurfaceDestroyed(holder.surface)
        }
    }

    fun show() {
        mainHandler.post { _show() }
    }

    private fun _show() {
        if (isShowing) return
        if (!Settings.canDrawOverlays(ctx)) return
        minimized = prefs.getBoolean(Prefs.OVERLAY_MINIMIZED, Prefs.DEF_OVERLAY_MINIMIZED)
        if (minimized) _showMini() else _showFull()
    }

    fun hide() {
        mainHandler.post {
            cancelHide()
            _persistParams()
            _removeFull()
            _removeMini()
            prefs.edit().putBoolean(Prefs.OVERLAY_MINIMIZED, false).apply()
        }
    }

    fun dismiss() {
        mainHandler.post {
            cancelHide()
            _removeFull()
            _removeMini()
        }
    }

    fun scheduleHide(delayMs: Long) {
        mainHandler.post {
            cancelHide()
            mainHandler.postDelayed(hideRunnable, delayMs)
        }
    }

    fun cancelHide() {
        mainHandler.removeCallbacks(hideRunnable)
    }

    /**
     * 跟随 iPhone 当前横/竖屏：按视频源宽高比自动调整悬浮窗尺寸（不变形、无黑边）。
     * 但若用户已手动调整过大小，则完全尊重用户设定，不再改写尺寸。
     */
    fun setVideoSize(vw: Int, vh: Int) {
        mainHandler.post { _fitToVideo(vw, vh) }
    }

    private fun _fitToVideo(vw: Int, vh: Int) {
        val root = fullRoot ?: return
        val p = params ?: return
        if (vw <= 0 || vh <= 0) return
        // 用户手动定过大小 → 尊重用户，不改尺寸，只兜底把窗口拉回屏幕内。
        if (prefs.getBoolean(Prefs.OVERLAY_SIZE_USER_SET, Prefs.DEF_OVERLAY_SIZE_USER_SET)) {
            p.x = p.x.coerceIn(0, (screenW - p.width).coerceAtLeast(0))
            p.y = p.y.coerceIn(0, (screenH - p.height).coerceAtLeast(0))
            try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
            _persistParams()
            return
        }
        val aspect = vw.toFloat() / vh
        val area = p.width.toLong() * p.height
        var h = kotlin.math.sqrt(area.toDouble() / aspect).toInt()
        var w = (h * aspect).toInt()
        if (h > screenH) {
            h = screenH
            w = (h * aspect).toInt()
        }
        if (w > screenW) {
            w = screenW
            h = (w / aspect).toInt()
        }
        p.width = w.coerceIn(_minW(), screenW)
        p.height = h.coerceIn(_minH(), screenH)
        p.x = p.x.coerceIn(0, (screenW - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (screenH - p.height).coerceAtLeast(0))
        try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
        _persistParams()
    }

    private fun _persistParams() {
        val p = params ?: return
        prefs.edit()
            .putInt(Prefs.OVERLAY_X, p.x)
            .putInt(Prefs.OVERLAY_Y, p.y)
            .putInt(Prefs.OVERLAY_W, p.width)
            .putInt(Prefs.OVERLAY_H, p.height)
            .apply()
    }

    private fun _markSizeUserSet() {
        prefs.edit().putBoolean(Prefs.OVERLAY_SIZE_USER_SET, true).apply()
    }

    private fun _showControls() {
        val tb = titleBar ?: return
        tb.visibility = View.VISIBLE
        resizeHandle?.visibility = View.VISIBLE
        mainHandler.removeCallbacks(controlsHideRunnable)
        mainHandler.postDelayed(controlsHideRunnable, CONTROLS_TIMEOUT_MS)
    }

    private fun _hideControls() {
        titleBar?.visibility = View.GONE
        resizeHandle?.visibility = View.GONE
        mainHandler.removeCallbacks(controlsHideRunnable)
    }

    private fun _toggleControls() {
        val tb = titleBar ?: return
        if (tb.visibility == View.VISIBLE) {
            _hideControls()
        } else {
            _showControls()
        }
    }

    private fun _showFull() {
        val surfaceView = SurfaceView(ctx).apply {
            holder.addCallback(surfaceCallback)
        }

        val hint = TextView(ctx).apply {
            text = ctx.getString(com.hpplay.mirror.R.string.overlay_hint)
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(8, 0, 8, 0)
        }
        val minimizeBtn = _barButton(ctx.getString(com.hpplay.mirror.R.string.overlay_minimize)) {
            _minimize()
        }
        val closeBtn = _barButton(ctx.getString(com.hpplay.mirror.R.string.overlay_close)) {
            hide()
        }
        val titleBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x66000000.toInt())
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(hint, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(minimizeBtn)
            addView(closeBtn)
        }
        this.titleBar = titleBar

        // 右下角缩放手柄：可见即可拖，锚点为左上角。
        val handleSize = (34 * density).toInt()
        val resizeHandle = TextView(ctx).apply {
            text = "◢"
            textSize = 18f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.BOTTOM or Gravity.END
            visibility = View.GONE
            isClickable = true
            setPadding(0, 0, (4 * density).toInt(), 0)
        }
        this.resizeHandle = resizeHandle

        val root = FrameLayout(ctx).apply {
            setBackgroundColor(0xCC000000.toInt())
            addView(surfaceView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(titleBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (32 * density).toInt()
            ))
            addView(resizeHandle, FrameLayout.LayoutParams(handleSize, handleSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            })
            setOnTouchListener(_touchListener)
        }
        resizeHandle.setOnTouchListener(_resizeTouchListener)

        val savedW = prefs.getInt(Prefs.OVERLAY_W, Prefs.DEF_OVERLAY_W)
        val savedH = prefs.getInt(Prefs.OVERLAY_H, Prefs.DEF_OVERLAY_H)
        // 只有用户真正手动调过大小才沿用记忆尺寸；否则一律用更小巧的默认宽度（约屏宽 42%），
        // 这样老版本留下的过宽尺寸也会自动收敛到新默认。位置仍照常记忆。
        val sizeUserSet = prefs.getBoolean(Prefs.OVERLAY_SIZE_USER_SET, Prefs.DEF_OVERLAY_SIZE_USER_SET)
        val defaultW = (screenW * 5 / 12).coerceIn(_minW(), screenW)
        val w = if (sizeUserSet && savedW > 0) savedW.coerceIn(_minW(), screenW) else defaultW
        val h = if (sizeUserSet && savedH > 0) savedH.coerceIn(_minH(), screenH) else (w * 9 / 16).coerceIn(_minH(), screenH)
        val x = prefs.getInt(Prefs.OVERLAY_X, Prefs.DEF_OVERLAY_X).let {
            if (it >= 0) it.coerceIn(0, (screenW - w).coerceAtLeast(0)) else (screenW - w) / 2
        }
        val y = prefs.getInt(Prefs.OVERLAY_Y, Prefs.DEF_OVERLAY_Y).let {
            if (it >= 0) it.coerceIn(0, (screenH - h).coerceAtLeast(0)) else (screenH - h) / 3
        }
        val p = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        try {
            wm.addView(root, p)
            this.surfaceView = surfaceView
            fullRoot = root
            params = p
        } catch (e: Exception) {
            this.surfaceView = null
            fullRoot = null
            params = null
        }
    }

    private fun _showMini() {
        val mini = TextView(ctx).apply {
            text = "AirPlay ▸"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
        }
        val w = (130 * density).toInt()
        val h = (46 * density).toInt()
        val x = prefs.getInt(Prefs.OVERLAY_X, Prefs.DEF_OVERLAY_X).let {
            if (it >= 0) it.coerceIn(0, (screenW - w).coerceAtLeast(0)) else screenW - w - (16 * density).toInt()
        }
        val y = prefs.getInt(Prefs.OVERLAY_Y, Prefs.DEF_OVERLAY_Y).let {
            if (it >= 0) it.coerceIn(0, (screenH - h).coerceAtLeast(0)) else (screenH / 3)
        }
        val p = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        try {
            wm.addView(mini, p)
            miniView = mini
            miniParams = p
        } catch (_: Exception) {
            miniView = null
            miniParams = null
        }
        mini.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    scaling = false
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    startWinX = p.x
                    startWinY = p.y
                    tapDownX = event.x
                    tapDownY = event.y
                    tapMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        val dx = (event.rawX - startTouchX).toInt()
                        val dy = (event.rawY - startTouchY).toInt()
                        p.x = (startWinX + dx).coerceIn(0, (screenW - p.width).coerceAtLeast(0))
                        p.y = (startWinY + dy).coerceIn(0, (screenH - p.height).coerceAtLeast(0))
                        if (abs(event.x - tapDownX) > touchSlop || abs(event.y - tapDownY) > touchSlop) {
                            tapMoved = true
                        }
                        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    prefs.edit().putInt(Prefs.OVERLAY_X, p.x).putInt(Prefs.OVERLAY_Y, p.y).apply()
                    if (!tapMoved) _restore()
                }
            }
            true
        }
    }

    private fun _minimize() {
        val p = params ?: return
        prefs.edit()
            .putInt(Prefs.OVERLAY_X, p.x)
            .putInt(Prefs.OVERLAY_Y, p.y)
            .putInt(Prefs.OVERLAY_W, p.width)
            .putInt(Prefs.OVERLAY_H, p.height)
            .putBoolean(Prefs.OVERLAY_MINIMIZED, true)
            .apply()
        _removeFull()
        _showMini()
    }

    private fun _restore() {
        prefs.edit().putBoolean(Prefs.OVERLAY_MINIMIZED, false).apply()
        _removeMini()
        _showFull()
    }

    private fun _removeFull() {
        fullRoot?.let { root ->
            try { wm.removeView(root) } catch (_: Exception) {}
        }
        mainHandler.removeCallbacks(controlsHideRunnable)
        fullRoot = null
        surfaceView = null
        params = null
        titleBar = null
        resizeHandle = null
    }

    private fun _removeMini() {
        miniView?.let { v ->
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        miniView = null
        miniParams = null
    }

    private fun _barButton(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
        setOnClickListener { onClick() }
    }

    // ---- 拖动/缩放手势 ----
    private var dragging = false
    private var scaling = false
    private var resizing = false
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var startWinX = 0
    private var startWinY = 0
    private var startDist = 0f
    private var startWinW = 0
    private var startWinH = 0
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapMoved = false

    private val _touchListener = View.OnTouchListener { _, event ->
        val root = fullRoot
        val p = params
        if (root == null || p == null) return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount == 1) {
                    dragging = true
                    scaling = false
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    startWinX = p.x
                    startWinY = p.y
                    tapDownX = event.x
                    tapDownY = event.y
                    tapMoved = false
                    _showControls()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    dragging = false
                    scaling = true
                    startDist = _distance(event)
                    startWinW = p.width
                    startWinH = p.height
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && event.pointerCount == 1) {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    if (abs(event.x - tapDownX) > touchSlop || abs(event.y - tapDownY) > touchSlop) {
                        tapMoved = true
                    }
                    p.x = (startWinX + dx).coerceIn(0, (screenW - p.width).coerceAtLeast(0))
                    p.y = (startWinY + dy).coerceIn(0, (screenH - p.height).coerceAtLeast(0))
                    try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
                } else if (scaling && event.pointerCount >= 2 && startDist > 0f) {
                    val ratio = _distance(event) / startDist
                    val newW = (startWinW * ratio).toInt().coerceIn(_minW(), screenW)
                    val newH = (startWinH * ratio).toInt().coerceIn(_minH(), screenH)
                    p.width = newW
                    p.height = newH
                    p.x = p.x.coerceIn(0, (screenW - newW).coerceAtLeast(0))
                    p.y = p.y.coerceIn(0, (screenH - newH).coerceAtLeast(0))
                    try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2) {
                    if (scaling) _markSizeUserSet()
                    scaling = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scaling) _markSizeUserSet()
                dragging = false
                scaling = false
                _persistParams()
                if (!tapMoved && event.actionMasked == MotionEvent.ACTION_UP) {
                    _toggleControls()
                }
            }
        }
        true
    }

    /** 右下角手柄拖拽缩放：锚点左上角，向右下扩展。 */
    private val _resizeTouchListener = View.OnTouchListener { _, event ->
        val root = fullRoot
        val p = params
        if (root == null || p == null) return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizing = true
                startTouchX = event.rawX
                startTouchY = event.rawY
                startWinW = p.width
                startWinH = p.height
                startWinX = p.x
                startWinY = p.y
                mainHandler.removeCallbacks(controlsHideRunnable)
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizing) {
                    val dw = (event.rawX - startTouchX).toInt()
                    val dh = (event.rawY - startTouchY).toInt()
                    val maxW = (screenW - startWinX).coerceAtLeast(_minW())
                    val maxH = (screenH - startWinY).coerceAtLeast(_minH())
                    p.width = (startWinW + dw).coerceIn(_minW(), maxW)
                    p.height = (startWinH + dh).coerceIn(_minH(), maxH)
                    try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (resizing) {
                    resizing = false
                    _markSizeUserSet()
                    _persistParams()
                }
                mainHandler.removeCallbacks(controlsHideRunnable)
                mainHandler.postDelayed(controlsHideRunnable, CONTROLS_TIMEOUT_MS)
            }
        }
        true
    }

    private fun _distance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return hypot(dx, dy)
    }

    private fun _minW(): Int = (240 * density).toInt()
    private fun _minH(): Int = (135 * density).toInt()

    private companion object {
        const val CONTROLS_TIMEOUT_MS = 4_000L
    }
}
