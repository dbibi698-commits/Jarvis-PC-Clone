package com.jarvis.ai.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.jarvis.ai.core.LiveSession
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

import com.jarvis.ai.core.Prefs

/** Animated sci-fi HUD orb (cyan + orange rings) that reacts to voice level and assistant state. */
class OrbView(ctx: Context) : View(ctx) {
    var compact = false
    var state: LiveSession.State = LiveSession.State.STOPPED
    var level = 0f
        set(v) { field = field * 0.6f + v * 0.4f }

    // v1.3.9: accent colour follows the theme (Settings > Personalize)
    private val cyan get() = Color.parseColor(Prefs.themeAccent)
    private val orange = Color.parseColor("#FF7A1A")
    private val violet = Color.parseColor("#A78BFA")
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; textSize = 22f; alpha = 150 }
    private var t = 0f
    private val rect = RectF()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        t += when (state) { LiveSession.State.THINKING -> 3.2f; LiveSession.State.SPEAKING -> 1.6f; LiveSession.State.STOPPED -> 0.3f; else -> 0.9f }
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f * 0.92f
        val main = when (state) { LiveSession.State.SPEAKING -> orange; LiveSession.State.THINKING -> violet; else -> cyan }
        val accent = if (state == LiveSession.State.SPEAKING) cyan else orange
        val dim = state == LiveSession.State.STOPPED
        val a = if (dim) 120 else 255
        val pulse = 1f + level * 0.25f

        // core glow
        val coreR = r * 0.30f * pulse
        fill.shader = RadialGradient(cx, cy, coreR * 1.8f, intArrayOf(Color.WHITE, main, Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        fill.alpha = a; c.drawCircle(cx, cy, coreR * 1.8f, fill); fill.shader = null

        // inner solid ring
        ring.color = main; ring.alpha = a; ring.strokeWidth = r * 0.025f
        c.drawCircle(cx, cy, r * 0.42f, ring)

        // rotating arcs
        arc(c, cx, cy, r * 0.55f, t * 1.2f, 110f, main, r * 0.045f, a)
        arc(c, cx, cy, r * 0.55f, t * 1.2f + 180f, 60f, main, r * 0.045f, a)
        arc(c, cx, cy, r * 0.66f, -t * 0.8f, 140f, accent, r * 0.03f, a)
        arc(c, cx, cy, r * 0.66f, -t * 0.8f + 200f, 40f, accent, r * 0.03f, a)

        // tick ring
        ring.color = main; ring.alpha = (a * 0.7f).toInt(); ring.strokeWidth = r * 0.05f
        ring.pathEffect = DashPathEffect(floatArrayOf(3f, 9f), t)
        c.drawCircle(cx, cy, r * 0.80f, ring); ring.pathEffect = null

        // outer thin ring + orange segments
        ring.strokeWidth = 2f; ring.alpha = (a * 0.5f).toInt(); c.drawCircle(cx, cy, r * 0.93f, ring)
        arc(c, cx, cy, r * 0.93f, t * 0.4f + 30f, 50f, accent, r * 0.02f, a)
        arc(c, cx, cy, r * 0.93f, t * 0.4f + 210f, 25f, accent, r * 0.02f, a)

        // audio bars around core
        if (!compact) {
            val bars = 48
            for (i in 0 until bars) {
                val ang = Math.toRadians((i * 360.0 / bars) + t)
                val amp = (level * 0.9f + 0.1f) * (0.5f + 0.5f * sin(i * 0.7 + t * 0.15).toFloat())
                val r1 = r * 0.45f; val r2 = r1 + r * 0.08f * amp
                ring.color = if (i % 6 == 0) accent else main; ring.alpha = a; ring.strokeWidth = 3f
                c.drawLine(cx + r1 * cos(ang).toFloat(), cy + r1 * sin(ang).toFloat(), cx + r2 * cos(ang).toFloat(), cy + r2 * sin(ang).toFloat(), ring)
            }
            text.textSize = r * 0.06f
            c.drawText(state.name, cx - text.measureText(state.name) / 2, cy + r * 0.72f, text)
        }
        postInvalidateOnAnimation()
    }

    private fun arc(c: Canvas, cx: Float, cy: Float, rr: Float, start: Float, sweep: Float, color: Int, w: Float, a: Int) {
        rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
        glow.color = color; glow.alpha = (a * 0.6f).toInt(); glow.strokeWidth = w * 1.8f
        c.drawArc(rect, start, sweep, false, glow)
        ring.color = color; ring.alpha = a; ring.strokeWidth = w
        c.drawArc(rect, start, sweep, false, ring)
    }
}
