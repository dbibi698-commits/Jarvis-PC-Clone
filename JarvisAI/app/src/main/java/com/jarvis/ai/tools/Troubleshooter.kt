package com.jarvis.ai.tools

import android.app.KeyguardManager
import android.content.Context
import com.jarvis.ai.App
import com.jarvis.ai.core.OfflineMode
import com.jarvis.ai.core.Prefs
import com.jarvis.ai.core.ResourceManager
import com.jarvis.ai.core.SelfHeal
import com.jarvis.ai.service.JarvisAccessibilityService
import java.io.File

/**
 * v1.3.9 — Automated system troubleshooting and repair protocol.
 * Runs the full check-up (internet, storage, memory, battery, crashes, screen
 * lock, API key, GitHub, accessibility), FIXES what it can fix itself
 * (cache clean, history trim, low-battery adaptations, safe mode after
 * repeated crashes) and reports what needs the user. This is the "repair"
 * side of diagnostics — diagnostics finds, troubleshoot fixes.
 */
object Troubleshooter {
    fun diagnoseAndFix(ctx: Context): String {
        val fixed = mutableListOf<String>()
        val needsUser = mutableListOf<String>()
        // internet
        if (OfflineMode.isOffline(ctx)) needsUser += "Internet nei — Wi-Fi / mobile data on koro (offline-e sudhu basic kaj cholbe)."
        // storage + memory
        val s = ResourceManager.status(ctx)
        if (s.storageFreeMB < 500) {
            SelfHeal.trimHistory()
            ResourceManager.cleanCache(ctx)
            fixed += "Storage kom chilo (${s.storageFreeMB}MB free) — purono history trim + cache clean kore dilam."
        }
        if (s.lowMemory) {
            val freed = ResourceManager.cleanCache(ctx)
            fixed += "Memory kom chilo — cache clean kore ${freed}MB khali korlam."
        }
        // battery
        if (s.battery in 1..19 && !s.charging) {
            ResourceManager.adapt(ctx)
            fixed += "Battery ${s.battery}% — disco + shake off kore battery bachalam."
        }
        // crashes
        val crash = File(App.instance.filesDir, "crash-last.txt")
        if (crash.exists() && Prefs.crashCount > 0) {
            if (Prefs.crashCount >= 3 && !Prefs.safeMode) {
                Prefs.safeMode = true; Prefs.wakeEnabled = false; Prefs.discoEnabled = false; Prefs.gestureShakeEnabled = false
                fixed += "Jarvis ${Prefs.crashCount} bar crash koreche — safe mode on kore risky features off korlam."
            } else needsUser += "Jarvis er age crash koreche (${Prefs.crashCount} bar) — crash log Downloads > JarvisAI-te ache, pathate paro."
        }
        // screen lock
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) needsUser += "Phone-er screen lock OFF — Settings > Security theke PIN/fingerprint bosao."
        // API key
        if (Prefs.apiKey.isBlank()) needsUser += "Gemini API key bosano nei — Settings > AI API Keys-e key bosao, na hole ami online kotha bolte parbo na."
        // GitHub
        if (Prefs.githubToken.isBlank()) needsUser += "GitHub connect kora nei — deploy/publish/sync-er jonno Settings > Connect-e GitHub connect koro."
        // accessibility
        if (!JarvisAccessibilityService.isOn) needsUser += "Accessibility OFF — screen control (tap/scroll/read) er jonno Settings > Permissions-e Jarvis Accessibility on koro."
        // notification access (WhatsApp/Instagram)
        val notifOn = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        if (!notifOn) needsUser += "Notification access OFF — WhatsApp/Instagram connect-er jonno Settings > Permissions-e allow koro."
        val sb = StringBuilder("🔧 Troubleshooting report:\n")
        if (fixed.isEmpty()) sb.appendLine("✅ Kono auto-fix laglo na — ja check korlam sob thik ache.")
        else fixed.forEach { sb.appendLine("✅ Fixed: $it") }
        if (needsUser.isNotEmpty()) {
            sb.appendLine("\n👉 Tomar korte hobe:")
            needsUser.forEach { sb.appendLine("- $it") }
        } else sb.appendLine("\n🎉 Sob thik ache — kono problem pelam na.")
        return sb.toString()
    }
}
