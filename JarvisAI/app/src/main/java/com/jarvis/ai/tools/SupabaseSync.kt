package com.jarvis.ai.tools

import android.content.Context
import com.jarvis.ai.core.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.4.0: Supabase cloud (OPTIONAL) — PostgREST for memory/settings sync.
 *
 * Local-first by design: if Supabase is not configured, not reachable, or
 * fails, Jarvis keeps working 100% locally (memory, GitHub sync, everything)
 * and just says so. Nothing depends on Supabase. Setup: Supabase dashboard
 * -> create project -> SQL: create table jarvis_sync (id text primary key,
 * data jsonb, updated_at timestamptz default now()); Settings -> Supabase
 * -> Project URL + anon key. Realtime here means periodic sync (every 15 min
 * background check + on demand) — not a live websocket.
 */
object SupabaseSync {

    fun isConfigured(): Boolean =
        Prefs.supabaseUrl.trim().isNotBlank() && Prefs.supabaseAnonKey.trim().isNotBlank()

    private fun base(): String = Prefs.supabaseUrl.trim().trimEnd('/')

    private fun headers(conn: HttpURLConnection) {
        conn.setRequestProperty("apikey", Prefs.supabaseAnonKey.trim())
        conn.setRequestProperty("Authorization", "Bearer ${Prefs.supabaseAnonKey.trim()}")
        conn.setRequestProperty("Content-Type", "application/json")
    }

    fun status(): String = if (!isConfigured()) {
        "Supabase connect kora nei — Jarvis local-e cholche (memory, GitHub sync sob thik ache). Connect korte Settings > Supabase-e Project URL + anon key bosao."
    } else {
        "Supabase configured: ${base()} — last pull: ${Prefs.lastSupabasePull.ifBlank { "kokhono na" }}. Kono problem hole Jarvis local-e cholbe, kichu theme nei."
    }

    fun syncUp(ctx: Context, cb: (String) -> Unit) {
        Thread {
            val msg = runCatching { doSyncUp(ctx) }.getOrElse { "Supabase sync hoy ni: ${it.message} — Jarvis local-e cholche, kichu haray ni." }
            cb(msg)
        }.start()
    }

    private fun doSyncUp(ctx: Context): String {
        if (!isConfigured()) return "Supabase connect kora nei — Settings > Supabase-e Project URL + anon key bosao, tarpor abar bolo."
        val rows = JSONArray()
        val mem = runCatching { com.jarvis.ai.core.Memory.exportMemoryJson() }.getOrNull()
        val hist = runCatching { com.jarvis.ai.core.Memory.exportHistoryJson() }.getOrNull()
        val settings = runCatching { SyncManager.settingsJson() }.getOrNull()
        if (mem != null) rows.put(JSONObject().put("id", "memory").put("data", JSONObject().put("facts", JSONArray(mem))))
        if (hist != null) rows.put(JSONObject().put("id", "history").put("data", JSONObject().put("messages", JSONArray(hist))))
        if (settings != null) rows.put(JSONObject().put("id", "settings").put("data", JSONObject(settings)))
        if (rows.length() == 0) return "Sync korar moto kichu pelam na."
        val conn = (URL("${base()}/rest/v1/jarvis_sync").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            headers(this)
            setRequestProperty("Prefer", "resolution=merge-duplicates")
            connectTimeout = 15000; readTimeout = 15000
        }
        conn.outputStream.use { it.write(rows.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8)?.take(300) }.getOrNull() ?: ""
            return "Supabase sync hoy ni (HTTP $code): $err — Jarvis local-e cholche, kichu haray ni. Table 'jarvis_sync' ache kina dekho (id text PK, data jsonb)."
        }
        Prefs.lastSupabasePull = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ENGLISH).format(java.util.Date())
        return "✅ Supabase-e sync kore dilam: ${rows.length()} ta item (memory, history, settings) — ${base()}"
    }

    fun syncDown(ctx: Context, cb: (String) -> Unit) {
        Thread {
            val msg = runCatching { doSyncDown(ctx) }.getOrElse { "Supabase theke ante parlam na: ${it.message} — Jarvis local-e cholche, kichu haray ni." }
            cb(msg)
        }.start()
    }

    private fun doSyncDown(ctx: Context): String {
        if (!isConfigured()) return "Supabase connect kora nei — Settings > Supabase-e Project URL + anon key bosao."
        val conn = (URL("${base()}/rest/v1/jarvis_sync?select=id,data,updated_at").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            headers(this)
            connectTimeout = 15000; readTimeout = 15000
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8)?.take(300) }.getOrNull() ?: ""
            return "Supabase theke ante parlam na (HTTP $code): $err — table 'jarvis_sync' ache kina dekho."
        }
        val text = conn.inputStream.readBytes().toString(Charsets.UTF_8)
        val arr = JSONArray(text)
        if (arr.length() == 0) return "Supabase-e kichu save kora nei — age 'supabase sync up' bolo."
        val dir = File(ctx.getExternalFilesDir(null), "workspace/supabase").apply { mkdirs() }
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id")
            File(dir, "$id.json").writeText(o.opt("data")?.toString() ?: "{}")
            names += "$id (${o.optString("updated_at", "")})"
        }
        Prefs.lastSupabasePull = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ENGLISH).format(java.util.Date())
        return "✅ Supabase theke anlam: ${names.joinToString(", ")} — workspace/supabase/-e save kore rakhlam. Settings apply korte 'restore preferences' (GitHub sync) ba manually dekho."
    }

    /** Background periodic check (watchdog tick) — no-op when not configured. */
    fun maybePull(ctx: Context) {
        if (!isConfigured()) return
        Thread {
            runCatching {
                val conn = (URL("${base()}/rest/v1/jarvis_sync?select=id,updated_at&order=updated_at.desc&limit=1").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    headers(this)
                    connectTimeout = 10000; readTimeout = 10000
                }
                if (conn.responseCode !in 200..299) return@Thread
                val arr = JSONArray(conn.inputStream.readBytes().toString(Charsets.UTF_8))
                if (arr.length() == 0) return@Thread
                val latest = arr.getJSONObject(0).optString("updated_at", "")
                if (latest.isNotBlank() && latest != Prefs.lastSupabasePull) {
                    com.jarvis.ai.core.BackgroundWorker.notifyDone(ctx, "☁️ Supabase-e notun sync ache", "Cloud-e notun data ache — 'supabase sync down' bolle anbo.")
                    Prefs.lastSupabasePull = latest
                }
            }
        }.start()
    }
}
