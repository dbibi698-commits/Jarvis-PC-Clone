package com.jarvis.ai.tools

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.webkit.WebView
import androidx.core.content.FileProvider
import com.jarvis.ai.App
import com.jarvis.ai.core.ActivityLog
import com.jarvis.ai.core.AdaptiveLearning
import com.jarvis.ai.core.GeminiChat
import com.jarvis.ai.core.IntegrityChain
import com.jarvis.ai.core.Memory
import com.jarvis.ai.core.Prefs
import com.jarvis.ai.core.ResourceManager
import com.jarvis.ai.core.SelfHeal
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.jarvis.ai.service.BackgroundUploadService
import com.jarvis.ai.service.DiscoLight
import com.jarvis.ai.service.LockHelper
import com.jarvis.ai.ui.BrowserActivity
import com.jarvis.ai.service.JarvisAccessibilityService
import com.jarvis.ai.service.JarvisNotificationListener
import com.jarvis.ai.ui.DashboardActivity
import com.jarvis.ai.ui.WebPreviewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class ToolResult(val data: JSONObject, val imageJpeg: ByteArray? = null)

/** Executes every tool Gemini can call. */
class ToolExecutor(private val ctx: Context) {

    private val acc get() = JarvisAccessibilityService.instance
    val workspace: File get() = File(ctx.getExternalFilesDir(null), "workspace").apply { mkdirs() }

    private fun ok(msg: String = "done") = ToolResult(JSONObject().put("ok", true).put("result", msg))
    private fun err(msg: String) = ToolResult(JSONObject().put("ok", false).put("error", msg))
    private fun needAcc() = err("Accessibility service is OFF. Ask the user to enable 'Jarvis AI' in Settings > Accessibility (Jarvis Settings > Permissions).")

