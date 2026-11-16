package com.jarvis.ai.tools

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v1.3.9 — Advanced cross-platform API connectivity: call ANY REST API from
 * Jarvis — Zapier, IFTTT, Notion, Telegram bot, your own server, anything
 * with an HTTP endpoint. Method + URL + headers (JSON) + body. The response
 * (status + first 3000 chars) comes back to the chat. Secrets stay in the
 * user's own headers; Jarvis never logs them.
 */
object ApiConnector {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun call(method: String, url: String, headersJson: String, body: String, cb: (String) -> Unit) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            cb("URL-ta http:// ba https:// diye shuru hote hobe."); return
        }
        Thread {
            val msg = runCatching {
                val m = method.uppercase().ifBlank { "GET" }
                val rb = Request.Builder().url(url)
                runCatching {
                    val h = JSONObject(headersJson)
                    h.keys().forEach { k -> rb.header(k, h.getString(k)) }
                }
                val bodyObj = if (m == "GET" || m == "DELETE") null
                else (body.ifBlank { "{}" }).toRequestBody("application/json".toMediaType())
                val req = when (m) {
                    "POST" -> rb.post(bodyObj!!)
                    "PUT" -> rb.put(bodyObj!!)
                    "PATCH" -> rb.patch(bodyObj!!)
                    "DELETE" -> rb.delete(bodyObj)
                    else -> rb.get()
                }.build()
                http.newCall(req).execute().use { r ->
                    val text = r.body?.string() ?: ""
                    "HTTP ${r.code}\n${text.take(3000)}"
                }
            }.getOrElse { "API call holo na: ${it.message}" }
            android.os.Handler(android.os.Looper.getMainLooper()).post { cb(msg) }
        }.start()
    }
}
