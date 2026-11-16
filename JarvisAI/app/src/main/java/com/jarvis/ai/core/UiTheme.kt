package com.jarvis.ai.core

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

/**
 * v1.4.0 — Light / Dark mode + liquid-glass look.
 * MainActivity aar SettingsActivity code-e UI banay, tai rong-gulo ekhane
 * ek jaygay rakha — Prefs.themeMode ("dark" default / "light") bodle gelei
 * sob jayga bodle jay. Glass drawable code-e banano hoy (XML-e theme
 * bodlano jay na), tai helper hisebe GradientDrawable ferot dey.
 */
object UiTheme {
    fun isLight(): Boolean = Prefs.themeMode == "light"

    val bg: Int get() = if (isLight()) Color.parseColor("#EDF1F7") else Color.parseColor("#05070D")
    val text: Int get() = if (isLight()) Color.parseColor("#16233A") else Color.parseColor("#E6F1FF")
    val muted: Int get() = if (isLight()) Color.parseColor("#5A6B85") else Color.parseColor("#7D8BA6")
    val subText: Int get() = if (isLight()) Color.parseColor("#3D4E68") else Color.parseColor("#B8C7E0")
    val note: Int get() = if (isLight()) Color.parseColor("#5A6B85") else Color.parseColor("#9FB3D1")
    val chipText: Int get() = if (isLight()) Color.parseColor("#0B5FFF") else Color.parseColor("#BFEFFF")
    val warmChipText: Int get() = if (isLight()) Color.parseColor("#9A3412") else Color.parseColor("#FFD2B0")
    val warnText: Int get() = if (isLight()) Color.parseColor("#B45309") else Color.parseColor("#FFB27A")
    val aiText: Int get() = if (isLight()) Color.parseColor("#7C2D12") else Color.parseColor("#FFE9D6")

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /** Liquid glass — adha-sotoccho (translucent) fill + halka border + upore highlight. */
    private fun glass(ctx: Context, radiusDp: Int): GradientDrawable {
        val fill = if (isLight()) Color.parseColor("#D9FFFFFF") else Color.parseColor("#33FFFFFF")
        val hi = if (isLight()) Color.parseColor("#FFFFFFFF") else Color.parseColor("#59FFFFFF")
        val stroke = if (isLight()) Color.parseColor("#40000000") else Color.parseColor("#55FFFFFF")
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(hi, fill)).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setStroke(dp(ctx, 1), stroke)
        }
    }

    /** Choto pill chip / banner-er jonno glass. */
    fun glassChip(ctx: Context): Drawable = glass(ctx, 20)

    /** Input row / card-er jonno glass. */
    fun glassCard(ctx: Context): Drawable = glass(ctx, 16)

    /** Chat bubble — user: accent-tinted glass; AI: halka glass. */
    fun bubbleBg(ctx: Context, user: Boolean): Drawable {
        val d = glass(ctx, 18)
        if (user) {
            val accent = runCatching { Color.parseColor(Prefs.themeAccent) }.getOrDefault(Color.parseColor("#22D3EE"))
            (d as GradientDrawable).setColor(Color.argb(if (isLight()) 70 else 90, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
        return d
    }

    /**
     * v1.4.0: full anime-style animated background — slow aurora gradients
     * that drift across the screen (4s per frame, 2s crossfade). Dark mode:
     * deep navy / purple / teal; light mode: soft sky / mint / lavender.
     * Start it in onResume, stop it in onPause (battery bachate).
     */
    fun animatedBg(ctx: Context): android.graphics.drawable.AnimationDrawable {
        val frames = if (isLight()) listOf(
            intArrayOf(Color.parseColor("#EDF1F7"), Color.parseColor("#DCE8FF"), Color.parseColor("#E9DFFF")),
            intArrayOf(Color.parseColor("#EDF1F7"), Color.parseColor("#DFF5E9"), Color.parseColor("#DCE8FF")),
            intArrayOf(Color.parseColor("#EDF1F7"), Color.parseColor("#FFE9D6"), Color.parseColor("#E9DFFF"))
        ) else listOf(
            intArrayOf(Color.parseColor("#05070D"), Color.parseColor("#0B1030"), Color.parseColor("#1B0B2E")),
            intArrayOf(Color.parseColor("#05070D"), Color.parseColor("#06222B"), Color.parseColor("#0B1030")),
            intArrayOf(Color.parseColor("#05070D"), Color.parseColor("#2E0B1B"), Color.parseColor("#0B1030"))
        )
        return android.graphics.drawable.AnimationDrawable().apply {
            frames.forEach { colors ->
                addFrame(GradientDrawable(GradientDrawable.Orientation.TL_BR, colors), 4000)
            }
            isOneShot = false
            setEnterFadeDuration(2000)
            setExitFadeDuration(2000)
        }
    }
}
