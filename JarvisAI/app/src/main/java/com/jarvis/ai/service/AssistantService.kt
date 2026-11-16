package com.jarvis.ai.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.ai.App
import com.jarvis.ai.R
import com.jarvis.ai.core.AccessWatchdog
import com.jarvis.ai.core.BackgroundWorker
import com.jarvis.ai.core.FamilyWakeWords
import com.jarvis.ai.core.LiveSession
import com.jarvis.ai.core.Prefs
import com.jarvis.ai.core.ResourceManager
import com.jarvis.ai.tools.GestureMapper
import com.jarvis.ai.ui.MainActivity
import com.jarvis.ai.ui.ScreenRecordActivity
import com.jarvis.ai.ui.OrbView
import com.jarvis.ai.service.DiscoLight

/**
 * The always-running brain of Jarvis.
 *  - HOTWORD mode: listens in the background for "Hey Jarvis" / any wake word (works from any app, screen on).
 *  - LIVE mode: real-time two-way voice with Gemini Live; mic stays open, no button presses needed.
 * A floating orb is shown over other apps while Jarvis is talking.
 */
class AssistantService : Service(), LiveSession.Listener {

    companion object {
        const val ACTION_HOTWORD = "jarvis.HOTWORD"
        const val ACTION_LIVE = "jarvis.LIVE"
        const val ACTION_STOP_LIVE = "jarvis.STOP_LIVE"
        const val ACTION_TOGGLE_LIVE = "jarvis.TOGGLE_LIVE"
        const val ACTION_SCREEN = "jarvis.SCREEN"
        // v1.3.1: screen video recording
        const val ACTION_RECORD = "jarvis.RECORD"
        const val ACTION_RECORD_GRANTED = "jarvis.RECORD_GRANTED"
        const val ACTION_STOP_RECORD = "jarvis.STOP_RECORD"
        // v1.3.2: multi-voice
        const val ACTION_VOICE = "jarvis.VOICE"
        const val ACTION_SHUTDOWN = "jarvis.SHUTDOWN"
        // v1.3.9: gesture control toggle (from Settings)
        const val ACTION_GESTURE = "jarvis.GESTURE"
        const val EXTRA_TEXT = "text"

        @Volatile var live: LiveSession? = null
        val uiListeners = mutableSetOf<LiveSession.Listener>()
        @Volatile var state: LiveSession.State = LiveSession.State.STOPPED

        fun send(ctx: Context, action: String, extras: Bundle? = null) {
            val i = Intent(ctx, AssistantService::class.java).setAction(action)
            extras?.let { i.putExtras(it) }
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }
        fun startHotwordIfEnabled(ctx: Context) { if (Prefs.wakeEnabled) send(ctx, ACTION_HOTWORD) }
        fun record(ctx: Context, seconds: Int = 0) = send(ctx, ACTION_RECORD, Bundle().apply { putInt("seconds", seconds) })
        fun stopRecord(ctx: Context) = send(ctx, ACTION_STOP_RECORD)
        fun changeVoice(ctx: Context, name: String) = send(ctx, ACTION_VOICE, Bundle().apply { putString("voice", name) })
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var hotwordOn = false
    private var lastWakeAt = 0L
    // v1.3.2: keep the screen on while Jarvis is talking or recording
    private var wakeLock: PowerManager.WakeLock? = null
    private var pendingText: String? = null
    private var overlay: OrbView? = null
    private var screenOn = false
    // v1.3.9: gesture control + background processing without screen visibility
    private var gestureInit = false
    private var partialLock: PowerManager.WakeLock? = null
    // v1.4.0: accessibility watchdog — check every 15 min, notify once per off-episode
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            AccessWatchdog.checkAndNotify(this@AssistantService)
            // v1.4.0: health tracking — capture the step baseline near midnight
            runCatching { com.jarvis.ai.tools.HealthTrack.maybeCaptureBaseline(this@AssistantService) }
            // v1.4.0: predictive notification (max 1/hour, only confident predictions)
            runCatching { com.jarvis.ai.core.ProactiveNotifier.maybeNotify(this@AssistantService) }
            // v1.4.0: Supabase pull check (no-op when not configured)
            runCatching { com.jarvis.ai.tools.SupabaseSync.maybePull(this@AssistantService) }
            // v1.4.0: owner backend heartbeat (no-op when URL set kora nei)
            runCatching { com.jarvis.ai.core.BackendClient.maybeHeartbeat(this@AssistantService) }
            main.postDelayed(this, 15 * 60 * 1000L)
        }
    }

    private fun ensureGestureAndResources() {
        if (gestureInit) return
        gestureInit = true
        // v1.4.0: start the accessibility watchdog (first check after 1 minute)
        main.postDelayed(watchdogRunnable, 60_000)
        // adaptive resource management: low battery / low memory adapts Jarvis automatically
        runCatching { ResourceManager.adapt(this) }
        if (Prefs.gestureShakeEnabled) {
            GestureControl.start(this) { if (!GestureMapper.run(this, "shake")) { if (live != null) stopLive() else startLive(null) } }
        }
        // seamless background processing: keep the CPU awake even with the screen off
        if (Prefs.backgroundWorkEnabled && partialLock == null) {
            partialLock = BackgroundWorker.holdPartial(this, "service")
        }
    }

    private fun applyGesturePref() {
        if (Prefs.gestureShakeEnabled) GestureControl.start(this) { if (!GestureMapper.run(this, "shake")) { if (live != null) stopLive() else startLive(null) } }
        else GestureControl.stop()
        if (Prefs.backgroundWorkEnabled && partialLock == null) partialLock = BackgroundWorker.holdPartial(this, "service")
        if (!Prefs.backgroundWorkEnabled) { runCatching { if (partialLock?.isHeld == true) partialLock?.release() }; partialLock = null }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        // v1.3.9: gesture control + adaptive resources + background processing
        ensureGestureAndResources()
        // v1.2: disco edge light while Jarvis runs in the background
        if (Prefs.discoEnabled) DiscoLight.start(this)
        when (intent?.action) {
            ACTION_GESTURE -> applyGesturePref()
            ACTION_HOTWORD, null -> if (live == null) startHotword()
            ACTION_LIVE -> startLive(intent.getStringExtra(EXTRA_TEXT))
            ACTION_TOGGLE_LIVE -> if (live != null) stopLive() else startLive(null)
            ACTION_STOP_LIVE -> stopLive()
            ACTION_SCREEN -> {
                val code = intent.getIntExtra("code", 0)
                @Suppress("DEPRECATION") val data = intent.getParcelableExtra<Intent>("data")
                if (data != null) { screenOn = true; goForeground(); ScreenCapture.start(this, code, data); live?.setScreenShare(true) }
            }
            // v1.3.1: screen video recording
            ACTION_RECORD -> {
                val seconds = intent.getIntExtra("seconds", 0)
                if (ScreenRecorder.recording) toast("Already recording the screen.")
                else if (ScreenCapture.active) { screenOn = true; goForeground(); startRecording(seconds) }
                else runCatching {
                    startActivity(Intent(this, ScreenRecordActivity::class.java).putExtra("seconds", seconds).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            ACTION_RECORD_GRANTED -> {
                val code = intent.getIntExtra("code", 0)
                @Suppress("DEPRECATION") val data = intent.getParcelableExtra<Intent>("data")
                if (data != null) {
                    screenOn = true; goForeground()
                    if (!ScreenCapture.active) ScreenCapture.start(this, code, data)
                    startRecording(intent.getIntExtra("seconds", 0))
                }
            }
            ACTION_STOP_RECORD -> stopRecording()
            ACTION_VOICE -> {
                val name = intent.getStringExtra("voice") ?: ""
                if (name.isNotBlank()) {
                    if (live != null) live?.changeVoice(name) else Prefs.voice = name
                    toast("Voice: $name")
                }
            }
            ACTION_SHUTDOWN -> { stopLive(); stopHotword(); stopSelf() }
        }
        return START_STICKY
    }

    // v1.3.1: screen video recording
    /** v1.3.2: hold a wake lock while live voice or screen recording is active. */
    private fun updateWakeLock() {
        val want = live != null || ScreenRecorder.recording
        if (want) {
            if (wakeLock?.isHeld != true) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "jarvis:work")
                    .apply { runCatching { acquire(30 * 60 * 1000L) } }
            }
        } else {
            runCatching { wakeLock?.release() }; wakeLock = null
        }
    }

    private fun startRecording(seconds: Int) {
        val msg = ScreenRecorder.start(this, seconds)
        if (msg == "NEED_PERMISSION") {
            runCatching { startActivity(Intent(this, ScreenRecordActivity::class.java).putExtra("seconds", seconds).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        toast(msg)
        updateWakeLock()
    }

    private fun stopRecording() {
        val path = ScreenRecorder.stop(this)
        updateWakeLock()
        toast(if (path != null) "Screen recording saved: $path" else "Screen recording stopped.")
    }

    private fun toast(msg: String) = main.post {
        runCatching { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun goForeground() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val talk = PendingIntent.getService(this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_TOGGLE_LIVE), PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            live != null -> "Listening… just talk (tap to stop)"
            hotwordOn -> "Say \"Hey ${Prefs.assistantName}\" anytime"
            else -> "Ready"
        }
        // v1.2.1: if the notification itself fails to build, fall back to a
        // minimal one instead of crashing the whole app process.
        val n: Notification = runCatching {
            NotificationCompat.Builder(this, App.CH_SERVICE)
                .setSmallIcon(R.drawable.ic_launcher_fg).setContentTitle(Prefs.assistantName + " AI").setContentText(text)
                .setOngoing(true).setContentIntent(open)
                .addAction(0, if (live != null) "Stop talking" else "Talk now", talk).build()
        }.getOrElse {
            runCatching {
                NotificationCompat.Builder(this, App.CH_SERVICE)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(Prefs.assistantName + " AI").setContentText("Ready").setOngoing(true).build()
            }.getOrNull() ?: return
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (screenOn) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(7, n, type)
            } else startForeground(7, n)
        } catch (e: Exception) {
            // Android 14 may block background mic start; keep the notification without mic type.
            runCatching { if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, 0) else startForeground(7, n) }
        }
    }

    // ------------------------------------------------------------------ hotword
    private fun wakeWords() = Prefs.wakeWords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() } +
        listOf(Prefs.assistantName.lowercase()) + FamilyWakeWords.allWords()

    private fun startHotword() {
        if (!Prefs.wakeEnabled || !SpeechRecognizer.isRecognitionAvailable(this)) return
        hotwordOn = true; goForeground()
        main.post { listenOnce() }
    }

    private fun stopHotword() {
        hotwordOn = false
        main.post { runCatching { recognizer?.destroy() }; recognizer = null }
    }

    private fun listenOnce() {
        if (!hotwordOn || live != null) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partial: Bundle?) { check(partial, false) }
                override fun onResults(results: Bundle?) { if (!check(results, true)) restart(100) }
                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                        runCatching { recognizer?.destroy() }; recognizer = null; restart(400)
                    } else restart(if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) 10000 else 120)
                }
            })
        }
        val lang = when (Prefs.language) { "bn" -> "bn-IN"; "hi" -> "hi-IN"; "en" -> "en-IN"; else -> "en-IN" }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("bn-IN", "hi-IN", "en-US"))
        runCatching { recognizer?.startListening(i) }.onFailure { restart(500) }
    }

    private fun restart(ms: Long) { main.removeCallbacksAndMessages(null); main.postDelayed({ listenOnce() }, ms) }

    /** Returns true if wake word detected. */
    private fun check(b: Bundle?, final: Boolean): Boolean {
        val texts = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        val words = wakeWords()
        for (t in texts) {
            val low = t.lowercase()
            val w = words.firstOrNull { low.contains(it) } ?: continue
            // v1.4.0: family wake word — remember who is talking
            FamilyWakeWords.matchPerson(low)?.let { Prefs.lastSpeaker = it }
            if (!final) { runCatching { recognizer?.stopListening() } ; return true.also { onWake(low.substringAfter(w).trim()) } }
            onWake(low.substringAfter(w).trim()); return true
        }
        return false
    }

    private fun onWake(rest: String) {
        if (live != null) return
        // v1.3: ignore repeat wakes within 3 seconds — the recognizer returns the
        // same partial + final results, which used to trigger Jarvis again and again.
        val now = System.currentTimeMillis()
        if (now - lastWakeAt < 3000) return
        lastWakeAt = now
        vibrate()
        startLive(rest.takeIf { it.length > 2 })
    }

    private fun vibrate() = runCatching {
        val v = getSystemService(Vibrator::class.java)
        v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ------------------------------------------------------------------ live
    private fun startLive(initial: String?) {
        // v1.4.0: owner backend block kore thakle live voice cholbe na
        if (com.jarvis.ai.core.BackendClient.isBlocked()) {
            runCatching { com.jarvis.ai.core.BackgroundWorker.notifyDone(this, "🚫 Jarvis bondho", com.jarvis.ai.core.BackendClient.blockedMessage()) }
            return
        }
        // v1.4.2: face check-e dhora poreche jini owner na — live voice cholbe na
        if (com.jarvis.ai.core.Prefs.faceBlocked) {
            runCatching { com.jarvis.ai.core.BackgroundWorker.notifyDone(this, "🔒 Jarvis bondho", "Face mileni — sudhu owner-i Jarvis use korte pare.") }
            return
        }
        if (live != null) { initial?.let { live?.sendText(it) }; return }
        // release the mic from the hotword recognizer
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.destroy() }; recognizer = null
        pendingText = initial
        // v1.4.0: show the overlay FIRST so Jarvis looks active instantly,
        // while the Live session (network) connects in the background.
        showOverlay()
        live = LiveSession(this, this).also { it.start() }
        goForeground()
        updateWakeLock()
    }

    private fun stopLive() {
        live?.stop(); live = null
        updateWakeLock()
        hideOverlay()
        goForeground()
        if (Prefs.wakeEnabled) { hotwordOn = true; restart(800) }
    }

    override fun onState(state: LiveSession.State) {
        AssistantService.state = state
        if (state == LiveSession.State.LISTENING) pendingText?.let { pendingText = null; live?.sendText(it) }
        main.post { overlay?.state = state; uiListeners.forEach { it.onState(state) } }
    }
    override fun onUserText(text: String) { main.post { uiListeners.forEach { it.onUserText(text) } } }
    override fun onModelText(text: String) { main.post { uiListeners.forEach { it.onModelText(text) } } }
    override fun onLevel(level: Float) { main.post { overlay?.level = level; uiListeners.forEach { it.onLevel(level) } } }
    override fun onError(msg: String) { main.post { uiListeners.forEach { it.onError(msg) } } }

    // ------------------------------------------------------------------ floating orb overlay
    private fun showOverlay() = main.post {
        if (overlay != null || !Settings.canDrawOverlays(this)) return@post
        val wm = getSystemService(WindowManager::class.java)
        val size = (resources.displayMetrics.density * 84).toInt()
        val lp = WindowManager.LayoutParams(size, size,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.END; lp.x = 24; lp.y = 260
        val v = OrbView(this).apply { compact = true }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 12) moved = true
                    lp.x = ox - dx.toInt(); lp.y = oy + dy.toInt(); runCatching { wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) stopLive()
            }
            true
        }
        runCatching { wm.addView(v, lp); overlay = v }
    }

    private fun hideOverlay() = main.post {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }; wakeLock = null
        main.removeCallbacks(watchdogRunnable)
        GestureControl.stop()
        runCatching { if (partialLock?.isHeld == true) partialLock?.release() }; partialLock = null
        DiscoLight.stop()
        live?.stop(); live = null; hideOverlay(); stopHotword(); ScreenCapture.stop()
        super.onDestroy()
    }
}
