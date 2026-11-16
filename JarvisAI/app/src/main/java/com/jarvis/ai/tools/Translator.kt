package com.jarvis.ai.tools

import com.jarvis.ai.core.GeminiChat

/**
 * v1.3.9 — Multilingual real-time translation. Uses the Gemini model Jarvis
 * already talks to — no extra API key, no extra app. The translation is a
 * one-shot call, so it comes back fast. Live voice translation works the same
 * way: just ask Jarvis in any language.
 */
object Translator {
    fun translate(text: String, target: String, cb: (String) -> Unit) {
        Thread {
            val res = GeminiChat.quick(
                "Translate the following text to $target. Reply with ONLY the translation, no explanation, no quotes:\n\n$text",
                "You are a precise, natural translator. Keep the tone of the original."
            )
            val out = res?.trim()?.takeIf { it.isNotBlank() }
                ?: "Translation holo na — internet ba Gemini API key check koro."
            android.os.Handler(android.os.Looper.getMainLooper()).post { cb(out) }
        }.start()
    }
}
