package com.jarvis.ai.tools

import android.content.Context
import com.jarvis.ai.App
import com.jarvis.ai.core.IntegrityChain
import com.jarvis.ai.core.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v1.3.9 — Cross-device data sync + cloud backup for user preferences.
 * There is no Jarvis cloud. Sync runs through the user's OWN GitHub repo
 * (the one already connected in Settings > Connect) — private repo recommended.
 * Uploads: memory.json, history.json, settings.json. settings.json contains
 * ONLY non-secret preferences (mode, voice, theme, toggles) — API keys,
 * tokens and passwords NEVER sync. Download applies the files and the user
 * restarts Jarvis once.
 */
object SyncManager {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private fun guard(): String? {
        if (Prefs.githubToken.isBlank() || Prefs.githubRepo.isBlank()) {
            return "GitHub connect kora nei — Settings > Connect theke GitHub connect koro, tarpor sync korte parbe. (Private repo use koro — tahole tomar data sudhu tomar thakbe.)"
        }
        return null
    }

    fun settingsJson(): String {
        val o = JSONObject()
        o.put("assistant_name", Prefs.assistantName)
        o.put("mode", Prefs.mode)
        o.put("voice", Prefs.voice)
        o.put("language", Prefs.language)
        o.put("chat_model", Prefs.chatModel)
        o.put("live_model", Prefs.liveModel)
        o.put("theme_accent", Prefs.themeAccent)
        o.put("wake_enabled", Prefs.wakeEnabled)
        o.put("disco_enabled", Prefs.discoEnabled)
        o.put("gesture_shake_enabled", Prefs.gestureShakeEnabled)
        o.put("mood_detect_enabled", Prefs.moodDetectEnabled)
        o.put("offline_mode", Prefs.offlineMode)
        o.put("speak_chat_replies", Prefs.speakChatReplies)
        o.put("encrypt_local", Prefs.encryptLocal)
        return o.toString()
    }

    private fun applySettings(json: String) {
        runCatching {
            val o = JSONObject(json)
            Prefs.assistantName = o.optString("assistant_name", Prefs.assistantName)
            Prefs.mode = o.optString("mode", Prefs.mode)
            Prefs.voice = o.optString("voice", Prefs.voice)
            Prefs.language = o.optString("language", Prefs.language)
            Prefs.chatModel = o.optString("chat_model", Prefs.chatModel)
            Prefs.liveModel = o.optString("live_model", Prefs.liveModel)
            Prefs.themeAccent = o.optString("theme_accent", Prefs.themeAccent)
            Prefs.wakeEnabled = o.optBoolean("wake_enabled", Prefs.wakeEnabled)
            Prefs.discoEnabled = o.optBoolean("disco_enabled", Prefs.discoEnabled)
            Prefs.gestureShakeEnabled = o.optBoolean("gesture_shake_enabled", Prefs.gestureShakeEnabled)
            Prefs.moodDetectEnabled = o.optBoolean("mood_detect_enabled", Prefs.moodDetectEnabled)
            Prefs.offlineMode = o.optBoolean("offline_mode", Prefs.offlineMode)
            Prefs.speakChatReplies = o.optBoolean("speak_chat_replies", Prefs.speakChatReplies)
            Prefs.encryptLocal = o.optBoolean("encrypt_local", Prefs.encryptLocal)
        }
    }

    private fun readFile(name: String): String {
        // history/memory files may be AES-encrypted (v1.3.9) — sync the PLAIN
        // content, because the Keystore key is per-device and another phone
        // could never decrypt the raw file.
        return when (name) {
            "history.json" -> com.jarvis.ai.core.Memory.exportHistoryJson()
            "memory.json" -> com.jarvis.ai.core.Memory.exportMemoryJson()
            else -> {
                val f = File(App.instance.filesDir, name)
                if (f.exists()) f.readText() else "[]"
            }
        }
    }

