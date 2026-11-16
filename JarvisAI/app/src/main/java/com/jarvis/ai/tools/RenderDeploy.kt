package com.jarvis.ai.tools

import com.jarvis.ai.core.GeminiChat
import com.jarvis.ai.core.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Render deploy (v1.3.4) — manual deploy on Render, two ways:
 *  1. API: Render API key (rnd_…) + Service ID (srv_…) in Settings.
 *     POST /v1/services/{id}/deploys triggers a deploy; GET .../deploys
 *     reads the latest status.
 *  2. Deploy Hook: a hook URL from the Render dashboard (service → Settings
 *     → Deploy Hook). Just POST to it, no key needed.
 * Screen-touch deploy also works: open the Render dashboard in the in-app
 * browser and Jarvis taps "Manual Deploy" itself — but the API is faster
 * and does not depend on the dashboard layout.
 */
object RenderDeploy {
    private const val API = "https://api.render.com/v1"

    private fun authReq(url: String, method: String = "GET", body: String? = null): Request {
        val b = Request.Builder().url(url)
            .header("Authorization", "Bearer ${Prefs.renderApiKey.trim()}")
            .header("Accept", "application/json")
        return when (method) {
            "POST" -> b.post((body ?: "{}").toRequestBody("application/json".toMediaType())).build()
            else -> b.build()
        }
    }

    /** Trigger a manual deploy now. Runs on a background thread, result on the calling thread. */
    fun deploy(cb: (String) -> Unit) {
        Thread {
            val hook = Prefs.renderDeployHook.trim()
            if (hook.isNotBlank()) {
                val msg = runCatching {
                    val req = Request.Builder().url(hook).post("".toRequestBody("application/json".toMediaType())).build()
                    GeminiChat.http.newCall(req).execute().use { r ->
                        if (r.code in 200..299) "✓ Render deploy triggered via Deploy Hook! Dashboard-e giye status dekho: https://dashboard.render.com"
                        else "❌ Deploy Hook failed: HTTP ${r.code}"
                    }
                }.getOrElse { "❌ Deploy Hook failed: ${it.message}" }
                cb(msg)
                return@Thread
            }
            val key = Prefs.renderApiKey.trim()
            val id = Prefs.renderServiceId.trim()
            if (key.isBlank() || id.isBlank()) {
                cb("Render deploy korte API key + Service ID lagbe (na hole Deploy Hook URL). Settings > Hosting / Render-e bosao: key dashboard.render.com → Account Settings → API Keys theke (rnd_… diye shuru), Service ID service-er page-er URL-e thake (srv-… diye shuru).")
                return@Thread
            }
            val msg = runCatching {
                GeminiChat.http.newCall(authReq("$API/services/$id/deploys", "POST")).execute().use { r ->
                    val body = r.body!!.string()
                    if (r.code !in 200..299) {
                        val err = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
                        return@use "❌ Render deploy failed: HTTP ${r.code}${if (err.isNotBlank()) " — $err" else ""}"
                    }
                    val j = JSONObject(body)
                    val d = j.optJSONObject("deploy")
                    val deployId = d?.optString("id") ?: j.optString("id")
                    val status = d?.optString("status") ?: ""
                    "✓ Render deploy triggered! Deploy: $deployId${if (status.isNotBlank()) " ($status)" else ""}. Dashboard: https://dashboard.render.com/web/$id"
                }
            }.getOrElse { "❌ Render deploy failed: ${it.message}" }
            cb(msg)
        }.start()
    }

    /** Latest deploy status of the Render service. */
    fun status(cb: (String) -> Unit) {
        Thread {
            val key = Prefs.renderApiKey.trim()
            val id = Prefs.renderServiceId.trim()
            if (key.isBlank() || id.isBlank()) {
                cb("Render status dekhte API key + Service ID lagbe. Settings > Hosting / Render-e bosao.")
                return@Thread
            }
            val msg = runCatching {
                var serviceUrl = ""
                runCatching {
                    GeminiChat.http.newCall(authReq("$API/services/$id")).execute().use { r ->
                        if (r.code in 200..299) serviceUrl = JSONObject(r.body!!.string()).optString("serviceUrl")
                    }
                }
                GeminiChat.http.newCall(authReq("$API/services/$id/deploys?limit=1")).execute().use { r ->
                    val body = r.body!!.string()
                    if (r.code !in 200..299) return@use "❌ Render status failed: HTTP ${r.code}"
                    val arr = JSONObject(body).optJSONArray("deploys")
                    if (arr == null || arr.length() == 0) return@use "Render-e ekhono kono deploy hoyni."
                    val d = arr.getJSONObject(0).optJSONObject("deploy") ?: arr.getJSONObject(0)
                    val st = d.optString("status")
                    val commit = d.optJSONObject("commit")?.optString("id")?.take(7) ?: ""
                    val created = d.optString("createdAt")
                    "Render status: $st${if (commit.isNotBlank()) " • commit $commit" else ""}${if (created.isNotBlank()) " • $created" else ""}${if (serviceUrl.isNotBlank()) "\nLive URL: $serviceUrl" else ""}"
                }
            }.getOrElse { "❌ Render status failed: ${it.message}" }
            cb(msg)
        }.start()
    }
}
