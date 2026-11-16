package com.jarvis.ai.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.jarvis.ai.core.Memory
import com.jarvis.ai.core.Prefs
import java.util.Locale

/** Restart Jarvis after reboot / update so "Hey Jarvis" always works. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { AssistantService.startHotwordIfEnabled(ctx) }
}

/** Incoming calls: announce caller name, optionally auto-answer and let Jarvis talk on speaker. */
class CallReceiver : BroadcastReceiver() {
    companion object { private var tts: TextToSpeech? = null; private var handled = "" }

    @SuppressLint("MissingPermission")
    override fun onReceive(ctx: Context, intent: Intent) {
        val st = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: return // second broadcast has the number
        if (st != TelephonyManager.EXTRA_STATE_RINGING) { if (st == TelephonyManager.EXTRA_STATE_IDLE) handled = ""; return }
        if (handled == number) return
        handled = number
        val name = lookup(ctx, number) ?: number
        Memory.add("assistant", "Incoming call from $name", "call")
        val app = ctx.applicationContext

        if (Prefs.announceCalls) speak(app, "$name is calling. $name ফোন করছে।")

        if (Prefs.autoAnswerCalls) {
            Handler(Looper.getMainLooper()).postDelayed({
                // v1.3.2: answer by itself — system API first, accessibility tap as fallback.
                // acceptRingingCall() only works when Jarvis is the default dialer,
                // so on most phones the accessibility Answer-button tap is what answers.
                runCatching {
                    @Suppress("DEPRECATION") app.getSystemService(TelecomManager::class.java).acceptRingingCall()
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    val tm = app.getSystemService(TelephonyManager::class.java)
                    if (tm.callState == TelephonyManager.CALL_STATE_RINGING) {
                        val acc = JarvisAccessibilityService.instance
                        val answered = acc?.answerIncomingCall(false) == true
                        if (!answered && tm.callState == TelephonyManager.CALL_STATE_RINGING) {
                            acc?.answerIncomingCall(true) // slide-to-answer fallback
                        }
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        // only talk if the call is actually connected now
                        if (app.getSystemService(TelephonyManager::class.java).callState != TelephonyManager.CALL_STATE_OFFHOOK) return@postDelayed
                        speakerOn(app)
                        AssistantService.send(app, AssistantService.ACTION_LIVE, Bundle().apply {
                            putString(AssistantService.EXTRA_TEXT,
                                "[SYSTEM] You just answered a phone call from $name ($number) on speaker for the owner. " +
                                "Greet the caller with this greeting if set: '" + Prefs.callGreeting + "'. " +
                                "Ask how you can help, take a message, and use remember to save it. Use end_call when the conversation is over.")
                        })
                    }, 1200)
                }, 1500)
            }, 4000)
        }
    }

    private fun lookup(ctx: Context, number: String): String? = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun speakerOn(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
        else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = true }
    }

    private fun speak(ctx: Context, text: String) {
        if (tts == null) tts = TextToSpeech(ctx) { if (it == TextToSpeech.SUCCESS) { tts?.language = Locale("en", "IN"); tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "call") } }
        else tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "call")
    }
}
