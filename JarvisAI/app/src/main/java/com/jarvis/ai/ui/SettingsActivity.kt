package com.jarvis.ai.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.ai.R
import com.jarvis.ai.core.AccessWatchdog
import com.jarvis.ai.core.FamilyWakeWords
import com.jarvis.ai.core.GeminiChat
import com.jarvis.ai.core.Memory
import com.jarvis.ai.core.Persona
import com.jarvis.ai.core.Prefs
import com.jarvis.ai.core.UiAnim
import com.jarvis.ai.core.UiTheme
import com.jarvis.ai.core.PassLock
import com.jarvis.ai.core.ResourceManager
import com.jarvis.ai.core.SelfHeal
import com.jarvis.ai.service.AssistantService
import com.jarvis.ai.service.BackgroundUploadService
import com.jarvis.ai.service.DiscoLight
import com.jarvis.ai.service.LockHelper
import com.jarvis.ai.tools.AppFinder
import com.jarvis.ai.tools.Diagnostics
import com.jarvis.ai.tools.HealthTrack
import com.jarvis.ai.tools.HostServer
import com.jarvis.ai.tools.ModelUpdater
import com.jarvis.ai.tools.PublishHelper
import com.jarvis.ai.tools.SecurityScan
import com.jarvis.ai.tools.SyncManager
import com.jarvis.ai.tools.SupabaseSync
import com.jarvis.ai.tools.Troubleshooter
import androidx.activity.result.contract.ActivityResultContracts
import com.jarvis.ai.core.OwnerFace
import com.jarvis.ai.tools.GithubAuth
import com.jarvis.ai.tools.RenderDeploy
import com.jarvis.ai.tools.ElevenLabsTts
import com.jarvis.ai.tools.SmartHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {
    private val cyan = Color.parseColor("#22D3EE")
    private val orange = Color.parseColor("#FF7A1A")
    private lateinit var col: LinearLayout
    private lateinit var permBox: LinearLayout
    private lateinit var modeBtns: List<TextView>
    private lateinit var provBtns: List<TextView>
    private lateinit var ghStatus: TextView
    // v1.4.2: owner photo — gallery theke beche nile owner_face.jpg hisebe save hobe
    private val pickOwnerPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            val bytes = runCatching { contentResolver.openInputStream(uri)?.readBytes() }.getOrNull()
            runOnUiThread {
                if (bytes != null && OwnerFace.saveOwnerPhoto(this, bytes)) toast("✅ Owner photo save hoyeche — ekhon theke face check ei photo-r sathe milabe.")
                else toast("Photo porte pareni — abar try koro.")
            }
        }.start()
    }
    private lateinit var waStatus: TextView
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            if (Prefs.uploadUrl.isBlank()) { Toast.makeText(this, "Upload URL set koro age (niche), nahole YouTube share khulbe", Toast.LENGTH_LONG).show(); return@registerForActivityResult }
            BackgroundUploadService.startUpload(this, uri, "video_" + System.currentTimeMillis() + ".mp4")
            Toast.makeText(this, "Background upload started", Toast.LENGTH_SHORT).show()
        }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(40)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(UiTheme.bg); addView(col) })

        title("SETTINGS")

        // ---- v1.3: Mode — Normal / Girlfriend / Comedy ----
        section("Mode — Jarvis kemon bhabe kotha bolbe (header-e tap koreo bodlano jay)")
        val modeRow = LinearLayout(this)
        modeBtns = listOf("normal" to "🤖 Normal", "girlfriend" to "💕 Girlfriend", "comedy" to "😂 Comedy").map { (m, label) ->
            TextView(this).apply {
                text = label; textSize = 13f; typeface = Typeface.create("monospace", Typeface.BOLD)
                gravity = Gravity.CENTER; setPadding(dp(10), dp(12), dp(10), dp(12)); setBackgroundResource(R.drawable.chip_bg)
                setOnClickListener { Prefs.mode = m; refreshModeButtons(); toast("Mode: ${Persona.modeLabel()}") }
            }.also { modeRow.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(8), 0) }) }
        }
        col.addView(modeRow)
        col.addView(TextView(this).apply {
            text = "Girlfriend Mode: emotional, caring, 'jaan' bole dakbe. Comedy Mode: funny, jokes, hasi. Normal: smart assistant."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(6), 0, 0)
        })

        section("Gemini API Key")
        val key = field(Prefs.apiKey, "Paste your Gemini API key (AIza…)", password = true)
        row(button("Get free key ↗") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) },
            button("Test key") { test(key.text.toString()) })

        section("Chat model (text + phone control)")
        val chat = field(Prefs.chatModel, Prefs.DEFAULT_CHAT_MODEL)
        chips(Prefs.CHAT_MODELS) { chat.setText(it) }

        // ---- v1.3: AI API Keys — Gemini / OpenAI / Claude ----
        section("AI API Keys — kon AI diye kotha bolbe (phone control shudhu Gemini-te)")
        val provRow = LinearLayout(this)
        provBtns = listOf("gemini" to "Gemini", "openai" to "OpenAI", "claude" to "Claude").map { (p, label) ->
            TextView(this).apply {
                text = label; textSize = 13f; typeface = Typeface.create("monospace", Typeface.BOLD)
                gravity = Gravity.CENTER; setPadding(dp(10), dp(12), dp(10), dp(12)); setBackgroundResource(R.drawable.chip_bg)
                setOnClickListener { Prefs.chatProvider = p; refreshProvButtons(); toast("Chat engine: $label") }
            }.also { provRow.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(8), 0) }) }
        }
        col.addView(provRow)
        val openaiKeyF = field(Prefs.openaiKey, "OpenAI API key (sk-…)", password = true)
        row(button("Get OpenAI key ↗") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.openai.com/api-keys"))) },
            button("Test OpenAI") { testOpenAI(openaiKeyF.text.toString()) })
        val openaiModelF = field(Prefs.openaiModel, "gpt-4o-mini")
        val claudeKeyF = field(Prefs.claudeKey, "Claude API key (sk-ant-…)", password = true)
        row(button("Get Claude key ↗") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys"))) },
            button("Test Claude") { testClaude(claudeKeyF.text.toString()) })
        val claudeModelF = field(Prefs.claudeModel, "claude-3-5-sonnet-latest")

        section("Live voice model (real-time human-like voice)")
        val live = field(Prefs.liveModel, Prefs.DEFAULT_LIVE_MODEL)
        chips(Prefs.LIVE_MODELS) { live.setText(it) }

        section("Voice")
        val voice = field(Prefs.voice, "Puck")
        chips(Prefs.VOICES) { voice.setText(it) }

        section("Language  (auto = speaks whatever you speak)")
        val lang = field(Prefs.language, "auto")
        chips(listOf("auto", "bn", "en", "hi")) { lang.setText(it) }

        section("Assistant name")
        val name = field(Prefs.assistantName, "Jarvis")

        section("Wake words (comma separated, any language)")
        val wake = field(Prefs.wakeWords, "hey jarvis, জারভিস")

        // ---- v1.4.0: family wake words — each family member gets their own ----
        section("Family Wake Words — prottek-er nijer wake word (Jarvis bujhbe ke dakche)")
        val famWordF = field("", "Wake word — jemon: ammu dakche")
        val famPersonF = field("", "Ke? — jemon: Ammu")
        val famListTv = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, dp(4)) }
        col.addView(famListTv)
        famListTv.text = FamilyWakeWords.listText()
        row(button("Add family wake word") {
            val msg = FamilyWakeWords.add(famWordF.text.toString(), famPersonF.text.toString())
            famListTv.text = FamilyWakeWords.listText()
            toast(msg)
        }, button("Remove this wake word") {
            val msg = FamilyWakeWords.remove(famWordF.text.toString())
            famListTv.text = FamilyWakeWords.listText()
            toast(msg)
        })
        col.addView(TextView(this).apply {
            text = "\"ammu dakche\" bolle Jarvis jagbe ar Ammu bole dakbe. \"add_wake_word\" boleo add kora jay."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(6))
        })

        section("About you (Jarvis will remember)")
        val profile = field(Prefs.userProfile, "My name is …, I live in …, I like …")

        section("Auto-reply apps (package names)")
        val apps = field(Prefs.autoReplyApps, "com.whatsapp")

        section("Features")
        val swWake = switch("Always listen for wake word (\"Hey ${Prefs.assistantName}\")", Prefs.wakeEnabled)
        val swSpeak = switch("Speak chat replies aloud", Prefs.speakChatReplies)
        val swAuto = switch("Auto-reply to WhatsApp / Messenger messages with AI", Prefs.autoReply)
        val swAnnounce = switch("Announce incoming caller name", Prefs.announceCalls)
        val swAnswer = switch("Auto-answer calls and let Jarvis talk (speaker)", Prefs.autoAnswerCalls)
        val swAnswerWa = switch("Auto-answer WhatsApp calls and greet the caller", Prefs.autoAnswerWhatsapp)
        val swAway = switch("Away mode — ami na thakle call dhore janiye kete dao", Prefs.awayModeEnabled)
        val awayMsg = field(Prefs.awayMessage, "Away message — caller-ke ki bolbe?")
        col.addView(TextView(this).apply {
            text = "Away mode ON thakle WhatsApp call dhore ei message-ta bole call kete debe (answering machine-er moto). OFF thakle sudhu greeting bole owner-er jonno wait korbe. WhatsApp call: Jarvis Answer button-e tap kore speaker ON kore greeting bole. Kintu Jarvis call-er asol participant hote pare na — Android kono app-ke WhatsApp-er mic-e audio dhokate dey na. Greeting-er por owner-ke phone nite hobe."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })
        val swScreen = switch("Live voice can see my screen continuously", Prefs.liveScreenShare)

        // ---- v1.4.1: Owner monitoring (CONSENT) — user nije ON korle tobei data jabe ----
        section("Owner Monitoring — tomar permission (OFF by default)")
        val swMonitoring = switch("Owner-ke amar activity dekhte dao", Prefs.ownerMonitoringEnabled)
        col.addView(TextView(this).apply {
            text = "OFF thakle owner sudhu dekhte pay: ei phone-ta install koreche, app version, ar sesh ki korle. ON korle owner dekhte pay: ki ki tool use korle, kon permission diyecho, sesh chat history, ar request korle ekta screenshot / ekta camera photo / Jarvis-er workspace-er choto text file. Password, OTP, onno app-er data, location, live video — kokhono jay na. Jekono somoy OFF kore dite paro."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })

        // ---- v1.4.2: Owner Face Check — sudhu owner-i Jarvis khulte parbe ----
        section("Owner Face Check — sudhu owner (OFF by default)")
        val swFaceCheck = switch("Jarvis kholar age amar mukh miliye dekho", Prefs.ownerFaceCheckEnabled)
        val faceStatus = TextView(this).apply {
            text = if (OwnerFace.hasOwnerPhoto(this@SettingsActivity)) "✅ Owner-er photo save ache." else "⚠️ Owner-er photo set kora nei — age photo dao, nahole check cholbe na."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        }
        col.addView(faceStatus)
        col.addView(TextView(this).apply {
            text = "Jarvis kholar somoy front camera selfie tule owner-er photo-r sathe milabe (Gemini Vision, internet + Gemini key lagbe). Mile gele khulbe, na mille Jarvis bondho thakbe. Eta sudhu JARVIS APP-er check — phone-er lock na, onno app bondho hobe na. Alo kharap ba chehara bodle gele notun photo dao."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })
        row(button("📸 Owner-er photo tolo") {
            faceStatus.text = "Selfie tulchi — camera-r dike takao…"
            OwnerFace.captureSelfie(this) { bytes ->
                runOnUiThread {
                    if (bytes != null && OwnerFace.saveOwnerPhoto(this, bytes)) { faceStatus.text = "✅ Owner-er photo save ache."; toast("✅ Owner photo save hoyeche.") }
                    else { faceStatus.text = "⚠️ Photo tulte pareni — camera permission dao."; toast("Camera khulte pareni — permission dao.") }
                }
            }
        }, button("🖼️ Gallery theke nao") { pickOwnerPhoto.launch("image/*") })
        row(button("🔍 Ekhon-i face check koro") {
            faceStatus.text = "Face check hocche…"
            OwnerFace.verify(this) { (isOwner, msg) ->
                runOnUiThread { faceStatus.text = msg; toast(msg) }
            }
        }, button("🗑️ Owner photo muche dao") {
            OwnerFace.deleteOwnerPhoto(this); faceStatus.text = "⚠️ Owner-er photo set kora nei — age photo dao, nahole check cholbe na."; toast("Owner photo muche deoa hoyeche.")
        })

        // ---- v1.2: Screen Lock (Device Admin) — lock control only, no app authentication ----
        // (v1.3.6: face / fingerprint / password authentication deleted — it was causing problems.
        //  Jarvis now opens directly, no lock. Screen lock/wake via Device Admin still works.)
        section("Screen Lock (Device Admin)")
        col.addView(TextView(this).apply {
            text = "Device Admin diye sudhu SCREEN LOCK kora jay — phone-er PIN / password / fingerprint kono app bypass korte pare na. Jarvis khulte kono lock nai."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(6))
        })
        row(button("Enable Device Admin") { startActivity(LockHelper.enableIntent(this)) })
        row(button("Lock Screen Now") {
            if (LockHelper.lockNow(this)) toast("Screen locked") else toast("Age Device Admin enable koro")
        }, button("Wake Screen") { LockHelper.wakeScreen(this); toast("Screen woken — phone-er lock thakle nijer PIN/fingerprint diye kholo") })
        col.addView(TextView(this).apply {
            text = "Device Admin: " + if (LockHelper.isAdmin(this@SettingsActivity)) "✓ ON" else "○ OFF (tap Enable Device Admin)"
            setTextColor(if (LockHelper.isAdmin(this@SettingsActivity)) Color.parseColor("#4ADE80") else UiTheme.warmChipText); textSize = 14f; setPadding(0, dp(6), 0, 0)
        })

        section("Disco Light — background-e cholle display-er dhare dhare light")
        val swDisco = switch("Disco edge light ON (background running-er somoy)", Prefs.discoEnabled)
        col.addView(TextView(this).apply {
            text = "Note: Disco light-er jonno 'Display over other apps' permission lagbe (niche Permissions-e ✓ koro)."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, 0)
        })

        section("Call greeting (auto-answer-e Jarvis ja bolbe)")
        val greeting = field(Prefs.callGreeting, "Hello, this is Jarvis…")
        section("WhatsApp auto-reply template (khali rakhle AI reply debe, {name} {message} use kora jay)")
        val replyTpl = field(Prefs.autoReplyTemplate, "Hi {name}, ami ekhon busy, pore reply dicchi…")
        val swBgWork = switch("Background-e kaj koro (call / WhatsApp / upload)", Prefs.backgroundWorkEnabled)
        val swProactive = switch("Predictive notification (Jarvis tor obhyash dekhe mone koriye debe, ghontay 1 bar-er beshi na)", Prefs.proactiveNotifEnabled)

        section("Hosting / Publish (command-e: \"publish index.html\" / \"host website\")")
        val ghToken = field(Prefs.githubToken, "GitHub token (ghp_…)", password = true)
        val ghRepo = field(Prefs.githubRepo, "owner/repo  jemon: rahim/my-site")
        val ghBranch = field(Prefs.githubBranch, "main")
        val upUrl = field(Prefs.uploadUrl, "Background video upload URL (POST multipart, field=file)")
        row(button("Start Hosting") {
            Prefs.githubToken = ghToken.text.toString(); Prefs.githubRepo = ghRepo.text.toString()
            Toast.makeText(this, "Hosting: " + HostServer.start(this, java.io.File(getExternalFilesDir(null), "workspace").apply { mkdirs() }), Toast.LENGTH_LONG).show()
        }, button("Stop Hosting") { HostServer.stop(); toast("Hosting stopped") })
        row(button("Pick & Upload Video (background)") { pickVideo.launch("video/*") },
            button("Test Publish") {
                Prefs.githubToken = ghToken.text.toString(); Prefs.githubRepo = ghRepo.text.toString(); Prefs.githubBranch = ghBranch.text.toString()
                PublishHelper.publishFile(this, "jarvis-test.txt", "Jarvis publish test " + System.currentTimeMillis()) { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            })

        // ---- v1.3.8: ElevenLabs Voice ----
        section("ElevenLabs Voice (Jarvis-er AI voice)")
        col.addView(TextView(this).apply {
            text = "ElevenLabs-er voice-e Jarvis kotha bolbe. elevenlabs.io theke API key ar Voice ID nao (Voices → voice-ta khule ID copy koro). Key na thakle phone-er nijer voice-e bolbe."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })
        val elKeyF = field(Prefs.elevenLabsKey, "ElevenLabs API key (sk_…)", password = true)
        val elVoiceF = field(Prefs.elevenLabsVoiceId, "Voice ID")
        row(button("Test voice") {
            Prefs.elevenLabsKey = elKeyF.text.toString(); Prefs.elevenLabsVoiceId = elVoiceF.text.toString()
            ElevenLabsTts.test(this) { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }, button("Get API key ↗") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://elevenlabs.io/app/settings/api-keys"))) })

        // ---- v1.3.8: Smart Home ----
        section("Smart Home (Home Assistant)")
        col.addView(TextView(this).apply {
            text = "Home Assistant thakle Jarvis light/fan/AC/switch control korte parbe. URL: http://homeassistant.local:8123 (ba tomar HA-er IP). Token: Home Assistant → Profile → Long-Lived Access Tokens theke banao. Na thakle Google Home app khule debe."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })
        val haUrlF = field(Prefs.homeAssistantUrl, "Home Assistant URL (http://homeassistant.local:8123)")
        val haTokenF = field(Prefs.homeAssistantToken, "Long-Lived Access Token", password = true)
        row(button("Test connection") {
            Prefs.homeAssistantUrl = haUrlF.text.toString(); Prefs.homeAssistantToken = haTokenF.text.toString()
            SmartHome.test { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }, button("Open Google Home") {
            val li = packageManager.getLaunchIntentForPackage("com.google.android.apps.chromecast.app")
            if (li != null) startActivity(li) else toast("Google Home app pelam na")
        })

        // ---- v1.3.7: Website Login ----
        section("Website Login (jekono site-e login / account)")
        col.addView(TextView(this).apply {
            text = "Jarvis jekono website-e login korte pare ar account khulte pare — \"Facebook-e login koro\" ba \"Instagram-e account kholo\" bollei hobe. Email ekhane bosale protibar ar chaibe na. Password chat-e dio na — login-er somoy Jarvis chaibe, ekbar use kore bhule jabe. OTP ele code-ta dao. Taka / delete / password change-er age Jarvis confirm chabe."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })
        val emailF = field(Prefs.defaultEmail, "Tomar email (login-er jonno)")

        // ---- v1.3.6: Photo / Video / Build ----
        section("Photo / Video / Build (CapCut, background remove, APK build)")
        col.addView(TextView(this).apply {
            text = "Video edit: CapCut / YouCut install thakle \"edit video\" bollei video oi app-e khulbe. Background remove: remove.bg API key bosale Jarvis nijei korbe (remove.bg → API Keys, free 50/month), na hole browser-e khule debe. APK build: GitHub repo + token lagbe (Connect section-e) — GitHub Actions free-te Gradle diye APK baniye Downloads-e debe; phone-e Gradle chole na."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })
        val rmBgF = field(Prefs.removeBgKey, "remove.bg API key", password = true)
        row(button("Save remove.bg key") { Prefs.removeBgKey = rmBgF.text.toString(); toast("remove.bg key saved") },
            button("Get remove.bg key ↗") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.remove.bg/api"))) })

        // ---- v1.3.4: Render deploy ----
        section("Render Deploy (command-e: \"render deploy koro\" / \"render status\")")
        col.addView(TextView(this).apply {
            text = "API key: dashboard.render.com → Account Settings → API Keys (rnd_… diye shuru). Service ID: service-er page-er URL-e thake (srv-… diye shuru). Na hole Deploy Hook URL bosao (service → Settings → Deploy Hook) — tahole key lagbe na. Token chat-e dio na, ekhanei bosao."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })
        val renderKeyF = field(Prefs.renderApiKey, "Render API key (rnd_…)", password = true)
        val renderSvcF = field(Prefs.renderServiceId, "Render Service ID (srv-…)")
        val renderHookF = field(Prefs.renderDeployHook, "Deploy Hook URL (optional)")
        row(button("Deploy now") {
            Prefs.renderApiKey = renderKeyF.text.toString(); Prefs.renderServiceId = renderSvcF.text.toString(); Prefs.renderDeployHook = renderHookF.text.toString()
            RenderDeploy.deploy { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }, button("Check status") {
            Prefs.renderApiKey = renderKeyF.text.toString(); Prefs.renderServiceId = renderSvcF.text.toString()
            RenderDeploy.status { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        })
        row(button("Open Render dashboard ↗") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dashboard.render.com"))) },
            button("Get API key ↗") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dashboard.render.com/account/api-keys"))) })

        // ---- v1.3: Connect — WhatsApp / Instagram / GitHub / Browser / Camera ----
        section("Connect — WhatsApp / Instagram / GitHub / Browser / Camera")
        waStatus = TextView(this).apply { textSize = 14f; setPadding(0, dp(4), 0, dp(4)) }
        col.addView(waStatus)
        row(button("WhatsApp connect") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            button("Instagram connect") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        col.addView(TextView(this).apply {
            text = "WhatsApp / Instagram connect hoy notification access diye — uporer button-e giye Jarvis-ke allow koro, tarpor Features-e Auto-reply ON koro."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(6))
        })
        // ---- v1.3.9: one-click Facebook connect — no API, no key, just one click ----
        row(button("Connect Facebook (one click)") {
            startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", "https://www.facebook.com/login"))
        }, button("Open Facebook app") {
            val app = AppFinder.find(this, "Facebook")
            if (app != null) AppFinder.launch(this, app.pkg) else toast("Facebook app install kora nei")
        })
        col.addView(TextView(this).apply {
            text = "One click-e Facebook login page khulbe — email/password diye login koro, kono API ba key lagbe na. Login thakle Jarvis Facebook-e post/comment korte parbe (browser diye). \"Facebook connect koro\" bollei hobe."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(6))
        })
        ghStatus = TextView(this).apply { textSize = 14f; setPadding(0, dp(4), 0, dp(4)) }
        col.addView(ghStatus)
        row(button("Connect GitHub") {
            Prefs.githubToken = ghToken.text.toString(); Prefs.githubRepo = ghRepo.text.toString(); Prefs.githubBranch = ghBranch.text.toString().ifBlank { "main" }
            ghStatus.text = "Connecting GitHub…"
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) { validateGithub(ghToken.text.toString().trim(), ghRepo.text.toString().trim()) }
                ghStatus.text = msg; ghStatus.setTextColor(if (msg.startsWith("✓")) Color.parseColor("#4ADE80") else UiTheme.warmChipText)
                toast(msg)
            }
        }, button("Open GitHub ↗") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com"))) })
        // ---- v1.3.2: one-click GitHub authorize (OAuth device flow, no token copy-paste) ----
        col.addView(TextView(this).apply {
            text = "One-click authorize (token copy-paste lagbe na): GitHub-e ekta free OAuth App baniye tar Client ID ekhane bosao — github.com/settings/developers → New OAuth App → Homepage URL: https://github.com → Client ID copy koro. Oi page-ei 'Enable Device Flow' ON korte hobe, na hole code asbe na. Tarpor Authorize GitHub chaple code dekhabe — github.com/device-e giye code-ta bosalei connect hoye jabe. Ei page-ta khula rekho jotokkhon na authorize hoy."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(6), 0, dp(4))
        })
        val ghClientF = field(Prefs.githubClientId, "GitHub OAuth App Client ID")
        val ghCode = TextView(this).apply {
            textSize = 20f; typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.CENTER
            setTextColor(orange); setPadding(0, dp(8), 0, dp(8)); visibility = View.GONE
        }
        col.addView(ghCode)
        row(button("Authorize GitHub (one click)") {
            val cid = ghClientF.text.toString().trim()
            if (cid.isBlank()) { toast("Age GitHub OAuth App-er Client ID bosao (upore lekha ache kivabe)"); return@button }
            Prefs.githubClientId = cid
            ghStatus.text = "Code anchi…"
            lifecycleScope.launch {
                val (dc, err) = withContext(Dispatchers.IO) { GithubAuth.requestDeviceCode(cid) }
                if (dc == null) { ghStatus.text = "❌ ${err ?: "Code pelam na"}"; toast(err ?: "Code pelam na"); return@launch }
                ghCode.text = "Code: ${dc.userCode}"
                ghCode.visibility = View.VISIBLE
                ghStatus.text = "Waiting… github.com/device-e code ${dc.userCode} bosao (15 min-er modhye)"
                val token = withContext(Dispatchers.IO) { GithubAuth.pollForToken(cid, dc.deviceCode, dc.interval, dc.expiresIn) }
                if (token == null) { ghStatus.text = "❌ Authorize hoy ni (code expire hoyeche ba decline korecho) — abar try koro"; return@launch }
                val login = withContext(Dispatchers.IO) { GithubAuth.finishWithToken(token) }
                if (login != null) {
                    ghStatus.text = "✓ GitHub connected as $login (one-click authorize) — ebar deploy/publish cholbe"
                    ghStatus.setTextColor(Color.parseColor("#4ADE80"))
                    ghCode.visibility = View.GONE
                    toast("GitHub connected as $login")
                } else {
                    ghStatus.text = "❌ Token pelam kintu user pai ni — abar try koro"
                }
                refreshConnect()
            }
        }, button("Open github.com/device ↗") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/device"))) })
        row(button("Browser kholo") { startActivity(Intent(this, BrowserActivity::class.java)) },
            button("Camera kholo") {
                runCatching { startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }.onFailure { toast("Camera khola jayni") }
            })

        // ---- v1.3.9: Gesture Control & Mood ----
        section("Gesture Control & Mood (shake, swipe, mood detect)")
        val swShake = switch("Shake phone to wake Jarvis (phone jhakalei Jarvis jagbe)", Prefs.gestureShakeEnabled)
        val swMood = switch("Real-time mood detection (Jarvis tomar mood bujhe uttor debe)", Prefs.moodDetectEnabled)
        col.addView(TextView(this).apply {
            text = "Orb-e swipe UP korle kotha shuru, swipe DOWN korle thambe. Shake korle live voice toggle hobe. Mood detect on thakle Jarvis tomar kotha theke mood bujhbe (khushi / mon kharap / klanto) ar sei moto uttor debe."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })

        // ---- v1.3.9: Offline Mode ----
        section("Offline Mode (internet charao basic features)")
        val swOffline = switch("Offline mode force koro (internet thakleo sudhu local kaj)", Prefs.offlineMode)
        col.addView(TextView(this).apply {
            text = "Internet na thakle Jarvis nijei offline hoye jay — time, battery, torch, lock, app khola, call, alarm, scroll/back sob offline-e cholbe. Eta on korle sobsomoy offline thakbe."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })

        // ---- v1.3.9: Personalize (theme) ----
        section("Personalize — Theme (Jarvis-er rong)")
        row(*listOf("#22D3EE" to "Cyan", "#FF7A1A" to "Orange", "#EC4899" to "Pink", "#22C55E" to "Green", "#A78BFA" to "Purple").map { (hex, label) ->
            button(label) { Prefs.themeAccent = hex; toast("$label theme set — orb-er rong ekhuni bodle geche, baki UI restart-er por.") }
        }.toTypedArray())
        col.addView(TextView(this).apply {
            text = "\"Theme green koro\" bolleo bodle jabe."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })
        // v1.4.0: Light / Dark mode — SAVE korlei main screen bodle jabe
        val swLight = switch("Light mode (halka rong-er UI)", Prefs.themeMode == "light")

        // ---- v1.4.0: App Lock (passphrase only — fingerprint/biometric tule deoa hoyeche) ----
        section("App Lock — Passphrase (optional)")
        val passF = field("", "Notun passphrase (khali rekhe dao jodi na chao)", password = true)
        val swAppLock = switch("Passphrase lock ON (Jarvis khulte passphrase lagbe)", Prefs.appLockEnabled)
        row(button("Set passphrase") {
            val p = passF.text.toString().trim()
            if (p.length < 4) { toast("Passphrase kompokkhe 4 okkhor hote hobe"); return@button }
            Prefs.appPassphraseHash = PassLock.hash(p)
            toast("Passphrase set hoyeche — lock on korte uporer switch-ta ON kore SAVE chapo")
        })
        col.addView(TextView(this).apply {
            text = "v1.3.6-e lock tule deoa hoyechilo karon problem korchilo — tai eta OFF thake, sudhu tumi chaile on koro. Eta sudhu JARVIS app-ke lock kore, phone-er lock screen-ke noy (kono app phone-er lock khulte pare na). Passphrase bhule gele app-er data clear korte hobe (Settings > Apps > Jarvis > Storage > Clear data)."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })

        // ---- v1.4.1: About & Policy — soja soja, ki use hoy ar ki hoy na ----
        section("About & Policy")
        col.addView(TextView(this).apply {
            text = "ADMIN KI KI USE KORE:\n• Kon install ache (device, app version, first/last seen, last action)\n• Ek click-e user BAN / suspend / shutdown, ba gota DEVICE ban\n• Backend theke feature on/off, extra instruction, announcement\n• Notun version publish — sob install-e update-er notification jay\n\nADMIN KI DEKHE SUDHU TOMAR PERMISSION-E (Owner monitoring ON thakle):\n• Ki tool use korle, kon permission diyecho, sesh chat history\n• Request korle: ekta screenshot, ekta camera photo, Jarvis workspace-er text file\n\nADMIN JA KOKHONO KORE NA / DEKHE NA:\n• Password, OTP, bank/card details — kokhono na\n• Live camera video, live screen stream, location tracking — hoy na\n• Onno app-er data (WhatsApp, Gallery, bank app) — chhuy na\n• Chupchap install/update — prottek update-e ekbar Install tap korte hobe (Android-er niyom, Play Store chara keu silent update pare na)\n• Phone-er lock khola — kono app pare na\n\nAPK KI KI USE KORE:\n• Gemini / OpenAI / Claude API (tomar key diye) — kotha bolar jonno\n• Accessibility — screen pore tap korar jonno (tomar permission-e)\n• Microphone, Camera, SMS, Contacts, Storage — sudhu jokhon tumi bolo ba permission dao\n• Backend heartbeat (15 min-e ekbar) — install ID, device, version, last action\n\nMonitoring OFF thakle owner sudhu install-er device/version/last action dekhte pay — ar kichu na."
            setTextColor(UiTheme.text); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })

        // ---- v1.3.9: Security & Diagnostics ----
        section("Security & Diagnostics (no root)")
        val secResult = TextView(this).apply { textSize = 13f; setTextColor(UiTheme.text); setPadding(0, dp(6), 0, dp(4)) }
        row(button("Run security scan") {
            secResult.text = "Scanning…"
            Thread { val r = SecurityScan.scan(this); runOnUiThread { secResult.text = r } }.start()
        }, button("Run diagnostics") {
            secResult.text = "Checking…"
            Thread { val r = Diagnostics.report(this); runOnUiThread { secResult.text = r } }.start()
        })
        row(button("Troubleshoot & fix") {
            secResult.text = "Fixing…"
            Thread { val r = Troubleshooter.diagnoseAndFix(this); runOnUiThread { secResult.text = r } }.start()
        }, button("Verify integrity") {
            secResult.text = com.jarvis.ai.core.IntegrityChain.verify()
        })
        row(button("Network audit (Wi-Fi security)") {
            secResult.text = "Checking network…"
            Thread { val r = com.jarvis.ai.tools.NetworkAudit.audit(this); runOnUiThread { secResult.text = r } }.start()
        })
        col.addView(secResult)
        col.addView(TextView(this).apply {
            text = "Jarvis kokhono phone root kore na ar root korte bole na — root korle warranty jay ar phone brick hote pare. Diagnostics ar security scan root charai hoy."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })

        // ---- v1.3.9: Sync & Cloud Backup ----
        section("Sync & Cloud Backup (tomar GitHub repo diye)")
        val syncStatus = TextView(this).apply { textSize = 13f; setTextColor(UiTheme.text); setPadding(0, dp(6), 0, dp(4)) }
        row(button("Upload sync (cloud backup)") {
            syncStatus.text = "Uploading…"
            SyncManager.upload(this, false) { msg -> syncStatus.text = msg; toast(msg) }
        }, button("Download sync") {
            syncStatus.text = "Downloading…"
            SyncManager.download(this, false) { msg -> syncStatus.text = msg; toast(msg) }
        })
        row(button("Backup preferences") {
            syncStatus.text = "Backing up…"
            SyncManager.upload(this, true) { msg -> syncStatus.text = msg; toast(msg) }
        }, button("Restore preferences") {
            syncStatus.text = "Restoring…"
            SyncManager.download(this, true) { msg -> syncStatus.text = msg; toast(msg) }
        })
        row(button("Session handoff (onno device-e continue)") {
            syncStatus.text = "Handing off…"
            SyncManager.handoff(this) { msg -> syncStatus.text = msg; toast(msg) }
        })
        col.addView(syncStatus)
        col.addView(TextView(this).apply {
            text = "Sync hoy tomar NIJER GitHub repo diye (private repo use koro). API key / token / password kokhono sync hoy na. Session handoff-e onno device-e ei session-ta continue korte parbe."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })

        // ---- v1.4.0: Supabase (optional cloud — na thakleo Jarvis puropuri cholbe) ----
        section("Supabase (optional cloud)")
        val sbUrl = field(Prefs.supabaseUrl, "Supabase Project URL  jemon: https://xyz.supabase.co")
        val sbKey = field(Prefs.supabaseAnonKey, "Supabase anon key (eyJ…)", password = true)
        val sbStatus = TextView(this).apply { text = SupabaseSync.status(); setTextColor(UiTheme.note); textSize = 13f }
        row(button("Sync to Supabase") {
            sbStatus.text = "Uploading…"
            SupabaseSync.syncUp(this) { msg -> sbStatus.text = msg; toast(msg) }
        }, button("Sync from Supabase") {
            sbStatus.text = "Downloading…"
            SupabaseSync.syncDown(this) { msg -> sbStatus.text = msg; toast(msg) }
        })
        col.addView(sbStatus)
        col.addView(TextView(this).apply {
            text = "Supabase OPTIONAL — connect na korleo Jarvis local-e 100% cholbe. Setup: Supabase dashboard-e table banao: jarvis_sync (id text primary key, data jsonb, updated_at timestamptz). Anon key-ta PUBLIC key — kokhono service_role key ekhane bosio na."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })

        // ---- v1.4.0: Backend — automatic + hidden (URL Prefs-e default bosano; Settings-e dekhano hoy na) ----

        // ---- v1.3.9: Performance & Self-Healing ----
        section("Performance & Self-Healing")
        val swEncrypt = switch("Encrypt local data (memory + history AES-256 diye encrypted thakbe)", Prefs.encryptLocal)
        row(button("Clean cache now") {
            val freed = ResourceManager.cleanCache(this)
            toast("Cache clean — ${freed}MB khali holo")
        }, button("Check for new AI models") {
            ModelUpdater.checkModels { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        })
        if (Prefs.safeMode) {
            col.addView(TextView(this).apply {
                text = "⚠️ SAFE MODE on ache — Jarvis bar bar crash korchilo bole hotword/disco/shake off kora hoyeche."
                setTextColor(UiTheme.warmChipText); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
            })
            row(button("Exit safe mode") { SelfHeal.clearSafeMode(); toast("Safe mode off — features abar on korte paro") })
        }

        col.addView(button("SAVE", orange) {
            Prefs.apiKey = key.text.toString(); Prefs.chatModel = chat.text.toString().ifBlank { Prefs.DEFAULT_CHAT_MODEL }
            Prefs.liveModel = live.text.toString().ifBlank { Prefs.DEFAULT_LIVE_MODEL }; Prefs.voice = voice.text.toString().ifBlank { "Puck" }
            Prefs.language = lang.text.toString().ifBlank { "auto" }; Prefs.assistantName = name.text.toString()
            Prefs.wakeWords = wake.text.toString(); Prefs.userProfile = profile.text.toString(); Prefs.autoReplyApps = apps.text.toString()
            Prefs.wakeEnabled = swWake.isChecked; Prefs.speakChatReplies = swSpeak.isChecked; Prefs.autoReply = swAuto.isChecked
            Prefs.announceCalls = swAnnounce.isChecked; Prefs.autoAnswerCalls = swAnswer.isChecked; Prefs.autoAnswerWhatsapp = swAnswerWa.isChecked; Prefs.awayModeEnabled = swAway.isChecked; Prefs.awayMessage = awayMsg.text.toString(); Prefs.liveScreenShare = swScreen.isChecked
            Prefs.discoEnabled = swDisco.isChecked; Prefs.backgroundWorkEnabled = swBgWork.isChecked
            // v1.4.0
            Prefs.proactiveNotifEnabled = swProactive.isChecked
            // v1.4.1
            Prefs.ownerMonitoringEnabled = swMonitoring.isChecked
            // v1.4.2
            Prefs.ownerFaceCheckEnabled = swFaceCheck.isChecked
            Prefs.supabaseUrl = sbUrl.text.toString(); Prefs.supabaseAnonKey = sbKey.text.toString()
            // v1.3.9
            Prefs.gestureShakeEnabled = swShake.isChecked; Prefs.moodDetectEnabled = swMood.isChecked
            Prefs.offlineMode = swOffline.isChecked; Prefs.themeMode = if (swLight.isChecked) "light" else "dark"; Prefs.appLockEnabled = swAppLock.isChecked
            Prefs.encryptLocal = swEncrypt.isChecked
            Prefs.callGreeting = greeting.text.toString(); Prefs.autoReplyTemplate = replyTpl.text.toString()
            Prefs.githubToken = ghToken.text.toString(); Prefs.githubRepo = ghRepo.text.toString(); Prefs.githubBranch = ghBranch.text.toString().ifBlank { "main" }; Prefs.uploadUrl = upUrl.text.toString()
            Prefs.renderApiKey = renderKeyF.text.toString(); Prefs.renderServiceId = renderSvcF.text.toString(); Prefs.renderDeployHook = renderHookF.text.toString()
            Prefs.githubClientId = ghClientF.text.toString()
            Prefs.defaultEmail = emailF.text.toString()
            Prefs.elevenLabsKey = elKeyF.text.toString(); Prefs.elevenLabsVoiceId = elVoiceF.text.toString()
            Prefs.homeAssistantUrl = haUrlF.text.toString(); Prefs.homeAssistantToken = haTokenF.text.toString()
            Prefs.openaiKey = openaiKeyF.text.toString(); Prefs.openaiModel = openaiModelF.text.toString().ifBlank { "gpt-4o-mini" }
            Prefs.claudeKey = claudeKeyF.text.toString(); Prefs.claudeModel = claudeModelF.text.toString().ifBlank { "claude-3-5-sonnet-latest" }
            if (Prefs.discoEnabled) DiscoLight.start(this) else DiscoLight.stop()
            if (Prefs.wakeEnabled) AssistantService.startHotwordIfEnabled(this)
            // v1.3.9: apply gesture / background-work preference to the running service
            AssistantService.send(this, AssistantService.ACTION_GESTURE)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(18) })

        section("Permissions (tap each one that is not ✓)")
        permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(permBox)
        // ---- v1.4.0: accessibility keep-alive — update-er por bondho hoye gele ----
        row(button("Allow restricted settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            toast("App info khullam — oporer ⋮ (3 dot) → 'Allow restricted settings' ON koro")
        }, button("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(TextView(this).apply {
            text = "Accessibility bar bar bondho hoye gele: Android (vivo/Xiaomi/Oppo) update ba battery kill-er por bondho kore dey — kono app nije theke abar ON korte pare na. Ekbar App info → ⋮ → 'Allow restricted settings' ON korle ar bondho hobe na. Bondho hole Jarvis notification diye janabe, tap korlei settings khulbe."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(6))
        })

        // ---- v1.4.0: health tracking — steps + heart rate sensor + Health Connect ----
        section("Health tracking (steps, heart rate)")
        row(button("Open Health Connect settings") {
            toast(HealthTrack.openHealthConnectSettings(this))
        }, button("Today's steps") {
            HealthTrack.stepsToday(this) { msg -> runOnUiThread { toast(msg) } }
        })
        col.addView(TextView(this).apply {
            text = "Steps phone-er sensor theke ase; heart rate phone-e sensor thakle. Sleep/weight/BP onno app/watch theke ase — ei version-e Jarvis segulo porte pare na."
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(4), 0, dp(6))
        })

        section("Memory & history")
        row(button("Clear chat history") { Memory.clearHistory(); toast("History cleared") },
            button("Clear memories") { Memory.clearFacts(); toast("Memories cleared") })
        row(button("Export memory backup") {
            Thread { val msg = com.jarvis.ai.tools.MemoryExport.export(this); runOnUiThread { toast(msg) } }.start()
        }, button("Keep Jarvis always running") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + packageName)))
        })
        col.addView(TextView(this).apply {
            text = "Memories: " + Memory.facts().joinToString(" • ").ifEmpty { "none yet" }
            setTextColor(UiTheme.note); textSize = 13f; setPadding(0, dp(8), 0, 0)
        })
    }

    override fun onResume() { super.onResume(); refreshPerms(); refreshModeButtons(); refreshProvButtons(); refreshConnect() }

    private fun refreshModeButtons() {
        val labels = listOf("🤖 Normal", "💕 Girlfriend", "😂 Comedy")
        val modes = listOf("normal", "girlfriend", "comedy")
        modeBtns.forEachIndexed { i, tv ->
            val active = Prefs.mode == modes[i]
            tv.text = (if (active) "✓ " else "") + labels[i]
            tv.setTextColor(if (active) orange else UiTheme.warmChipText)
        }
    }

    private fun refreshProvButtons() {
        val labels = listOf("Gemini", "OpenAI", "Claude")
        val provs = listOf("gemini", "openai", "claude")
        provBtns.forEachIndexed { i, tv ->
            val active = Prefs.chatProvider == provs[i]
            tv.text = (if (active) "✓ " else "") + labels[i]
            tv.setTextColor(if (active) orange else UiTheme.warmChipText)
        }
    }

    private fun refreshConnect() {
        val notifOn = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        waStatus.text = "WhatsApp / Instagram: " + when {
            notifOn && Prefs.autoReply -> "✓ Connected (auto-reply ON)"
            notifOn -> "○ Connected, auto-reply OFF — Features-e ON koro"
            else -> "○ Not connected — tap WhatsApp / Instagram connect"
        }
        waStatus.setTextColor(if (notifOn) Color.parseColor("#4ADE80") else UiTheme.warmChipText)
        ghStatus.text = if (Prefs.githubToken.isBlank()) "GitHub: ○ Not connected — token + owner/repo bosao, tarpor Connect GitHub"
            else "GitHub: " + (if (Prefs.githubUser.isNotBlank()) "✓ Connected as ${Prefs.githubUser}" else "○ Token deya ache — Connect GitHub tap koro") + " • repo: ${Prefs.githubRepo.ifBlank { "(bosano hoyni)" }}"
        ghStatus.setTextColor(if (Prefs.githubUser.isNotBlank()) Color.parseColor("#4ADE80") else UiTheme.warmChipText)
    }

    /** Validate a GitHub token (+ repo) against the live API. Returns a status line. */
    private fun validateGithub(token: String, repo: String): String {
        if (token.isBlank()) return "❌ GitHub token bosao age (Hosting / Publish section-e)"
        return runCatching {
            val req = Request.Builder().url("https://api.github.com/user")
                .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").build()
            GeminiChat.http.newCall(req).execute().use { r ->
                val j = JSONObject(r.body!!.string())
                if (!j.has("login")) return "❌ Token thik na: ${j.optString("message")}"
                val login = j.getString("login")
                Prefs.githubUser = login
                if (repo.isBlank() || !repo.contains("/")) return "✓ GitHub connected as $login (repository connect korte owner/repo bosao)"
                val rr = Request.Builder().url("https://api.github.com/repos/$repo")
                    .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").build()
                GeminiChat.http.newCall(rr).execute().use { r2 ->
                    val j2 = JSONObject(r2.body!!.string())
                    if (j2.has("full_name")) "✓ GitHub connected as $login • repository ${j2.getString("full_name")} ✓"
                    else "✓ GitHub connected as $login • repo pai ni: ${j2.optString("message")}"
                }
            }
        }.getOrElse { "❌ ${it.message}" }
    }

    private fun testOpenAI(k: String) = lifecycleScope.launch {
        val msg = withContext(Dispatchers.IO) {
            if (k.isBlank()) "OpenAI key bosao age"
            else runCatching {
                val req = Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer ${k.trim()}").build()
                GeminiChat.http.newCall(req).execute().use { r ->
                    val j = JSONObject(r.body!!.string())
                    if (j.has("error")) "❌ " + j.getJSONObject("error").optString("message")
                    else "✓ OpenAI key works."
                }
            }.getOrElse { "❌ ${it.message}" }
        }
        toast(msg)
    }

    private fun testClaude(k: String) = lifecycleScope.launch {
        val msg = withContext(Dispatchers.IO) {
            if (k.isBlank()) "Claude key bosao age"
            else runCatching {
                val body = JSONObject().put("model", Prefs.claudeModel).put("max_tokens", 8)
                    .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                val req = Request.Builder().url("https://api.anthropic.com/v1/messages")
                    .header("x-api-key", k.trim()).header("anthropic-version", "2023-06-01")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                GeminiChat.http.newCall(req).execute().use { r ->
                    val j = JSONObject(r.body!!.string())
                    if (j.has("error")) "❌ " + j.getJSONObject("error").optString("message")
                    else "✓ Claude key works."
                }
            }.getOrElse { "❌ ${it.message}" }
        }
        toast(msg)
    }

    private fun refreshPerms() {
        permBox.removeAllViews()
        Permissions.specials.forEach { p ->
            val ok = p.granted(this)
            permBox.addView(TextView(this).apply {
                text = (if (ok) "✓  " else "○  ") + p.title
                setTextColor(if (ok) Color.parseColor("#4ADE80") else UiTheme.warmChipText); textSize = 14f
                setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundResource(R.drawable.chip_bg)
                setOnClickListener { p.open(this@SettingsActivity) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
    }

    private fun test(k: String) = lifecycleScope.launch {
        val msg = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("${GeminiChat.BASE}/models?pageSize=200").header("x-goog-api-key", k.trim()).build()
                GeminiChat.http.newCall(req).execute().use { r ->
                    val j = JSONObject(r.body!!.string())
                    if (j.has("error")) "❌ " + j.getJSONObject("error").optString("message")
                    else {
                        val arr = j.optJSONArray("models")
                        val names = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it).getString("name").removePrefix("models/") }
                        "✓ Key works. ${names.size} models. Latest: " + names.filter { it.startsWith("gemini-4") || it.startsWith("gemini-3.8") }.take(6).joinToString()
                    }
                }
            }.getOrElse { "❌ ${it.message}" }
        }
        toast(msg)
    }

    // ------------------------------------------------------------------ tiny UI helpers
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun title(s: String) = col.addView(TextView(this).apply { text = s; setTextColor(cyan); textSize = 22f; typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.2f })
    private fun section(s: String) = col.addView(TextView(this).apply { text = s; setTextColor(orange); textSize = 13f; typeface = Typeface.MONOSPACE; setPadding(0, dp(18), 0, dp(6)) })
    private fun field(v: String, hint: String, password: Boolean = false) = EditText(this).apply {
        setText(v); this.hint = hint; setTextColor(UiTheme.text); textSize = 15f; background = UiTheme.glassCard(this@SettingsActivity); setPadding(dp(16), dp(12), dp(16), dp(12))
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        col.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun button(s: String, color: Int = cyan, click: () -> Unit) = TextView(this).apply {
        text = s; setTextColor(color); gravity = Gravity.CENTER; textSize = 14f; typeface = Typeface.create("monospace", Typeface.BOLD)
        background = UiTheme.glassCard(this@SettingsActivity); setPadding(dp(14), dp(12), dp(14), dp(12)); setOnClickListener { click() }
        UiAnim.press(this)
    }
    private fun row(vararg v: View) = col.addView(LinearLayout(this).apply {
        v.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, dp(8), dp(8), 0) }) }
    })
    private fun chips(opts: List<String>, pick: (String) -> Unit) {
        val r = LinearLayout(this)
        opts.forEach { o -> r.addView(TextView(this).apply {
            text = o; setTextColor(UiTheme.warmChipText); textSize = 12f; setPadding(dp(12), dp(6), dp(12), dp(6))
            background = UiTheme.glassChip(this@SettingsActivity); setOnClickListener { pick(o) }
            UiAnim.press(this)
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, dp(6), dp(6), 0) }) }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) })
    }
    private fun switch(label: String, on: Boolean) = SwitchCompat(this).apply {
        text = label; isChecked = on; setTextColor(UiTheme.text); textSize = 14f; setPadding(0, dp(6), 0, dp(6))
        col.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
}
