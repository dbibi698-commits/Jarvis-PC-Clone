package com.jarvis.ai.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.ai.R
import com.jarvis.ai.core.AccessWatchdog
import com.jarvis.ai.core.AdaptiveLearning
import com.jarvis.ai.core.GeminiChat
import com.jarvis.ai.core.LiveSession
import com.jarvis.ai.core.Memory
import com.jarvis.ai.core.MoodDetector
import com.jarvis.ai.core.OfflineMode
import com.jarvis.ai.core.OwnerFace
import com.jarvis.ai.core.PassLock
import com.jarvis.ai.core.Prefs
import com.jarvis.ai.core.QuickAnswer
import com.jarvis.ai.core.UiAnim
import com.jarvis.ai.core.UiTheme
import com.jarvis.ai.core.CrashLog
import com.jarvis.ai.core.SessionContext
import com.jarvis.ai.core.VoiceCommands
import com.jarvis.ai.tools.ElevenLabsTts
import com.jarvis.ai.service.AssistantService
import com.jarvis.ai.service.JarvisAccessibilityService
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity(), LiveSession.Listener {

    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var caption: TextView
    // v1.4.1: owner-er announcement banner (backend theke asa)
    private lateinit var announceBar: TextView
    private lateinit var chat: LinearLayout
    private lateinit var suggestRow: LinearLayout

    companion object {
        /** App-entry lock state for this process lifetime. */
        @Volatile var unlocked = false
    }
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var banner: TextView
    private lateinit var liveBtn: TextView
    private lateinit var modeBtn: TextView
    private val gemini by lazy { GeminiChat(this) }
    private var tts: TextToSpeech? = null
    private var busy = false

    // v1.3.9: accent colour follows the theme (Settings > Personalize)
    private val cyan get() = Color.parseColor(Prefs.themeAccent)
    private val orange = Color.parseColor("#FF7A1A")
    private val muted get() = UiTheme.muted
    // v1.4.0: light/dark mode — je theme-e toiri hoyeche seta mone rakhi, bodle gele recreate()
    private var appliedTheme = Prefs.themeMode
    // v1.4.0: full anime animation — aurora background + orb breathing pulse
    private var bgAnim: android.graphics.drawable.AnimationDrawable? = null
    private var orbPulse: ObjectAnimator? = null
    // v1.4.2: sesh face check kokhon hoyeche (5 min-er beshi background-e thakle abar check)
    private var lastFaceCheckAt: Long = 0

    private fun startOrbPulse() {
        if (!::orb.isInitialized) return
        orbPulse?.cancel()
        orbPulse = ObjectAnimator.ofPropertyValuesHolder(orb,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.03f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.03f, 1f)).apply {
            duration = 2400; repeatCount = ValueAnimator.INFINITE; start()
        }
    }

    // v1.4.1: owner-er announcement thakle banner dekhay, na thakle lukay
    private fun refreshAnnouncement() {
        if (!::announceBar.isInitialized) return
        val a = Prefs.remoteAnnouncement.trim()
        if (a.isBlank()) { announceBar.visibility = android.view.View.GONE; return }
        announceBar.text = "📢 $a"
        announceBar.visibility = android.view.View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.breadcrumb("MainActivity.onCreate start")
        super.onCreate(savedInstanceState)
        CrashLog.breadcrumb("super.onCreate ok")
        try {
            setContentView(buildUi())
            CrashLog.breadcrumb("setContentView ok")
        } catch (e: Exception) {
            CrashLog.breadcrumb("setContentView FAILED: $e")
            // Fallback: minimal UI so the app at least opens and the crash log is saved.
            runCatching {
                setContentView(TextView(this).apply {
                    text = "Jarvis UI load holo na. Crash log Downloads/JarvisAI folder-e save hoyeche — ota share koro."
                    setTextColor(Color.parseColor("#E6F1FF")); textSize = 16f
                    setPadding(48, 120, 48, 48)
                })
            }
        }
        tts = TextToSpeech(this) {}
        Permissions.requestRuntime(this)
        // v1.2.1: hotword service starts only after the first frame AND only when
        // the mic permission is already granted — starting it in onCreate could
        // crash the very first launch before the user can grant anything.
        window.decorView.postDelayed({
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                AssistantService.startHotwordIfEnabled(this)
            }
        }, 800)
        loadRecent()
        handleIntent(intent)
        // v1.3.9: optional app-entry lock (passphrase / biometric) — only if the user enabled it
        checkAppLock()
        CrashLog.breadcrumb("MainActivity.onCreate done")
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_ASSIST || i?.action == Intent.ACTION_VOICE_COMMAND || i?.getBooleanExtra("live", false) == true)
            AssistantService.send(this, AssistantService.ACTION_LIVE)
    }

    override fun onResume() {
        super.onResume()
        // v1.4.0: Settings-e Light/Dark bodle thakle UI-ta abar toiri koro
        if (appliedTheme != Prefs.themeMode) { appliedTheme = Prefs.themeMode; recreate(); return }
        // v1.4.2: Owner Face Check — kholar somoy (ar 5 min-er beshi background-e thakle abar) mukh miliye dekho
        if (Prefs.ownerFaceCheckEnabled && OwnerFace.hasOwnerPhoto(this)) {
            val now = System.currentTimeMillis()
            if (now - lastFaceCheckAt > 5 * 60 * 1000L) {
                lastFaceCheckAt = now
                Prefs.faceBlocked = false
                status.text = "🔍 Face check hocche — camera-r dike takao…"
                OwnerFace.verify(this) { (isOwner, msg) ->
                    runOnUiThread {
                        if (isOwner) { status.text = msg; if (Prefs.faceBlocked) Prefs.faceBlocked = false }
                        else { Prefs.faceBlocked = true; status.text = msg; bubble(msg, false) }
                    }
                }
            } else if (Prefs.faceBlocked) {
                status.text = "🔒 Face mileni — sudhu owner-i Jarvis use korte pare."
            }
        }
        // v1.4.0: anime background + orb pulse chalu
        bgAnim?.start(); startOrbPulse()
        // v1.4.4: app khullei backend heartbeat — device sathe sathe dashboard-e asbe
        com.jarvis.ai.core.BackendClient.maybeHeartbeat(this)
        if (::announceBar.isInitialized) refreshAnnouncement()
        AssistantService.uiListeners.add(this)
        onState(AssistantService.state.takeIf { AssistantService.live != null } ?: LiveSession.State.STOPPED)
        refreshModeBtn()
        refreshBanner()
        refreshSuggestions()
        // v1.3.9: cross-session continuity — greet with what the user last talked about
        SessionContext.openingNote()?.let { caption.text = it }
        // v1.4.0: accessibility watchdog — update/battery kill-er por bondho hoye gele sathe sathe janano
        if (!JarvisAccessibilityService.isOn) {
            status.text = "⚠️ Accessibility OFF — tap kore ON koro"
            status.setOnClickListener { AccessWatchdog.openAccessibilitySettings(this) }
            AccessWatchdog.checkAndNotify(this)
        } else {
            AccessWatchdog.reset()
            status.setOnClickListener(null)
        }
    }

    // v1.3.9: smart suggestion chips, rebuilt from the user's habits each time
    private fun refreshSuggestions() {
        if (!::suggestRow.isInitialized) return
        suggestRow.removeAllViews()
        AdaptiveLearning.suggestions(this).forEach { q ->
            suggestRow.addView(TextView(this).apply {
                text = "✨ $q"; setTextColor(UiTheme.chipText); textSize = 12f; setPadding(dp(12), dp(7), dp(12), dp(7))
                background = UiTheme.glassChip(this@MainActivity); setOnClickListener { submit(q) }
                UiAnim.press(this)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
    }

    // v1.4.0: fingerprint/biometric lock puropuri tule deoa hoyeche — user-er
    // onurodhe (vivo-te BiometricPrompt-e app bondho hoye jachhilo). Ekhon sudhu
    // optional passphrase lock ache, OFF by default. Eta sudhu JARVIS app-ke
    // lock kore; phone-er lock screen-ke noy (kono app phone-er lock khulte pare na).
    private fun checkAppLock() {
        if (unlocked) return
        if (!Prefs.appLockEnabled) { unlocked = true; return }
        if (PassLock.isSet()) showPassphraseDialog()
        else unlocked = true
    }

    private fun showPassphraseDialog(tries: Int = 0) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Passphrase"
        }
        AlertDialog.Builder(this).setTitle("Jarvis locked").setMessage("App khulte tomar passphrase dao.")
            .setView(input).setCancelable(false)
            .setPositiveButton("Unlock") { _, _ ->
                if (PassLock.verify(input.text.toString())) unlocked = true
                else if (tries + 1 >= 3) { Toast.makeText(this, "3 bar bhul — app bondho korchi.", Toast.LENGTH_LONG).show(); finish() }
                else { Toast.makeText(this, "Bhulo passphrase — abar dao.", Toast.LENGTH_SHORT).show(); showPassphraseDialog(tries + 1) }
            }
            .setNegativeButton("Close") { _, _ -> finish() }
            .show()
    }

    // v1.3: show current mode (Normal / Girlfriend / Comedy) in the header chip
    private fun refreshModeBtn() {
        if (!::modeBtn.isInitialized) return
        val label = com.jarvis.ai.core.Persona.modeLabel()
        modeBtn.text = when (Prefs.mode) {
            "girlfriend" -> "💕 $label"
            "comedy" -> "😂 $label"
            else -> "🤖 $label"
        }
        modeBtn.setTextColor(if (Prefs.mode == "normal") cyan else orange)
    }

    override fun onPause() { super.onPause(); bgAnim?.stop(); orbPulse?.cancel(); AssistantService.uiListeners.remove(this) }
    override fun onDestroy() { SessionContext.saveSessionSummary(); tts?.shutdown(); super.onDestroy() }

    // ------------------------------------------------------------------ UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UiTheme.animatedBg(this@MainActivity).also { bgAnim = it }; setPadding(dp(14), dp(10), dp(14), dp(10)) }

        // header
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = Prefs.assistantName.uppercase().toCharArray().joinToString("."); setTextColor(cyan); textSize = 20f; typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.15f })
            status = TextView(context).apply { text = "OFFLINE"; setTextColor(muted); textSize = 11f; typeface = Typeface.MONOSPACE; letterSpacing = 0.2f }
            addView(status)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconBtn("HISTORY") { startActivity(Intent(this, HistoryActivity::class.java)) })
        header.addView(iconBtn("DASHBOARD") { startActivity(Intent(this, DashboardActivity::class.java)) })
        header.addView(iconBtn("SETTINGS") { startActivity(Intent(this, SettingsActivity::class.java)) })
        // v1.3: mode chip — tap to cycle Normal → Girlfriend → Comedy
        modeBtn = TextView(this).apply {
            textSize = 11f; typeface = Typeface.create("monospace", Typeface.BOLD)
            setPadding(dp(10), dp(10), dp(10), dp(10)); background = UiTheme.glassChip(this@MainActivity)
            UiAnim.press(this)
            setOnClickListener {
                Prefs.mode = com.jarvis.ai.core.Persona.nextMode()
                refreshModeBtn()
                android.widget.Toast.makeText(this@MainActivity, "Mode: ${com.jarvis.ai.core.Persona.modeLabel()}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        header.addView(modeBtn)
        root.addView(header)

        banner = TextView(this).apply {
            setTextColor(UiTheme.warnText); textSize = 13f; setPadding(dp(12), dp(8), dp(12), dp(8))
            background = UiTheme.glassChip(this@MainActivity); visibility = View.GONE
            UiAnim.press(this)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        root.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        orb = OrbView(this).apply { setOnClickListener { AssistantService.send(this@MainActivity, AssistantService.ACTION_TOGGLE_LIVE) } }
        // v1.3.9: gesture control — swipe UP on the orb to start talking, swipe DOWN to stop (tap still toggles)
        run {
            var flung = false
            val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                    val dy = e2.y - (e1?.y ?: 0f)
                    if (kotlin.math.abs(dy) > 120 && kotlin.math.abs(vy) > 200) {
                        flung = true
                        if (dy < 0) AssistantService.send(this@MainActivity, AssistantService.ACTION_LIVE)
                        else AssistantService.send(this@MainActivity, AssistantService.ACTION_STOP_LIVE)
                        return true
                    }
                    return false
                }
            })
            orb.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); val r = flung; flung = false; r }
        }
        root.addView(orb, LinearLayout.LayoutParams(-1, dp(250)))

        caption = TextView(this).apply { setTextColor(UiTheme.subText); textSize = 14f; gravity = Gravity.CENTER; text = "Tap the core or say \"Hey ${Prefs.assistantName}\""; maxLines = 3 }
        root.addView(caption, LinearLayout.LayoutParams(-1, -2))

        // v1.4.1: owner-er announcement (backend theke) — thakle dekhabe, na thakle lukano
        announceBar = TextView(this).apply {
            setTextColor(UiTheme.text); textSize = 13f; gravity = Gravity.CENTER; maxLines = 3
            background = UiTheme.glassChip(this@MainActivity); setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = android.view.View.GONE
        }
        root.addView(announceBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(2) })
        refreshAnnouncement()

        scroll = ScrollView(this)
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // quick actions
        val chips = LinearLayout(this)
        listOf("আমার স্ক্রিনে কী আছে?", "YouTube-এ গান চালাও", "WhatsApp খোলো", "Google-e news search koro", "Browser kholo", "Screen lock koro", "🎬 Screen record koro", "সকাল ৭টায় অ্যালার্ম", "একটা ওয়েবসাইট বানাও", "Battery কত?")
            .forEach { q -> chips.addView(TextView(this).apply {
                text = q; setTextColor(UiTheme.warmChipText); textSize = 12f; setPadding(dp(12), dp(7), dp(12), dp(7))
                background = UiTheme.glassChip(this@MainActivity); setOnClickListener { submit(q) }
                UiAnim.press(this)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // v1.3.9: smart suggestions — predicted from the user's own habits (AdaptiveLearning)
        suggestRow = LinearLayout(this)
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(suggestRow) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        refreshSuggestions()

        // input row
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = UiTheme.glassCard(this@MainActivity); setPadding(dp(14), dp(4), dp(6), dp(4)) }
        input = EditText(this).apply {
            hint = "বাংলা / English / हिन्दी তে লিখুন…"; background = null; setTextColor(UiTheme.text); textSize = 15f; maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine(false)
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(text.toString()); true } else false }
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(iconBtn("SCREEN") { startActivity(Intent(this, ScreenShareActivity::class.java)) })
        liveBtn = iconBtn("MIC") { AssistantService.send(this, AssistantService.ACTION_TOGGLE_LIVE) }
        row.addView(liveBtn)
        row.addView(iconBtn("SEND", orange) { submit(input.text.toString()) })
        root.addView(row, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun iconBtn(label: String, color: Int = cyan, click: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(color); textSize = 11f; typeface = Typeface.create("monospace", Typeface.BOLD)
        setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { click(); overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out) }
        UiAnim.press(this)
    }

    private fun bubble(text: String, user: Boolean, save: Boolean = false): TextView {
        val tv = TextView(this).apply {
            this.text = text; setTextColor(if (user) Color.WHITE else UiTheme.aiText); textSize = 15f
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = UiTheme.bubbleBg(this@MainActivity, user)
            setTextIsSelectable(true); movementMethod = LinkMovementMethod.getInstance()
        }
        chat.addView(tv, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (user) Gravity.END else Gravity.START; topMargin = dp(6)
            if (user) marginStart = dp(48) else marginEnd = dp(32)
        })
        // v1.4.0: notun bubble halka uthe fade-in hoy (purono history load-e noy)
        if (save) UiAnim.fadeIn(tv)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        if (save) Memory.add(if (user) "user" else "assistant", text, "chat")
        return tv
    }

    private fun loadRecent() = Memory.history().takeLast(12).forEach { bubble(it.text, it.role == "user") }

    private fun refreshBanner() {
        val missing = mutableListOf<String>()
        // v1.3: check the ACTIVE provider's key (Gemini / OpenAI / Claude)
        val keyMissing = when (Prefs.chatProvider) {
            "openai" -> Prefs.openaiKey.isBlank()
            "claude" -> Prefs.claudeKey.isBlank()
            else -> Prefs.apiKey.isBlank()
        }
        if (keyMissing) missing += "${Prefs.chatProvider.replaceFirstChar { it.uppercase() }} API key"
        if (!JarvisAccessibilityService.isOn) missing += "Accessibility (phone control)"
        if (!android.provider.Settings.canDrawOverlays(this)) missing += "Display over apps"
        banner.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        banner.text = "Setup needed: ${missing.joinToString(", ")} — tap here"
    }

    // ------------------------------------------------------------------ chat
    private fun submit(raw: String) {
        val q = raw.trim(); if (q.isEmpty() || busy) return
        // v1.4.0: owner backend block kore thakle Jarvis kaj korbe na — karon-ta janabe
        if (com.jarvis.ai.core.BackendClient.isBlocked()) {
            bubble(com.jarvis.ai.core.BackendClient.blockedMessage(), false); return
        }
        // v1.4.2: face check-e dhora poreche jini owner na — Jarvis kaj korbe na
        if (Prefs.faceBlocked) {
            bubble("🔒 Face mileni — sudhu owner-i Jarvis use korte pare. Tumi owner hole Settings > Owner Face Check theke notun photo dao.", false); return
        }
        Prefs.lastBackendAction = q.take(100)
        input.setText("")
        AssistantService.live?.let { bubble(q, true); it.sendText(q); return }
        // v1.3.5: learn from corrections — save them so Jarvis never repeats the mistake
        learnFromCorrection(q)
        // v1.3.9: real-time mood + nuance detection (Bengali + English) — Jarvis answers with matching warmth
        if (Prefs.moodDetectEnabled) {
            val mood = MoodDetector.detect(q)
            val nu = MoodDetector.nuance(q)
            Prefs.lastMood = mood.key
            Prefs.lastNuance = "urgent=${nu.urgent},question=${nu.isQuestion},grateful=${nu.grateful}"
            caption.text = "${mood.emoji} Mood: ${mood.label} — Tap the core or say \"Hey ${Prefs.assistantName}\""
        }
        // v1.3.9: dynamic personality adaptation — learn the user's interaction style
        AdaptiveLearning.recordMessageStyle(q)
        // v1.3.5 Turbo: instant local answers first (no network, no waiting)
        QuickAnswer.tryAnswer(this, q)?.let { ans ->
            bubble(q, true, save = true)
            bubble(ans, false, save = true)
            if (Prefs.speakChatReplies) speak(ans)
            return
        }
        // v1.3.9: instant voice-activated accessibility commands (scroll/back/home/tap) — no AI round-trip
        VoiceCommands.tryHandle(this, q)?.let { ans ->
            bubble(q, true, save = true)
            bubble(ans, false, save = true)
            if (Prefs.speakChatReplies) speak(ans)
            return
        }
        // v1.3.9: zero-latency local fast path — torch/lock/open app/call/volume/alarm run on the phone itself
        OfflineMode.handleOffline(this, q)?.let { ans ->
            bubble(q, true, save = true)
            bubble(ans, false, save = true)
            if (Prefs.speakChatReplies) speak(ans)
            return
        }
        // v1.3.9: offline mode — no internet means basic features only, said clearly
        if (Prefs.offlineMode || OfflineMode.isOffline(this)) {
            bubble(q, true, save = true)
            bubble("📵 Internet nei — offline mode-e ami sudhu basic kaj korte pari (time, battery, torch, lock, app khola, call, alarm, scroll/back). Net ele abar full Jarvis pabe.", false, save = true)
            return
        }
        bubble(q, true, save = true)
        busy = true
        val thinking = bubble("…", false)
        orb.state = LiveSession.State.THINKING; status.text = "THINKING"
        lifecycleScope.launch {
            val steps = mutableListOf<String>()
            val reply = gemini.send(q,
                onStep = { step -> runOnUiThread { steps += step; thinking.text = "⚙ " + steps.joinToString(" → ") } },
                onToken = { text -> runOnUiThread { if (steps.isEmpty()) thinking.text = text } })
            thinking.text = if (steps.isEmpty()) reply else "$reply\n\n⚙ ${steps.joinToString(" → ")}"
            Memory.add("assistant", reply, "chat")
            busy = false
            orb.state = LiveSession.State.STOPPED; status.text = "READY"
            if (Prefs.speakChatReplies) speak(reply)
        }
    }

    /** v1.3.5: when the user corrects Jarvis, remember it as a lasting lesson. */
    private fun learnFromCorrection(q: String) {
        val low = q.lowercase()
        val isCorrection = listOf("na, ", "na eta", "bhul", "eta na", "ota na", "wrong", "not that", "thik na", "eita na", "ami bolini").any { low.contains(it) }
        if (isCorrection) Memory.remember("User correction: $q")
    }

    private fun speak(text: String) {
        val clean = text.replace(Regex("```[\\s\\S]*?```"), " code saved. ").replace(Regex("[*#`_>]"), "").take(1200)
        // v1.3.8: ElevenLabs voice first (if API key + Voice ID are set), else phone TTS
        if (ElevenLabsTts.isConfigured()) {
            ElevenLabsTts.speak(this, clean) { ok ->
                if (!ok) runOnUiThread { speakTts(clean) }
            }
            return
        }
        speakTts(clean)
    }

    private fun speakTts(clean: String) {
        val loc = when {
            clean.any { it in '\u0980'..'\u09FF' } -> Locale("bn", "IN")
            clean.any { it in '\u0900'..'\u097F' } -> Locale("hi", "IN")
            else -> Locale("en", "IN")
        }
        tts?.language = loc
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    // ------------------------------------------------------------------ live callbacks
    override fun onState(state: LiveSession.State) {
        orb.state = state
        status.text = when (state) {
            LiveSession.State.STOPPED -> if (Prefs.wakeEnabled) "STANDBY — SAY \"HEY ${Prefs.assistantName.uppercase()}\"" else "READY"
            else -> state.name
        }
        status.setTextColor(if (state == LiveSession.State.STOPPED) muted else if (state == LiveSession.State.SPEAKING) orange else cyan)
        liveBtn.text = if (state == LiveSession.State.STOPPED) "MIC" else "STOP"
        liveBtn.setTextColor(if (state == LiveSession.State.STOPPED) cyan else orange)
    }
    override fun onUserText(text: String) { caption.text = "You: $text" }
    override fun onModelText(text: String) { caption.text = text }
    override fun onLevel(level: Float) { orb.level = level }
    override fun onError(msg: String) { caption.text = msg }
}
