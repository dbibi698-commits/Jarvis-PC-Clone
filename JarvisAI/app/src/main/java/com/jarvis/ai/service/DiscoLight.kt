package com.jarvis.ai.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import com.jarvis.ai.core.Prefs

/**
 * Disco edge light (v1.2, additive only).
 * While Jarvis is running in the background, a colourful animated light
 * runs around the edges of the display. It is a non-touchable overlay,
 * so it never blocks taps. Turn it off in Settings > Disco Light.
 * Needs "Display over other apps" permission.
 */
object DiscoLight {
    private var wm: WindowManager? = null
    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private class EdgeView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 14f }
        private var hue = 0f
        fun tick() { hue = (hue + 6f) % 360f; invalidate() }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val inset = 7f
            val r = RectF(inset, inset, width - inset, height - inset)
            // 4 edges, 4 colours — rainbow running around the screen
            val colors = floatArrayOf(hue, (hue + 90f) % 360f, (hue + 180f) % 360f, (hue + 270f) % 360f)
            paint.color = Color.HSVToColor(220, colors[0].let { floatArrayOf(it, 1f, 1f) })
            c.drawLine(r.left, r.top, r.right, r.top, paint)
            paint.color = Color.HSVToColor(220, floatArrayOf(colors[1], 1f, 1f))
            c.drawLine(r.right, r.top, r.right, r.bottom, paint)
            paint.color = Color.HSVToColor(220, floatArrayOf(colors[2], 1f, 1f))
            c.drawLine(r.right, r.bottom, r.left, r.bottom, paint)
            paint.color = Color.HSVToColor(220, floatArrayOf(colors[3], 1f, 1f))
            c.drawLine(r.left, r.bottom, r.left, r.top, paint)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            (view as? EdgeView)?.tick()
            handler.postDelayed(this, 90)
        }
    }

    /** Start the edge light. No-op when disabled in Settings or overlay permission is missing. */
    fun start(ctx: Context) {
        if (running || !Prefs.discoEnabled) return
        if (!Settings.canDrawOverlays(ctx)) return
        runCatching {
            val app = ctx.applicationContext
            wm = app.getSystemService(WindowManager::class.java)
            val v = EdgeView(app)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT)
            wm?.addView(v, lp)
            view = v; running = true
            handler.post(ticker)
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
        runCatching { view?.let { wm?.removeView(it) } }
        view = null; wm = null
    }

    fun isRunning(): Boolean = running
}