    fun upload(ctx: Context, onlySettings: Boolean, cb: (String) -> Unit) {
        guard()?.let { cb(it); return }
        com.jarvis.ai.core.BackgroundWorker.run(ctx, "sync-upload", {
            val files = if (onlySettings) listOf("settings.json" to settingsJson())
            else listOf("memory.json" to readFile("memory.json"), "history.json" to readFile("history.json"), "settings.json" to settingsJson())
            var last = ""
            for ((name, content) in files) {
                val doneRef = java.util.concurrent.atomic.AtomicBoolean(false)
                val msgRef = java.util.concurrent.atomic.AtomicReference("")
                PublishHelper.publishFile(ctx, "jarvis-sync/$name", content) { msgRef.set(it); doneRef.set(true) }
                var waited = 0
                while (!doneRef.get() && waited < 60_000) { Thread.sleep(200); waited += 200 }
                last = msgRef.get()
                // PublishHelper success starts with "Published!"
                if (!last.startsWith("Published") && !last.startsWith("✓") && !last.lowercase().contains("uploaded") && !last.lowercase().contains("saved")) {
                    return@run "Sync upload theme gelo: $last"
                }
            }
            IntegrityChain.record("sync upload (${if (onlySettings) "settings" else "memory+history+settings"})")
            "☁️ Cloud backup hoye geche (${files.map { it.first }.joinToString(", ")}) — repo: ${Prefs.githubRepo}/jarvis-sync/. API keys / tokens kokhono sync hoy na."
        }, cb)
    }

    fun download(ctx: Context, onlySettings: Boolean, cb: (String) -> Unit) {
        guard()?.let { cb(it); return }
        com.jarvis.ai.core.BackgroundWorker.run(ctx, "sync-download", {
            val names = if (onlySettings) listOf("settings.json") else listOf("memory.json", "history.json", "settings.json")
            for (name in names) {
                val url = "https://raw.githubusercontent.com/${Prefs.githubRepo}/${Prefs.githubBranch}/jarvis-sync/$name"
                val req = Request.Builder().url(url).header("Authorization", "Bearer ${Prefs.githubToken}").build()
                val body = runCatching { http.newCall(req).execute().use { r -> if (!r.isSuccessful) null else r.body?.string() } }.getOrNull()
                    ?: return@run "Download holo na: jarvis-sync/$name pelam na (age ekbar upload koro)."
                if (name == "settings.json") applySettings(body)
                else File(App.instance.filesDir, name).writeText(body)
            }
            IntegrityChain.record("sync download (${if (onlySettings) "settings" else "memory+history+settings"})")
            // cross-device session handoff: if another device handed a session off, show it
            val handoffNote = runCatching {
                val url = "https://raw.githubusercontent.com/${Prefs.githubRepo}/${Prefs.githubBranch}/jarvis-sync/handoff.json"
                val req = Request.Builder().url(url).header("Authorization", "Bearer ${Prefs.githubToken}").build()
                val body = http.newCall(req).execute().use { r -> if (!r.isSuccessful) null else r.body?.string() }
                if (body.isNullOrBlank()) null
                else {
                    val o = JSONObject(body)
                    val whenStr = java.text.SimpleDateFormat("dd MMM h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(o.optLong("time", 0L)))
                    "\n📲 Session handoff from ${o.optString("device")} ($whenStr): \"${o.optString("last_message")}\""
                }
            }.getOrNull()
            "☁️ Download hoye geche — Jarvis restart koro (app bondho kore abar kholo), tahole notun memory + settings apply hobe.${handoffNote ?: ""}"
        }, cb)
    }

    /** Cross-device session handoff: uploads the session + a marker so another device can continue it. */
    fun handoff(ctx: Context, cb: (String) -> Unit) {
        guard()?.let { cb(it); return }
        upload(ctx, false) { msg ->
            if (!msg.startsWith("☁️")) { cb(msg); return@upload }
            val last = com.jarvis.ai.core.Memory.history().lastOrNull { it.role == "user" }?.text?.take(200) ?: "(kono recent message nei)"
            val handoff = JSONObject()
                .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                .put("time", System.currentTimeMillis())
                .put("last_message", last)
                .toString()
            PublishHelper.publishFile(ctx, "jarvis-sync/handoff.json", handoff) { _ ->
                cb("📲 Session handoff ready — onno device-e Jarvis khule 'Download sync' chaplei ei session-ta continue hobe.\nLast: \"$last\"")
            }
        }
    }
}
