package com.jarvis.ai.tools

import android.content.Context
import com.jarvis.ai.core.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * v1.3.9 — Efficient self-update: checks whether the GitHub Actions build
 * finished a NEWER APK than the one installed, and downloads it in one step.
 * Compares the latest successful workflow run's time with the app's install /
 * update time. The real update loop stays the same (code -> GitHub ->
 * build_apk -> install); this just makes checking for it one voice command.
 */
object UpdateChecker {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun check(ctx: Context, cb: (String) -> Unit) {
        if (Prefs.githubToken.isBlank() || Prefs.githubRepo.isBlank()) {
            cb("Update check korte GitHub connect kora lagbe — Settings > Connect theke GitHub connect koro.")
            return
        }
        Thread {
            val msg = runCatching {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/${Prefs.githubRepo}/actions/runs?per_page=5")
                    .header("Authorization", "Bearer ${Prefs.githubToken}")
                    .header("Accept", "application/vnd.github+json").build()
                val body = http.newCall(req).execute().use { r -> r.body?.string() ?: "" }
                val runs = JSONObject(body).optJSONArray("workflow_runs")
                var latest: JSONObject? = null
                if (runs != null) {
                    for (i in 0 until runs.length()) {
                        val r = runs.getJSONObject(i)
                        if (r.optString("status") == "completed" && r.optString("conclusion") == "success") { latest = r; break }
                    }
                }
                if (latest == null) return@runCatching "Kono successful build pelam na — age 'build apk' bolo, build sesh hole abar check koro."
                val updatedAt = latest.optString("updated_at")
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val runTime = runCatching { sdf.parse(updatedAt)?.time ?: 0L }.getOrDefault(0L)
                val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                val installedAt = info.lastUpdateTime
                if (runTime > installedAt) {
                    "NEW BUILD available (build sesh: ${latest.optString("updated_at")}) — APK download korchi…"
                } else {
                    "✅ Tumi latest build-ei acho — notun build nei."
                }
            }.getOrElse { "Update check holo na: ${it.message}" }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (msg.startsWith("NEW BUILD")) {
                    ApkBuilder.check(ctx) { dl -> cb("$msg\n$dl") }
                } else cb(msg)
            }
        }.start()
    }
}
