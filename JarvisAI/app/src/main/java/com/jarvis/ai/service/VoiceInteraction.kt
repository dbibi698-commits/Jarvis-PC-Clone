package com.jarvis.ai.service

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

/**
 * Makes Jarvis selectable as the phone's "Digital assistant app"
 * (Settings > Apps > Default apps > Digital assistant app). Long-press Home / Power then opens Jarvis
 * instead of Google Assistant.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // The system keeps the assistant bound, so Jarvis' wake word can run even after the app is closed.
        AssistantService.startHotwordIfEnabled(this)
    }
}

class JarvisVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisVoiceSession(this)
}

class JarvisVoiceSession(private val svc: VoiceInteractionSessionService) : VoiceInteractionSession(svc) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Long-press home / assist gesture → start real-time voice immediately.
        AssistantService.send(context, AssistantService.ACTION_LIVE)
        hide()
    }
}

/** Minimal recognition service required for the digital-assistant role. */
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) }
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
