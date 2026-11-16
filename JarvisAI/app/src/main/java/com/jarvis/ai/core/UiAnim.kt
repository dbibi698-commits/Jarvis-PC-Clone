package com.jarvis.ai.core

import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * v1.4.0 — Button animations.
 * press(): tap korle button-ta halka choto hoy (0.93x, 110ms), chere dile
 * bounce-back hoye 1.0x-e fire ase. OnTouchListener false ferot dey, tai
 * OnClickListener jemon chilo temon-i cholbe — animation click-e badha dey na.
 * fadeIn(): notun chat bubble niche theke halka uthe fade-in hoy.
 */
object UiAnim {
    fun press(v: View) {
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(110).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
            }
            false
        }
    }

    fun fadeIn(v: View) {
        v.alpha = 0f
        v.translationY = 14f
        v.animate().alpha(1f).translationY(0f).setDuration(260)
            .setInterpolator(DecelerateInterpolator()).start()
    }
}
