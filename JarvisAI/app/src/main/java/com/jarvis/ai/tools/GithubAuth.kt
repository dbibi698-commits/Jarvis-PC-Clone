package com.jarvis.ai.tools

import com.jarvis.ai.core.GeminiChat
import com.jarvis.ai.core.Prefs
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

/**
 * GitHub one-click authorize — OAuth device flow (v1.3.2).
 *
 * No token copy-paste: the user taps "Authorize GitHub", Jarvis shows a short
 * code, the user opens github.com/device and types the code once, and Jarvis
 * gets its own access token (stored in Prefs.githubToken, so publish/deploy
 * and the repo connection work right away).
 *
 * Needs a GitHub OAuth App Client ID — free, one-time setup:
 * github.com/settings/developers → "New OAuth App" → any name, Homepage URL
 * https://github.com → copy the Client ID into Jarvis Settings.
 */
object GithubAuth {
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int
    )

    /** Step 1: ask GitHub for a user code. Returns the code, or an error string when it fails. */
    fun requestDeviceCode(clientId: String): Pair<DeviceCode?, String?> {
        return runCatching {
            val body = FormBody.Builder().add("client_id", clientId).add("scope", "repo").build()
            val req = Request.Builder().url("https://github.com/login/device/code")
                .header("Accept", "application/json").post(body).build()
            GeminiChat.http.newCall(req).execute().use { r ->
                val j = JSONObject(r.body!!.string())
                if (!j.has("device_code")) {
                    val err = j.optString("error")
                    val msg = when (err) {
                        "device_flow_disabled" -> "Device Flow OFF ache — GitHub app page-e giye 'Enable Device Flow' ON koro, tarpor abar try koro"
                        "Not Found", "incorrect_client_credentials" -> "Client ID thik na — OAuth App page theke Client ID (Ov23... diye shuru) copy kore bosao"
                        else -> "GitHub theke code pelam na${if (err.isNotBlank()) ": $err" else " — internet check koro"}"
                    }
                    return Pair(null, msg)
                }
                Pair(
                    DeviceCode(
                        j.getString("device_code"),
                        j.getString("user_code"),
                        j.optString("verification_uri", "https://github.com/device"),
                        j.optInt("expires_in", 900),
                        j.optInt("interval", 5)
                    ), null
                )
            }
        }.getOrElse { Pair(null, "Internet nai ba GitHub-e pouchano jachche na: ${it.message}") }
    }

    /** Step 2: poll until the user authorizes (or the code expires). Returns the access token or null. */
    fun pollForToken(clientId: String, deviceCode: String, intervalSec: Int, expiresInSec: Int): String? {
        val deadline = System.currentTimeMillis() + expiresInSec * 1000L
        var interval = intervalSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval)
            val res = runCatching {
                val body = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("device_code", deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build()
                val req = Request.Builder().url("https://github.com/login/oauth/access_token")
                    .header("Accept", "application/json").post(body).build()
                GeminiChat.http.newCall(req).execute().use { r -> JSONObject(r.body!!.string()) }
            }.getOrNull() ?: continue
            when (res.optString("error")) {
                "" -> { val t = res.optString("access_token"); if (t.isNotBlank()) return t }
                "authorization_pending" -> { /* user hasn't typed the code yet */ }
                "slow_down" -> interval += 5000
                else -> return null // expired_token / access_denied / incorrect_device_code
            }
        }
        return null
    }

    /** Step 3: check the token against the API and store token + username in Prefs. Returns the login or null. */
    fun finishWithToken(token: String): String? {
        return runCatching {
            val req = Request.Builder().url("https://api.github.com/user")
                .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").build()
            GeminiChat.http.newCall(req).execute().use { r ->
                val j = JSONObject(r.body!!.string())
                if (!j.has("login")) return null
                val login = j.getString("login")
                Prefs.githubToken = token
                Prefs.githubUser = login
                login
            }
        }.getOrNull()
    }
}
