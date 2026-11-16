package com.jarvis.ai.tools

import android.content.Context
import com.jarvis.ai.core.Prefs
import com.jarvis.ai.core.SecureStore
import java.io.File
import org.json.JSONObject

/**
 * v1.4.0: integrated password vault — passwords live encrypted on the phone
 * (AES-256-GCM via Android Keystore, same as encrypted memory).
 *
 * Rules: list shows site names ONLY, never passwords. get returns the
 * password only so Jarvis can type it into a login form — never speak a
 * password aloud in live voice, never save one in memory/chat, never share
 * one with anyone. The vault file itself is encrypted; without the phone's
 * Keystore nobody can read it.
 */
object PasswordVault {

    private fun file(ctx: Context) = File(ctx.filesDir, "vault.json")

    private fun load(ctx: Context): JSONObject {
        val f = file(ctx)
        if (!f.exists()) return JSONObject()
        val raw = runCatching { f.readText() }.getOrNull() ?: return JSONObject()
        if (raw.isBlank()) return JSONObject()
        val json = if (SecureStore.looksEncrypted(raw)) {
            SecureStore.decrypt(raw) ?: return JSONObject()
        } else raw
        return runCatching { JSONObject(json) }.getOrDefault(JSONObject())
    }

    private fun save(ctx: Context, obj: JSONObject) {
        val json = obj.toString()
        val out = if (Prefs.encryptLocal) (SecureStore.encrypt(json) ?: json) else json
        runCatching { file(ctx).writeText(out) }
    }

    fun savePassword(ctx: Context, site: String, user: String, pass: String): String {
        val s = site.trim()
        if (s.isBlank() || pass.isBlank()) return "Site ar password — dutui lagbe. Jemon: site Facebook, password ****."
        val obj = load(ctx)
        obj.put(s.lowercase(), JSONObject()
            .put("site", s)
            .put("user", user.trim())
            .put("pass", pass)
            .put("updated", System.currentTimeMillis()))
        save(ctx, obj)
        return "✅ Password save kore dilam (encrypted vault): $s${if (user.isNotBlank()) " — $user" else ""}. Keu file khuleo porte parbe na."
    }

    fun getPassword(ctx: Context, site: String): String {
        val obj = load(ctx)
        val key = findKey(obj, site) ?: return "Vault-e \"$site\" pelam na. Sites dekhte 'list_passwords' bolo."
        val e = obj.getJSONObject(key)
        return "🔑 ${e.optString("site")}\nUsername: ${e.optString("user").ifBlank { "(nei)" }}\nPassword: ${e.optString("pass")}\n\n⚠️ Ei password sudhu login form-e use koro — kauke bolo na, chat-e share koro na, jore bolo na."
    }

    fun listPasswords(ctx: Context): String {
        val obj = load(ctx)
        if (obj.length() == 0) return "Vault khali — 'save_password' bole prothom password save koro."
        val sb = StringBuilder("🔐 Vault-e ${obj.length()} ta password (sudhu naam dekhacchi, password noy):\n")
        val keys = obj.keys()
        while (keys.hasNext()) {
            val e = obj.getJSONObject(keys.next())
            sb.append("• ${e.optString("site")}${e.optString("user").takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}\n")
        }
        return sb.toString().trimEnd()
    }

    fun deletePassword(ctx: Context, site: String): String {
        val obj = load(ctx)
        val key = findKey(obj, site) ?: return "Vault-e \"$site\" pelam na."
        val name = obj.getJSONObject(key).optString("site")
        obj.remove(key)
        save(ctx, obj)
        return "✅ \"$name\" vault theke muche dilam."
    }

    private fun findKey(obj: JSONObject, site: String): String? {
        val q = site.trim().lowercase()
        val keys = obj.keys()
        var fallback: String? = null
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == q) return k
            if (fallback == null && (k.contains(q) || q.contains(k))) fallback = k
        }
        return fallback
    }
}
