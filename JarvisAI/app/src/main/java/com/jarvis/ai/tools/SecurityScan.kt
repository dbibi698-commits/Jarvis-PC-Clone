package com.jarvis.ai.tools

import android.app.admin.DevicePolicyManager
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * v1.3.9 — Advanced security suite: an on-device audit, no root needed.
 * Checks the screen lock, storage encryption, unknown-sources setting, and
 * which installed apps hold dangerous permissions (SMS, mic, camera, precise
 * location, contacts, call log). Reports the worst offenders so the user can
 * revoke them. This is a permission + settings audit — not an antivirus, and
 * it never claims to be one.
 */
object SecurityScan {
    private val DANGEROUS = listOf(
        "android.permission.READ_SMS" to "SMS read",
        "android.permission.RECEIVE_SMS" to "SMS receive",
        "android.permission.SEND_SMS" to "SMS send",
        "android.permission.RECORD_AUDIO" to "microphone",
        "android.permission.CAMERA" to "camera",
        "android.permission.ACCESS_FINE_LOCATION" to "precise location",
        "android.permission.READ_CONTACTS" to "contacts",
        "android.permission.READ_CALL_LOG" to "call log",
        "android.permission.READ_MEDIA_IMAGES" to "photos",
    )

    fun scan(ctx: Context): String {
        val sb = StringBuilder("🛡 Security scan:\n")
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        sb.appendLine("- Screen lock: " + if (km.isDeviceSecure) "✅ ON" else "❌ OFF — lock screen bosao (Settings > Security), na hole je keu phone khulte parbe!")
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val enc = dpm.storageEncryptionStatus
        sb.appendLine("- Storage encryption: " + if (enc == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE || enc == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER) "✅ Active" else "⚠️ Not active ($enc)")
        val unknown = runCatching { Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1 }.getOrDefault(false)
        sb.appendLine("- Unknown sources (APK install baire theke): " + if (unknown) "⚠️ ON — sudhu trusted APK install koro" else "✅ OFF")
        // permission audit
        val pm = ctx.packageManager
        val apps = runCatching { pm.getInstalledApplications(PackageManager.GET_META_DATA) }.getOrDefault(emptyList())
        val offenders = mutableListOf<Triple<String, Int, List<String>>>()
        for (app in apps) {
            if (app.packageName == ctx.packageName) continue
            val held = DANGEROUS.filter { (perm, _) -> pm.checkPermission(perm, app.packageName) == PackageManager.PERMISSION_GRANTED }
            if (held.size >= 2) {
                val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                offenders += Triple(label, held.size, held.map { it.second })
            }
        }
        offenders.sortByDescending { it.second }
        sb.appendLine("- Apps scanned: ${apps.size}, apps with 2+ dangerous permissions: ${offenders.size}")
        offenders.take(8).forEach { (label, n, perms) ->
            sb.appendLine("  ⚠️ $label ($n): ${perms.joinToString(", ")}")
        }
        if (offenders.isEmpty()) sb.appendLine("  ✅ Kono app-er kache 2+ dangerous permission nei.")
        sb.appendLine("\nJarvis nije kono dangerous permission bina karone chay na — ja ache, Settings > Permissions-e dekhte paro.")
        return sb.toString()
    }
}
