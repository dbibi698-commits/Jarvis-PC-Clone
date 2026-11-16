package com.jarvis.ai.tools

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import com.jarvis.ai.core.Prefs
import com.jarvis.ai.service.JarvisAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * v1.4.2: WhatsApp call answer + greet.
 *
 * HONEST LIMITS (user-ke bolte hobe):
 * - Jarvis WhatsApp-er incoming-call screen-er Answer button-e tap korte
 *   pare (Accessibility diye) ar speaker ON kore greeting bolte pare — caller
 *   Jarvis-er awaj speaker theke mic diye shunte pay.
 * - Kintu Jarvis call-er bhitore ASOL participant hote pare na: Android
 *   kono app-ke onno app-er call-e audio dhokate dey na (WhatsApp-er mic
 *   call cholakalin sudhu WhatsApp-er). Tai purno dui-diker AI kotha
 *   WhatsApp call-e sombhob na — greeting + owner-ke daka porjonto.
 * - Phone lock thakle WhatsApp-er answer screen-e swipe lagte pare; tokhon
 *   auto-answer na-ao hote pare — tokhon nije uthate hobe.
 */
object WhatsAppCall {

    private var lastAutoAnswerAt = 0L
    private var tts: TextToSpeech? = null

    private fun speakerOn(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        }
    }

    private fun speakText(ctx: Context, text: String) {
        runCatching {
            if (tts == null) {
                tts = TextToSpeech(ctx.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale("bn", "IN")
                    }
                }
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wa-greet")
        }
    }

    private fun greet(ctx: Context) {
        speakText(ctx, "Hello, ami Jarvis. Ektu wait korun, ami owner ke dakchi.")
    }

    /** WhatsApp call-er End button-e tap kore call kata. */
    private fun endCall(acc: JarvisAccessibilityService): Boolean {
        for (c in listOf("End call", "End", "Hang up", "Decline", "কল শেষ")) {
            if (acc.clickText(c)) return true
        }
        return false
    }

    /** WhatsApp call dhoro + speaker ON + greeting bolo. Tool theke daka hoy. */
    suspend fun answerNow(ctx: Context, acc: JarvisAccessibilityService?): String {
        if (acc == null) return "Accessibility ON koro age — Settings > Permissions."
        val ok = acc.answerIncomingCall(false) || acc.answerIncomingCall(true)
        if (!ok) return "WhatsApp-er Answer button pelam na — call asle nije uthao, ba Accessibility ON ache kina dekho."
        delay(1200)
        speakerOn(ctx)
        // v1.4.3: Away Mode — call dhore away message bole kete dao (answering machine)
        if (Prefs.awayModeEnabled) {
            val msg = Prefs.awayMessage.ifBlank { "Owner ekhon barite nei, pore call koro. Dhonnobad!" }
            speakText(ctx, msg)
            delay((6000 + msg.length * 150L).coerceAtMost(20_000))
            val ended = endCall(acc)
            return if (ended) "✅ Away mode: WhatsApp call dhore janiye diyechi — \"$msg\" — ar call kete diyechi."
            else "✅ Away mode: WhatsApp call dhore janiye diyechi — \"$msg\" — kintu call katte pareni, nije kete dao."
        }
        greet(ctx)
        return "✅ WhatsApp call dhorechi, speaker ON, caller-ke greeting bolechi. Mone rekho: Jarvis call-er asol participant hote pare na (Android WhatsApp-er mic-e audio dhokate dey na) — tai greeting-er por owner-ke phone-ta nite hobe."
    }

    /** Accessibility event theke auto-answer: WhatsApp incoming call screen dekhlei Answer tap. */
    fun maybeAutoAnswer(acc: JarvisAccessibilityService) {
        if (!Prefs.autoAnswerWhatsapp) return
        val now = System.currentTimeMillis()
        if (now - lastAutoAnswerAt < 10_000) return
        val pkg = JarvisAccessibilityService.currentPackage
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        val t = acc.screenText()
        val incoming = t.contains("Incoming", true) || t.contains("WhatsApp call", true) ||
            t.contains("WhatsApp audio call", true) || t.contains("WhatsApp video call", true)
        if (!incoming) return
        lastAutoAnswerAt = now
        Thread { runBlocking { answerNow(acc, acc) } }.start()
    }
}
