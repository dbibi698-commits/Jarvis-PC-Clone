package com.jarvis.ai.tools

import android.content.Context
import android.media.MediaPlayer
import com.jarvis.ai.core.GeminiChat
import com.jarvis.ai.core.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * ElevenLabs voice (v1.3.8) — Jarvis speaks with an ElevenLabs voice
 * (any voice ID from elevenlabs.io) instead of the phone's built-in TTS.
 * Needs the ElevenLabs API key + Voice ID in Settings > ElevenLabs Voice.
 * Free tier has a monthly character limit; without a key Jarvis falls
 * back to the phone's own TTS automatically.
 */
object ElevenLabsTts {
    @Volatile private var player: MediaPlayer? = null

    fun isConfigured(): Boolean = Prefs.elevenLabsKey.trim().isNotBlank() && Prefs.elevenLabsVoiceId.trim().isNotBlank()

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    /** Speak the text with ElevenLabs. Returns false when not configured (caller falls back to TTS). */
    fun speak(ctx: Context, text: String, cb: ((Boolean) -> Unit)? = null) {
        if (!isConfigured()) { cb?.invoke(false); return }
        Thread {
            val ok = runCatching {
                val body = JSONObject()
                    .put("text", text)
                    .put("model_id", "eleven_multilingual_v2")
                    .put("voice_settings", JSONObject().put("stability", 0.5).put("similarity_boost", 0.75))
                val req = Request.Builder()
                    .url("https://api.elevenlabs.io/v1/text-to-speech/${Prefs.elevenLabsVoiceId.trim()}")
                    .header("xi-api-key", Prefs.elevenLabsKey.trim())
                    .header("Accept", "audio/mpeg")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                GeminiChat.http.newCall(req).execute().use { r ->
                    if (r.code !in 200..299) return@runCatching false
                    val bytes = r.body!!.bytes()
                    val tmp = File(ctx.cacheDir, "eleven-${System.currentTimeMillis()}.mp3")
                    FileOutputStream(tmp).use { it.write(bytes) }
                    stop()
                    val mp = MediaPlayer()
                    player = mp
                    mp.setDataSource(tmp.absolutePath)
                    mp.setOnCompletionListener { stop(); tmp.delete() }
                    mp.setOnErrorListener { _, _, _ -> stop(); tmp.delete(); true }
                    mp.prepare()
                    mp.start()
                    true
                }
            }.getOrDefault(false)
            cb?.invoke(ok)
        }.start()
    }

    /** Test the voice with a short sentence. */
    fun test(ctx: Context, cb: (String) -> Unit) {
        if (!isConfigured()) { cb("ElevenLabs API key + Voice ID bosao age (Settings > ElevenLabs Voice)."); return }
        speak(ctx, "Hello, ami Jarvis. Eta amar notun voice.") { ok ->
            cb(if (ok) "✓ ElevenLabs voice bajche!" else "❌ ElevenLabs bolte parlo na — key/voice ID thik ache kina dekho.")
        }
    }
}