    private fun start(i: Intent): Boolean = runCatching {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); true
    }.getOrDefault(false)

    suspend fun run(name: String, a: JSONObject): ToolResult {
        // v1.4.1: owner backend theke feature OFF thakle seta cholbe na
        if (!Prefs.isToolEnabled(name)) return err("Owner '$name' feature-ta backend theke bondho kore diyeche.")
        return try {
            ActivityLog.record(name) // v1.4.1: activity log (monitoring ON thakle backend-e jay)
            AdaptiveLearning.recordTool(name) // v1.3.9: learn usage habits for predictions
            withContext(Dispatchers.Main) { exec(name, a) }
        } catch (e: SecurityException) {
            err("Permission missing: ${e.message}. Ask user to grant it in Jarvis Settings > Permissions.")
        } catch (e: Exception) {
            err("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun exec(name: String, a: JSONObject): ToolResult {
        when (name) {
            // ---------------- apps & screen ----------------
            "open_app" -> {
                val app = AppFinder.find(ctx, a.optString("name")) ?: return err("App '${a.optString("name")}' not found. Use list_apps.")
                return if (AppFinder.launch(ctx, app.pkg)) { delay(1200); ok("Opened ${app.label} (${app.pkg})") } else err("Cannot launch ${app.label}")
            }
            "list_apps" -> {
                val f = a.optString("filter").lowercase()
                val list = AppFinder.all(ctx).filter { f.isEmpty() || it.label.lowercase().contains(f) || it.pkg.contains(f) }
                return ToolResult(JSONObject().put("count", list.size).put("apps", JSONArray(list.map { "${it.label} = ${it.pkg}" })))
            }
            // v1.4.0: install apps by touching the screen like a human
            "install_app" -> {
                return suspendCancellableCoroutine { cont ->
                    AppInstaller.install(ctx, a.optString("app")) { msg -> cont.resume(ok(msg)) }
                }
            }
            // v1.4.0: WhatsApp Web link, object recognition, mood recommendations, phone finder
            "whatsapp_web" -> {
                start(Intent(ctx, BrowserActivity::class.java).putExtra("url", "https://web.whatsapp.com"))
                return ok("📱 WhatsApp Web khullam — WhatsApp-e Settings > Linked devices > Link a device diye QR scan koro (ekbar). Tarpor Jarvis browser diye WhatsApp porte ar pathate parbe. Official WhatsApp API third-party app-er jonno nei — tai eta-i asol rasta.")
            }
            "identify_object" -> {
                val s = acc ?: return needAcc()
                return ok(ObjectVision.identify(ctx, s))
            }
            "recommend" -> {
                return suspendCancellableCoroutine { cont ->
                    MoodRecommend.recommend(ctx, a.optString("mood")) { msg -> cont.resume(ok(msg)) }
                }
            }
            "find_phone" -> {
                PhoneFinder.ring(ctx, a.optInt("seconds", 30)) {}
                return ok("📱 Phone bajche (jore alarm + vibration + torch)! \"stop\" bolle thambe.")
            }
            "stop_ringing" -> return ok(PhoneFinder.stop(ctx))
            "read_screen" -> {
                val s = acc ?: return needAcc()
                val img = if (a.optBoolean("with_image", true)) s.screenshotJpeg() else null
                return ToolResult(JSONObject().put("screen", s.screenText()), img)
            }
            "tap_text" -> {
                val s = acc ?: return needAcc()
                val r = s.clickText(a.getString("text"), a.optBoolean("long_press")); delay(700)
                return if (r) ok("Tapped '${a.getString("text")}'") else err("Element '${a.getString("text")}' not found. Use read_screen.")
            }
            "tap" -> {
                val s = acc ?: return needAcc()
                s.tap(a.getInt("x").toFloat(), a.getInt("y").toFloat(), if (a.optBoolean("long_press")) 700L else 60L); delay(700)
                return ok()
            }
            "swipe" -> {
                val s = acc ?: return needAcc()
                s.swipe(a.getInt("x1").toFloat(), a.getInt("y1").toFloat(), a.getInt("x2").toFloat(), a.getInt("y2").toFloat(), a.optLong("duration_ms", 350)); delay(600)
                return ok()
            }
            "scroll" -> {
                val s = acc ?: return needAcc()
                repeat(a.optInt("times", 1).coerceIn(1, 20)) { s.scroll(a.optString("direction", "down")); delay(700) }
                return ok("Scrolled ${a.optString("direction")}")
            }
            "type_text" -> {
                val s = acc ?: return needAcc()
                if (!s.typeText(a.getString("text"), a.optBoolean("append"))) return err("No input field found. Tap the field first.")
                if (a.optBoolean("press_enter")) { delay(200); s.pressEnter() }
                delay(300); return ok("Typed")
            }
            "press_key" -> {
                val s = acc ?: return needAcc()
                val k = a.getString("key")
                val r = if (k == "send") s.clickSend() else s.global(k)
                delay(600); return if (r) ok("Pressed $k") else err("Could not press $k")
            }
            "wait" -> { delay(a.optLong("ms", 1000).coerceIn(100, 8000)); return ok() }

            // ---------------- communication ----------------
            "make_call" -> {
                val num = resolveNumber(a.getString("to")) ?: return err("No number found for '${a.getString("to")}'")
                start(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num))))
                if (a.optBoolean("speaker")) { delay(2500); speaker(true) }
                return ok("Calling $num")
            }
            "answer_call" -> {
                // v1.3.2: system API first, accessibility Answer-button tap as fallback
                val tm = ctx.getSystemService(TelecomManager::class.java)
                runCatching { @Suppress("DEPRECATION") tm.acceptRingingCall() }
                delay(900)
                val tel = ctx.getSystemService(TelephonyManager::class.java)
                if (tel.callState == TelephonyManager.CALL_STATE_RINGING) {
                    val acc = JarvisAccessibilityService.instance
                    if (acc?.answerIncomingCall(false) != true) acc?.answerIncomingCall(true)
                    delay(900)
                }
                val connected = ctx.getSystemService(TelephonyManager::class.java).callState != TelephonyManager.CALL_STATE_RINGING
                if (connected && a.optBoolean("ai_talk", false)) { delay(1200); speaker(true) }
                return if (connected) ok("Call answered") else ok("Could not answer — the phone's Answer button was not found. Enable Jarvis Accessibility in Settings > Permissions and try again.")
            }
            // v1.4.2: WhatsApp call answer (Accessibility Answer tap + speaker greeting)
            "answer_whatsapp_call" -> return ok(com.jarvis.ai.tools.WhatsAppCall.answerNow(ctx, acc))
            // v1.4.3: Away Mode on/off (voice control)
            "set_away_mode" -> {
                com.jarvis.ai.core.Prefs.awayModeEnabled = a.optBoolean("enabled", false)
                return ok(if (com.jarvis.ai.core.Prefs.awayModeEnabled) "✅ Away mode ON — ekhon theke WhatsApp call dhore \"${com.jarvis.ai.core.Prefs.awayMessage}\" bole kete debo." else "Away mode OFF — ekhon theke WhatsApp call dhore sudhu greeting bolbo, owner-er jonno wait korbo.")
            }
            "end_call" -> {
                val tm = ctx.getSystemService(TelecomManager::class.java)
                @Suppress("DEPRECATION")
                val r = if (Build.VERSION.SDK_INT >= 28) tm.endCall() else false
                if (r) return ok("Call ended")
                // v1.3.2: tap the in-call screen's End button as fallback
                val acc = acc
                if (acc != null) {
                    for (c in listOf("End call", "End", "Hang up", "Decline", "কল শেষ")) {
                        if (acc.clickText(c)) return ok("Call ended")
                    }
                }
                return ok("Tried to end call — if it is still on, tap End call on the call screen.")
            }
            "send_whatsapp" -> {
                val to = a.getString("to"); val msg = a.getString("message")
                val pkg = if (a.optBoolean("business")) "com.whatsapp.w4b" else "com.whatsapp"
                val num = resolveNumber(to)?.filter { it.isDigit() || it == '+' }?.removePrefix("+")
                    ?: return err("No WhatsApp number for '$to'")
                val full = if (num.length == 10) "91$num" else num
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$full&text=" + URLEncoder.encode(msg, "UTF-8")))
                if (AppFinder.installed(ctx, pkg)) i.setPackage(pkg)
                start(i)
                // wait for chat to open, then press send
                for (t in 0 until 10) {
                    delay(800)
                    if (acc?.clickSend() == true) return ok("WhatsApp message sent to $to")
                }
                return ok("WhatsApp opened with the message typed. Accessibility could not press Send — use read_screen + tap_text Send.")
            }
            "send_sms" -> {
                val num = resolveNumber(a.getString("to")) ?: return err("No number found")
                val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
                sms.sendMultipartTextMessage(num, null, sms.divideMessage(a.getString("message")), null, null)
                return ok("SMS sent to $num")
            }
            "reply_notification" -> {
                val r = JarvisNotificationListener.reply(ctx, a.getString("from"), a.getString("message"))
                return if (r) ok("Replied") else err("No repliable notification from '${a.getString("from")}'. Enable notification access or open the chat and type.")
            }
            "read_notifications" -> {
                val l = JarvisNotificationListener.recent.takeLast(a.optInt("limit", 15))
                return ToolResult(JSONObject().put("enabled", JarvisNotificationListener.connected)
                    .put("notifications", JSONArray(l.map { "[${it.appLabel}] ${it.title}: ${it.text}" })))
            }
            "find_contact" -> return ToolResult(JSONObject().put("contacts", JSONArray(searchContacts(a.getString("name")).map { "${it.first}: ${it.second}" })))

            // ---------------- media & social ----------------
            "play_youtube" -> {
                val q = a.getString("query")
                val i = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q)
                if (!start(i)) start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
                delay(3000)
                // tap first result
                acc?.let { s -> val (w, h) = s.screenSize(); s.tap(w / 2f, h * 0.38f) }
                return ok("Playing '$q' on YouTube (opened search and tapped the first result)")
            }
            "open_url" -> {
                var u = a.getString("url"); if (!u.contains("://")) u = "https://$u"
                return if (start(Intent(Intent.ACTION_VIEW, Uri.parse(u)))) ok("Opened $u") else err("Cannot open")
            }
            "web_search" -> {
                start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(a.getString("query")))))
                return ok("Searching")
            }
            "share_media_to_app" -> {
                val video = a.optString("media_type", "video") != "image"
                val uris = galleryUris(video, a.optInt("index", 0) + 1)
                val uri = uris.getOrNull(a.optInt("index", 0)) ?: return err("No ${if (video) "video" else "image"} found in gallery")
                val app = AppFinder.find(ctx, a.getString("app")) ?: return err("App not installed")
                val i = Intent(Intent.ACTION_SEND).setType(if (video) "video/*" else "image/*")
                    .putExtra(Intent.EXTRA_STREAM, uri).setPackage(app.pkg).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                a.optString("caption").takeIf { it.isNotBlank() }?.let { i.putExtra(Intent.EXTRA_TEXT, it) }
                if (!start(i)) start(Intent.createChooser(i, "Share"))
                delay(2500)
                return ok("Shared media to ${app.label}. Now use read_screen and tap through Next/Post/Upload to finish.")
            }
            "list_gallery" -> {
                val video = a.optString("media_type", "image") == "video"
                return ToolResult(JSONObject().put("items", JSONArray(galleryList(video, a.optInt("limit", 10)))))
            }
            "open_camera" -> {
                start(Intent(if (a.optBoolean("video")) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                return ok("Camera opened")
            }
            "media_control" -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                val code = when (a.getString("action")) {
                    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> KeyEvent.KEYCODE_MEDIA_PLAY
                }
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                return ok()
            }

            // ---------------- device ----------------
            "set_alarm" -> {
                start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, a.getInt("minute")).putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                return ok("Alarm set for %02d:%02d".format(a.getInt("hour"), a.getInt("minute")))
            }
            "set_timer" -> {
                start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("seconds"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                return ok("Timer started")
            }
            "set_volume" -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max * a.getInt("percent").coerceIn(0, 100) / 100, AudioManager.FLAG_SHOW_UI)
                return ok()
            }
            "set_brightness" -> {
                if (!Settings.System.canWrite(ctx)) {
                    start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                    return err("Need 'Modify system settings' permission — opened the screen for the user.")
                }
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255 * a.getInt("percent").coerceIn(1, 100) / 100)
                return ok()
            }
            "flashlight" -> {
                val cm = ctx.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull() ?: return err("No camera")
                cm.setTorchMode(id, a.getBoolean("on")); return ok()
            }
            "open_settings" -> {
                val act = when (a.getString("panel").lowercase()) {
                    "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "mobile_data", "internet" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS
                    "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                    "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
                    "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    "display" -> Settings.ACTION_DISPLAY_SETTINGS
                    "sound", "volume" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
                    "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                    "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                    "notifications" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                    "assistant" -> Settings.ACTION_VOICE_INPUT_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                start(Intent(act)); return ok("Opened ${a.getString("panel")} settings")
            }
            "screen_power" -> {
                when (a.getString("action")) {
                    "wake" -> {
                        val pm = ctx.getSystemService(PowerManager::class.java)
                        @Suppress("DEPRECATION")
                        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "jarvis:wake").acquire(5000)
                        return ok("Screen on")
                    }
                    "lock" -> return if (acc?.global("lock") == true) ok("Screen locked") else needAcc()
                    "power_menu" -> return if (acc?.global("power_menu") == true) ok("Power menu open") else needAcc()
                    "power_off", "restart" -> {
                        val s = acc ?: return needAcc()
                        s.global("power_menu"); delay(1200)
                        val words = if (a.getString("action") == "restart") listOf("Restart", "Reboot", "রিস্টার্ট", "पुनः प्रारंभ") else listOf("Power off", "Turn off", "পাওয়ার অফ", "बंद करें")
                        for (w in words) if (s.clickText(w)) { delay(1000); s.clickText(w); return ok("Tapped $w (phone may ask to confirm)") }
                        return ok("Power menu opened — use read_screen to tap the option.")
                    }
                }
                return err("Unknown action")
            }
            // v1.3.1: screen video recording
            "record_screen" -> {
                when (a.optString("action")) {
                    "stop" -> {
                        com.jarvis.ai.service.AssistantService.stopRecord(ctx)
                        delay(1500)
                        val path = com.jarvis.ai.service.ScreenRecorder.lastVideoPath
                        return ok(if (path != null) "Screen recording stopped. Video saved: $path (Gallery > Movies > JarvisAI)." else "Screen recording stopped.")
                    }
                    else -> {
                        val seconds = a.optInt("seconds", 0)
                        com.jarvis.ai.service.AssistantService.record(ctx, seconds)
                        return ok("Screen recording starting${if (seconds > 0) " for $seconds seconds" else ""}. First time, Android asks the user to tap 'Start now' once on the system dialog. The video saves to Gallery > Movies > JarvisAI.")
                    }
                }
            }
            // v1.3.9: renamed from device_status (which was shadowed by the smart-home one)
            "phone_status" -> {
                val bm = ctx.getSystemService(BatteryManager::class.java)
                val cm = ctx.getSystemService(ConnectivityManager::class.java)
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                val net = when {
                    caps == null -> "offline"; caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"; else -> "online"
                }
                val st = StatFs(Environment.getDataDirectory().path)
                return ToolResult(JSONObject()
                    .put("battery_percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                    .put("charging", bm.isCharging).put("network", net)
                    .put("time", SimpleDateFormat("EEE dd MMM yyyy HH:mm", Locale.ENGLISH).format(Date()))
                    .put("free_storage_gb", "%.1f".format(st.availableBytes / 1e9))
                    .put("current_app", JarvisAccessibilityService.currentPackage)
                    .put("accessibility_on", JarvisAccessibilityService.isOn))
            }

            // ---------------- v1.2: Admin lock / unlock ----------------
            "lock_screen" -> {
                return if (LockHelper.lockNow(ctx)) ok("Screen locked with Device Admin")
                else err("Device Admin is not enabled. Open Jarvis Settings > Admin & App Lock > Enable Device Admin, then try again.")
            }
            "unlock_phone" -> {
                LockHelper.wakeScreen(ctx)
                return if (LockHelper.isKeyguardSecure(ctx)) ok("Screen woken. Your phone has a PIN/password/fingerprint lock — only you can unlock it with that. After you unlock, Jarvis will be waiting.")
                else { start(Intent(ctx, com.jarvis.ai.ui.MainActivity::class.java)); ok("Screen woken and Jarvis opened (no secure lock was set).") }
            }
            // ---------------- v1.2: browser / Google ----------------
            "open_browser" -> {
                val q = a.optString("query"); val u = a.optString("url")
                start(Intent(ctx, BrowserActivity::class.java).putExtra("query", q).putExtra("url", u))
                return ok("Browser opened" + if (q.isNotBlank()) " for '$q'" else "")
            }
            "google_search" -> {
                start(Intent(ctx, BrowserActivity::class.java).putExtra("query", a.getString("query")))
                return ok("Google search opened in the Jarvis browser for '${a.getString("query")}'")
            }
            // ---------------- v1.2: disco light ----------------
            "disco_light" -> {
                Prefs.discoEnabled = a.getBoolean("on")
                if (a.getBoolean("on")) DiscoLight.start(ctx) else DiscoLight.stop()
                return ok(if (a.getBoolean("on")) "Disco light ON — rainbow light will run around the display edges while Jarvis runs in the background (needs Display over other apps)." else "Disco light OFF")
            }
            // ---------------- v1.2: background video upload ----------------
            "upload_video" -> {
                if (!Prefs.backgroundWorkEnabled) return err("Background work is off in Settings.")
                val uris = galleryUris(true, a.optInt("index", 0) + 1)
                val uri = uris.getOrNull(a.optInt("index", 0)) ?: return err("No video found in gallery")
                return if (Prefs.uploadUrl.isBlank()) {
                    val app = AppFinder.find(ctx, "YouTube") ?: return err("No upload URL set in Settings > Background Video Upload, and YouTube not found.")
                    val i = Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, uri).setPackage(app.pkg).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    a.optString("caption").takeIf { it.isNotBlank() }?.let { i.putExtra(Intent.EXTRA_TEXT, it) }
                    start(i); ok("No upload URL set, so I opened YouTube's upload screen with your video. Finish it there — YouTube does not allow silent background posting.")
                } else {
                    BackgroundUploadService.startUpload(ctx, uri, "video_${System.currentTimeMillis()}.mp4")
                    ok("Background upload started for your video. It will keep uploading even if you leave the app.")
                }
            }
            // ---------------- v1.2: hosting / publish ----------------
            // v1.3.2: multi-voice + deploy
            "change_voice" -> {
                val v = a.getString("voice")
                com.jarvis.ai.service.AssistantService.changeVoice(ctx, v)
                return ok("Voice changed to $v.")
            }
            "deploy_website" -> {
                val rel = a.optString("file", "index.html").trimStart('/')
                val f = File(workspace, rel)
                if (!f.exists()) return err("File not found in workspace: $rel. Ask Jarvis to build the website first, then deploy.")
                val content = f.readText()
                return suspendCancellableCoroutine { cont ->
                    PublishHelper.publishFile(ctx, rel, content) { msg -> cont.resume(ok(msg)) }
                }
            }
            // v1.4.0: create a new GitHub repo + push a ZIP's files into it
            "create_repo" -> {
                return suspendCancellableCoroutine { cont ->
                    RepoManager.createRepo(ctx, a.getString("name"), a.optBoolean("private", false), a.optString("description")) { msg -> cont.resume(ok(msg)) }
                }
            }
            "push_zip_to_github" -> {
                return suspendCancellableCoroutine { cont ->
                    RepoManager.pushZip(ctx, a.getString("file"), a.optString("folder")) { msg -> cont.resume(ok(msg)) }
                }
            }
            // v1.4.0: Supabase optional cloud sync (local-first)
            "supabase_status" -> return ok(SupabaseSync.status())
            "supabase_sync" -> {
                return suspendCancellableCoroutine { cont ->
                    if (a.optString("direction", "up").lowercase() == "down") {
                        SupabaseSync.syncDown(ctx) { msg -> cont.resume(ok(msg)) }
                    } else {
                        SupabaseSync.syncUp(ctx) { msg -> cont.resume(ok(msg)) }
                    }
                }
            }
            // v1.3.4: Render deploy
            "render_deploy" -> {
                return suspendCancellableCoroutine { cont ->
                    RenderDeploy.deploy { msg -> cont.resume(ok(msg)) }
                }
            }
            "render_status" -> {
                return suspendCancellableCoroutine { cont ->
                    RenderDeploy.status { msg -> cont.resume(ok(msg)) }
                }
            }
            // v1.3.6: video making, APK building, media editing, shopping
            "make_video" -> {
                val names = a.optString("images").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val uris = if (names.isNotEmpty()) {
                    names.mapNotNull { n -> val f = File(workspace, n); if (f.exists()) shareUri(f) else null }
                } else galleryUris(false, 5)
                if (uris.isEmpty()) return err("No photos found — workspace-e photo dao (jemon: photo1.jpg, photo2.jpg) ba gallery-te photo rakho.")
                return suspendCancellableCoroutine { cont ->
                    VideoMaker.make(ctx, uris, a.optInt("seconds", 3), a.optString("text")) { msg -> cont.resume(ok(msg)) }
                }
            }
            "build_apk" -> {
                return suspendCancellableCoroutine { cont ->
                    ApkBuilder.build(ctx) { msg -> cont.resume(ok(msg)) }
                }
            }
            "check_build" -> {
                return suspendCancellableCoroutine { cont ->
                    ApkBuilder.check(ctx) { msg -> cont.resume(ok(msg)) }
                }
            }
            "edit_video" -> {
                val uri = resolveMediaUri(a, video = true) ?: return err("No video found — gallery-te video rakho ba workspace-e video file dao.")
                val target = AppFinder.find(ctx, "CapCut") ?: AppFinder.find(ctx, "YouCut")
                return if (target != null) {
                    val i = Intent(Intent.ACTION_SEND).setType("video/*")
                        .putExtra(Intent.EXTRA_STREAM, uri).setPackage(target.pkg)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    start(i)
                    ok("Video khule dilam ${target.label}-e — ekhane professional edit koro (trim, text, music, effects).")
                } else {
                    val i = Intent(Intent.ACTION_EDIT).setDataAndType(uri, "video/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (start(Intent.createChooser(i, "Edit video"))) ok("Video editor khule dilam — CapCut install korle aro professional edit hobe.")
                    else err("Kono video editor pelam na — Play Store theke CapCut install koro.")
                }
            }
            "edit_photo" -> {
                val uri = resolveMediaUri(a, video = false) ?: return err("No photo found — gallery-te photo rakho ba workspace-e photo file dao.")
                val snapseed = AppFinder.find(ctx, "Snapseed")
                return if (snapseed != null) {
                    val i = Intent(Intent.ACTION_SEND).setType("image/*")
                        .putExtra(Intent.EXTRA_STREAM, uri).setPackage(snapseed.pkg)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    start(i)
                    ok("Photo khule dilam Snapseed-e — professional edit koro.")
                } else {
                    val i = Intent(Intent.ACTION_EDIT).setDataAndType(uri, "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (start(Intent.createChooser(i, "Edit photo"))) ok("Photo editor khule dilam.")
                    else err("Kono photo editor pelam na.")
                }
            }
            "remove_background" -> {
                val uri = resolveMediaUri(a, video = false) ?: return err("No photo found")
                val key = Prefs.removeBgKey.trim()
                if (key.isBlank()) {
                    start(Intent(ctx, BrowserActivity::class.java).putExtra("url", "https://www.remove.bg/upload"))
                    return ok("remove.bg API key bosano nai — remove.bg browser-e khule dilam, photo upload kore background remove koro. Key thakle Settings > Photo / Video / Build-e bosao, tahole Jarvis nijei korbe.")
                }
                return suspendCancellableCoroutine { cont ->
                    Thread {
                        val msg = runCatching {
                            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: return@runCatching "Photo porte parlam na."
                            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                                .addFormDataPart("image_file", "photo.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
                                .addFormDataPart("size", "auto")
                                .build()
                            val req = Request.Builder().url("https://api.remove.bg/v1.0/removebg")
                                .header("X-Api-Key", key).post(body).build()
                            GeminiChat.http.newCall(req).execute().use { r ->
                                if (r.code !in 200..299) return@runCatching "❌ remove.bg failed (HTTP ${r.code}) — API key thik ache kina dekho (Settings > Photo / Video / Build)."
                                val png = r.body!!.bytes()
                                val name = "NoBg-${System.currentTimeMillis()}.png"
                                saveImageToGallery(ctx, name, png)
                                "✓ Background remove hoyeche! Gallery > Pictures > JarvisAI > $name (transparent PNG)"
                            }
                        }.getOrElse { "❌ Background remove hoy ni: ${it.message}" }
                        cont.resume(ok(msg))
                    }.start()
                }
            }
            "buy_online" -> {
                val site = a.getString("site").lowercase()
                val product = a.getString("product")
                val url = when (site) {
                    "amazon" -> "https://www.amazon.in/s?k=" + URLEncoder.encode(product, "UTF-8")
                    else -> "https://www.flipkart.com/search?q=" + URLEncoder.encode(product, "UTF-8")
                }
                start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return ok("'$product' ${if (site == "amazon") "Amazon" else "Flipkart"}-e khuje dilam — pochondo hole cart-e add kore checkout-e jao. Payment-er age amake confirm koro — ami nije payment korbo na, OTP/UPI tomar hatei thakbe.")
            }
            "free_domain" -> {
                start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.is-a.dev/")))
                return ok("Free domain: is-a.dev khule dilam — ekhane free subdomain (tomar-nam.is-a.dev) pabe. Ar tomar site-er emnitei free address ache: GitHub Pages-e username.github.io, Render-e *.onrender.com. Publish korle Jarvis oi free address-ei live korbe.")
            }
            // v1.3.7: log in / create an account on any website
            "login_website" -> {
                val site = a.getString("site").trim().lowercase()
                val signup = a.optString("action").lowercase() == "signup"
                val url = when {
                    site.contains("google") || site.contains("gmail") || site.contains("youtube") -> "https://accounts.google.com"
                    site.contains("facebook") -> if (signup) "https://www.facebook.com/r.php" else "https://www.facebook.com/login"
                    site.contains("instagram") -> if (signup) "https://www.instagram.com/accounts/emailsignup/" else "https://www.instagram.com/accounts/login/"
                    site.contains("github") -> if (signup) "https://github.com/signup" else "https://github.com/login"
                    site.contains("amazon") -> "https://www.amazon.in/ap/signin"
                    site.contains("flipkart") -> "https://www.flipkart.com/account/login"
                    site.contains("twitter") || site == "x" -> "https://x.com/i/flow/login"
                    site.contains("netflix") -> "https://www.netflix.com/login"
                    site.contains("linkedin") -> "https://www.linkedin.com/login"
                    site.contains("render") -> "https://dashboard.render.com/login"
                    site.contains("netlify") -> "https://app.netlify.com/login"
                    site.contains("vercel") -> "https://vercel.com/login"
                    site.contains("whatsapp") -> "https://web.whatsapp.com"
                    site.contains("://") -> site
                    site.contains(".") -> "https://$site"
                    else -> "https://www.google.com/search?q=" + URLEncoder.encode("$site login", "UTF-8")
                }
                start(Intent(ctx, BrowserActivity::class.java).putExtra("url", url))
                val email = Prefs.defaultEmail
                return ok("${if (signup) "Signup" else "Login"} page khule dilam ($url). " +
                    if (email.isNotBlank()) "Email hisebe $email use korbo — password-ta dao, ami type kore ${if (signup) "account khulbo" else "login korbo"}. OTP ele code-ta dao."
                    else "Email/password dao, ami type kore ${if (signup) "account khulbo" else "login korbo"}. OTP ele code-ta dao.")
            }
            // v1.3.8: smart home + self feature requests
            "control_device" -> {
                val entity = a.getString("entity").trim()
                val action = a.getString("action").trim().lowercase()
                if (!SmartHome.isConfigured()) {
                    val gh = AppFinder.find(ctx, "Google Home")
                    val li = gh?.let { ctx.packageManager.getLaunchIntentForPackage(it.pkg) }
                    if (li != null) {
                        start(li)
                        return ok("Home Assistant set kora nai — Google Home app khule dilam, ekhane device control koro. Home Assistant URL + token Settings > Smart Home-e bosale Jarvis nijei control korbe.")
                    }
                    return err("Home Assistant set kora nai (Settings > Smart Home-e URL + token bosao), ar Google Home app-o pelam na.")
                }
                val domain = entity.substringBefore(".")
                val service = when (action) {
                    "on" -> "turn_on"
                    "off" -> "turn_off"
                    else -> "toggle"
                }
                return suspendCancellableCoroutine { cont ->
                    SmartHome.callService(domain, service, entity) { msg -> cont.resume(ok(msg)) }
                }
            }
            "device_status" -> {
                return suspendCancellableCoroutine { cont ->
                    SmartHome.states { msg -> cont.resume(ok(msg)) }
                }
            }
            "request_feature" -> {
                val feature = a.getString("feature")
                val slug = feature.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "feature" }
                val name = "features/requests/$slug-${System.currentTimeMillis()}.md"
                val content = "# Feature request\n\n$feature\n\n_Requested via Jarvis voice/chat. To build: write the code, push to GitHub, run build_apk, install the update._\n"
                val f = File(workspace, name).apply { parentFile?.mkdirs(); writeText(content) }
                if (Prefs.githubToken.isBlank() || Prefs.githubRepo.isBlank()) {
                    return ok("Feature request save korlam (workspace/$name). GitHub connect korle ami eta push-o kore debo. Mone rekho: ami nijer cholonto code nije bodlate pari na — notun feature code likhe, GitHub-e push kore, build_apk diye notun version banate hoy, tarpor tumi install korle feature chole ashe.")
                }
                return suspendCancellableCoroutine { cont ->
                    PublishHelper.publishFile(ctx, name, content) { msg ->
                        cont.resume(ok("Feature request save + GitHub-e push korlam: $feature\n$msg\nNotun version build korte 'build_apk' bolo."))
                    }
                }
            }
            "publish_file" -> {
                val f = File(workspace, a.getString("path").trimStart('/'))
                if (!f.exists()) return err("File not found in workspace: ${a.getString("path")}")
                val content = f.readText()
                return suspendCancellableCoroutine { cont ->
                    PublishHelper.publishFile(ctx, a.getString("path").trimStart('/'), content) { msg -> cont.resume(ok(msg)) }
                }
            }
            "host_website" -> {
                return if (a.getBoolean("on")) {
                    val url = HostServer.start(ctx, workspace)
                    ok("Hosting started! Open this on the same Wi-Fi: $url  (serves your workspace files, index.html first)")
                } else { HostServer.stop(); ok("Hosting stopped") }
            }

            // ---------------- files & coding ----------------
            "save_file" -> {
                val path = a.getString("path").trimStart('/')
                val f = File(workspace, path); f.parentFile?.mkdirs(); f.writeText(a.getString("content"))
                val pub = runCatching { copyToDownloads(f, path) }.getOrNull()
                return ok("Saved ${f.length()} bytes to workspace/$path${if (pub != null) " and Downloads/JarvisAI/$path" else ""}")
            }
            "read_file" -> {
                val f = File(workspace, a.getString("path").trimStart('/'))
                if (!f.exists()) return err("Not found")
                return ToolResult(JSONObject().put("content", f.readText().take(60000)))
            }
            "list_files" -> {
                val d = File(workspace, a.optString("dir"))
                val l = d.walkTopDown().filter { it.isFile }.take(300).map { it.relativeTo(workspace).path + " (${it.length()}b)" }.toList()
                return ToolResult(JSONObject().put("files", JSONArray(l)))
            }
            "delete_file" -> return if (File(workspace, a.getString("path")).deleteRecursively()) ok("Deleted") else err("Not found")
            "preview_html" -> {
                val f = File(workspace, a.getString("path").trimStart('/'))
                if (!f.exists()) return err("Not found")
                start(Intent(ctx, WebPreviewActivity::class.java).putExtra("path", f.absolutePath)); return ok("Preview opened")
            }
            "run_javascript" -> return runJs(a.getString("code"))

            // ---------------- memory ----------------
            "remember" -> { Memory.remember(a.getString("fact")); return ok("Remembered") }
            "forget" -> return ok("Forgot ${Memory.forget(a.getString("keyword"))} memories")
            "search_history" -> {
                val q = a.getString("query").lowercase()
                val hits = Memory.history().filter { it.text.lowercase().contains(q) }.takeLast(20)
                    .map { "${SimpleDateFormat("dd/MM HH:mm", Locale.ENGLISH).format(Date(it.time))} ${it.role}: ${it.text.take(300)}" }
                return ToolResult(JSONObject().put("matches", JSONArray(hits)))
            }

            // ---------------- v1.3.9: battery, translate, scheduling, security, sync, theme, resources, prediction, updates, integrity, API, logs ----------------
            "battery_status" -> {
                val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val pct = if (level >= 0) level * 100 / scale else -1
                val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10f) ?: 0f
                val volt = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
                val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over-voltage"
                    BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                    else -> "unknown"
                }
                val plugged = when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    else -> "not plugged in"
                }
                val status = when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, 0)) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                    BatteryManager.BATTERY_STATUS_FULL -> "full"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                    else -> "unknown"
                }
                val bm = ctx.getSystemService(BatteryManager::class.java)
                val counter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                val current = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                return ok("🔋 Battery: $pct% ($status), plugged: $plugged\nHealth: $health, temp: ${temp}°C, voltage: ${volt}mV\nCharge: ${counter / 1000}mAh, current: ${current / 1000}mA")
            }
            "translate" -> {
                return suspendCancellableCoroutine { cont ->
                    Translator.translate(a.getString("text"), a.optString("target", "English")) { msg -> cont.resume(ok(msg)) }
                }
            }
            // v1.4.0: offline translation + password vault
            "translate_offline" -> return ok(OfflineTranslator.translate(ctx, a.getString("text"), a.optString("target", "English")))
            "save_password" -> return ok(PasswordVault.savePassword(ctx, a.getString("site"), a.optString("username"), a.getString("password")))
            "get_password" -> return ok(PasswordVault.getPassword(ctx, a.getString("site")))
            "list_passwords" -> return ok(PasswordVault.listPasswords(ctx))
            "delete_password" -> return ok(PasswordVault.deletePassword(ctx, a.getString("site")))
            // v1.4.0: family wake words
            "add_wake_word" -> return ok(com.jarvis.ai.core.FamilyWakeWords.add(a.getString("word"), a.getString("person")))
            "list_wake_words" -> return ok(com.jarvis.ai.core.FamilyWakeWords.listText())
            "remove_wake_word" -> return ok(com.jarvis.ai.core.FamilyWakeWords.remove(a.getString("word")))
            // v1.4.0: health tracking (on-device sensors + Health Connect onboarding)
            "health_status" -> {
                return suspendCancellableCoroutine { cont ->
                    HealthTrack.summary(ctx) { msg -> cont.resume(ok(msg)) }
                }
            }
            "health_permissions" -> return ok(HealthTrack.openHealthConnectSettings(ctx))
            // v1.4.0: wellness tracker (sleep + hydration)
            "log_sleep" -> return ok(WellnessTracker.logSleep(a.getString("bed"), a.getString("wake")))
            "log_water" -> return ok(WellnessTracker.logWater(a.getString("amount")))
            "wellness_status" -> return ok(WellnessTracker.status())
            "schedule_task" -> return ok(Scheduler.scheduleReminder(ctx, a.getString("text"), a.getString("time"), a.optBoolean("daily", false)))
            "list_schedules" -> return ok(Scheduler.listAll())
            // v1.4.0: smart calendar sync
            "list_events" -> return ok(CalendarSync.listEvents(ctx, a.optString("when", "today")))
            "add_event" -> return ok(CalendarSync.addEvent(ctx, a.getString("title"), a.getString("start"), a.optInt("duration", 60), a.optString("description"), a.optString("location")))
            // v1.4.0: daily summary briefing
            "daily_briefing" -> {
                return suspendCancellableCoroutine { cont ->
                    DailyBriefing.briefing(ctx) { msg -> cont.resume(ok(msg)) }
                }
            }
            // v1.4.0: meme auto-sort + focus (work-slacking) report
            "sort_memes" -> {
                return suspendCancellableCoroutine { cont ->
                    MemeSorter.sort(ctx, a.optString("folder", "memes"), a.optBoolean("dry_run", false)) { msg -> cont.resume(ok(msg)) }
                }
            }
            "focus_report" -> {
                return suspendCancellableCoroutine { cont ->
                    FocusWatch.report(ctx, a.optString("date", "today"), a.optString("work_start", "10:00"), a.optString("work_end", "18:00")) { msg -> cont.resume(ok(msg)) }
                }
            }
            // v1.4.0: auto online/offline switch status
            "network_status" -> return ok(com.jarvis.ai.core.OfflineMode.statusText(ctx))
            // v1.4.0: Control Panel (Quick Settings) access
            "open_control_panel" -> return ok(ControlPanel.openPanel(acc))
            "toggle_setting" -> {
                return suspendCancellableCoroutine { cont ->
                    ControlPanel.toggle(ctx, acc, a.getString("setting"), a.optInt("level", -1)) { msg -> cont.resume(ok(msg)) }
                }
            }
            // v1.4.0: wallpaper changer (background, home/lock/both) + automatic messaging + wake screen
            "set_wallpaper" -> {
                return suspendCancellableCoroutine { cont ->
                    WallpaperChanger.setWallpaper(ctx, a.optString("source", "random"), a.optString("screen", "both")) { msg -> cont.resume(ok(msg)) }
                }
            }
            "schedule_message" -> return ok(Scheduler.scheduleMessage(ctx, a.getString("to"), a.getString("message"), a.getString("time"), a.optBoolean("daily", false)))
            "wake_screen" -> return ok(ControlPanel.wakeScreen(ctx))
            // v1.4.0: owner backend status (heartbeat check)
            "backend_status" -> {
                return suspendCancellableCoroutine { cont ->
                    com.jarvis.ai.core.BackendClient.heartbeat(ctx, null) { msg -> cont.resume(ok(msg)) }
                }
            }
            // v1.4.2: owner face check — selfie vs owner photo (Gemini Vision)
            "verify_owner" -> {
                return suspendCancellableCoroutine { cont ->
                    com.jarvis.ai.core.OwnerFace.verify(ctx) { (_, msg) -> cont.resume(ok(msg)) }
                }
            }
            "save_owner_photo" -> {
                return suspendCancellableCoroutine { cont ->
                    com.jarvis.ai.core.OwnerFace.captureSelfie(ctx) { bytes ->
                        if (bytes == null) cont.resume(err("Camera khulte pareni — camera permission dao ba camera busy kina dekho."))
                        else if (com.jarvis.ai.core.OwnerFace.saveOwnerPhoto(ctx, bytes)) cont.resume(ok("✅ Owner photo save hoyeche — ekhon theke face check ei photo-r sathe milabe."))
                        else cont.resume(err("Owner photo save korte pareni."))
                    }
                }
            }
            "cancel_schedule" -> return ok(Scheduler.cancel(ctx, a.getString("id")))
            "create_routine" -> return ok(Scheduler.createRoutine(ctx, a.getString("name"), a.getString("trigger"), a.getString("action")))
            "list_routines" -> return ok(Scheduler.listAll())
            "delete_routine" -> return ok(Scheduler.cancel(ctx, a.getString("id")))
            "security_scan" -> return ok(SecurityScan.scan(ctx))
            "diagnostics" -> return ok(Diagnostics.report(ctx))
            "troubleshoot" -> return ok(Troubleshooter.diagnoseAndFix(ctx))
            "sync_upload" -> {
                return suspendCancellableCoroutine { cont -> SyncManager.upload(ctx, false) { msg -> cont.resume(ok(msg)) } }
            }
            "sync_download" -> {
                return suspendCancellableCoroutine { cont -> SyncManager.download(ctx, false) { msg -> cont.resume(ok(msg)) } }
            }
            "backup_preferences" -> {
                return suspendCancellableCoroutine { cont -> SyncManager.upload(ctx, true) { msg -> cont.resume(ok(msg)) } }
            }
            "restore_preferences" -> {
                return suspendCancellableCoroutine { cont -> SyncManager.download(ctx, true) { msg -> cont.resume(ok(msg)) } }
            }
            "session_handoff" -> {
                return suspendCancellableCoroutine { cont -> SyncManager.handoff(ctx) { msg -> cont.resume(ok(msg)) } }
            }
            "change_theme" -> {
                val hex = when (a.getString("color").lowercase()) {
                    "orange" -> "#FF7A1A"; "pink" -> "#EC4899"; "green" -> "#22C55E"; "purple" -> "#A78BFA"
                    else -> "#22D3EE"
                }
                Prefs.themeAccent = hex
                return ok("🎨 Theme ${a.getString("color")} kore dilam — orb-er rong ekhuni bodle geche, baki UI app restart-er por bodlabe.")
            }
            "resource_status" -> return ok(ResourceManager.report(ctx))
            "clean_cache" -> return ok("Cache clean — ${ResourceManager.cleanCache(ctx)}MB khali holo.")
            "predict_next" -> {
                val preds = AdaptiveLearning.predictNext()
                val hint = AdaptiveLearning.proactiveHint()
                return ok(buildString {
                    appendLine("🔮 Predictive AI — tomar habit theke:")
                    if (preds.isEmpty()) appendLine("- Ekhono kono strong pattern nei — koyekdin use korle ami tomar routine shikhe nebo.")
                    else preds.forEach { (tool, n) -> appendLine("- \"${AdaptiveLearning.toolToCommand(tool)}\" ($n bar ei somoy korecho)") }
                    hint?.let { appendLine("\n$it") }
                    appendLine("\nTop tools: ${AdaptiveLearning.topTools(3).joinToString(", ")}")
                })
            }
            "check_update" -> {
                return suspendCancellableCoroutine { cont -> UpdateChecker.check(ctx) { msg -> cont.resume(ok(msg)) } }
            }
            "check_models" -> {
                return suspendCancellableCoroutine { cont -> ModelUpdater.checkModels { msg -> cont.resume(ok(msg)) } }
            }
            "verify_integrity" -> return ok(IntegrityChain.verify())
            "api_call" -> {
                return suspendCancellableCoroutine { cont ->
                    ApiConnector.call(a.getString("method"), a.getString("url"), a.optString("headers", "{}"), a.optString("body", "")) { msg -> cont.resume(ok(msg)) }
                }
            }
            "view_logs" -> {
                val startup = runCatching { File(App.instance.filesDir, "startup.log").readText().takeLast(2000) }.getOrDefault("(no startup log)")
                val crash = runCatching { File(App.instance.filesDir, "crash-last.txt").readText().take(2000) }.getOrDefault("(no crash recorded)")
                return ok("📋 Startup log (last):\n$startup\n\n💥 Last crash:\n$crash")
            }
            "self_heal" -> {
                val note = SelfHeal.onAppStart(ctx)
                return ok(note ?: "✅ Self-healing check: kono problem pelam na — crash history clean, memory thik ache.")
            }
            "build_bundle" -> {
                return suspendCancellableCoroutine { cont ->
                    ApkBuilder.buildBundle(ctx) { msg -> cont.resume(ok(msg)) }
                }
            }
            "dashboard" -> {
                start(Intent(ctx, DashboardActivity::class.java))
                return ok("📊 Dashboard khullam — battery, memory, storage, mood, top tools, schedules, security — 5 second-e refresh hoy.")
            }
            "network_audit" -> return ok(NetworkAudit.audit(ctx))
            "export_memory" -> return ok(MemoryExport.export(ctx))
            "set_gesture" -> return ok(GestureMapper.set(a.getString("gesture"), a.getString("action")))
            "delegate_task" -> {
                return suspendCancellableCoroutine { cont ->
                    TaskDelegator.delegate(ctx, a.getString("task")) { msg -> cont.resume(ok(msg)) }
                }
            }
            "fix_build" -> {
                return suspendCancellableCoroutine { cont ->
                    ApkBuilder.selfCorrect(ctx) { msg -> cont.resume(ok(msg)) }
                }
            }
            "keep_alive" -> {
                val pm = ctx.getSystemService(PowerManager::class.java)
                val ignored = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                return if (ignored) ok("✅ Jarvis battery optimization theke exempted — background-e choltei thakbe, mic-o sobsomoy cholbe (hotword listener error hole nijei restart hoy).")
                else {
                    start(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
                    ok("🔋 Battery optimization exemption-er screen khullam — 'Allow' chapo, tahole Android Jarvis-ke background-e marbe na, ar mic sobsomoy cholbe.")
                }
            }
        }
        return err("Unknown tool $name")
    }

    // ---------------- helpers ----------------
    private fun speaker(on: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            else am.clearCommunicationDevice()
        } else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = on }
    }

    private fun resolveNumber(to: String): String? {
        val digits = to.filter { it.isDigit() || it == '+' }
        if (digits.length >= 6) return digits
        return searchContacts(to).firstOrNull()?.second
    }

    fun searchContacts(name: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)?.use { c ->
            while (c.moveToNext() && out.size < 10) out.add(c.getString(0) to c.getString(1).replace(" ", ""))
        }
        return out.distinctBy { it.second }
    }

    private fun galleryUris(video: Boolean, limit: Int): List<Uri> {
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val out = mutableListOf<Uri>()
        ctx.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID), null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext() && out.size < limit) out.add(ContentUris.withAppendedId(base, c.getLong(0)))
        }
        return out
    }

    private fun galleryList(video: Boolean, limit: Int): List<String> {
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val out = mutableListOf<String>()
        ctx.contentResolver.query(base, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE),
            null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            var i = 0
            while (c.moveToNext() && i < limit) {
                out.add("#$i ${c.getString(0)} ${SimpleDateFormat("dd MMM HH:mm", Locale.ENGLISH).format(Date(c.getLong(1) * 1000))} ${c.getLong(2) / 1024}KB"); i++
            }
        }
        return out
    }

    private fun copyToDownloads(f: File, rel: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) {
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "JarvisAI/$rel")
            d.parentFile?.mkdirs(); f.copyTo(d, true); return Uri.fromFile(d)
        }
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JarvisAI/" + (File(rel).parent ?: ""))
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        return uri
    }

    fun shareUri(f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    /** v1.3.6: resolve a media file — workspace filename if given, else newest gallery item. */
    private fun resolveMediaUri(a: JSONObject, video: Boolean): Uri? {
        val file = a.optString("file").trim()
        if (file.isNotBlank()) {
            val f = File(workspace, file.trimStart('/'))
            if (f.exists()) return shareUri(f)
        }
        val uris = galleryUris(video, a.optInt("index", 0) + 1)
        return uris.getOrNull(a.optInt("index", 0))
    }

    /** v1.3.6: save a PNG to Gallery > Pictures > JarvisAI. */
    private fun saveImageToGallery(ctx: Context, name: String, png: ByteArray) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JarvisAI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(png) }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "JarvisAI").apply { mkdirs() }
            File(dir, name).writeBytes(png)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runJs(code: String): ToolResult {
        val wv = WebView(ctx); wv.settings.javaScriptEnabled = true
        val wrapped = """(function(){var __l=[];var console={log:function(){__l.push(Array.prototype.slice.call(arguments).map(String).join(' '))},error:function(){__l.push('ERR '+Array.prototype.slice.call(arguments).join(' '))}};
            try{var __r=(function(){ $code })();return JSON.stringify({result:String(__r),logs:__l});}catch(e){return JSON.stringify({error:String(e),logs:__l});}})()"""
        val r = withTimeoutOrNull(10000) {
            suspendCancellableCoroutine<String> { c -> wv.evaluateJavascript(wrapped) { if (c.isActive) c.resume(it ?: "null") } }
        }
        wv.destroy()
        return ToolResult(JSONObject().put("output", r ?: "timeout"))
    }
}
