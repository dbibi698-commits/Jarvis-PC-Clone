package com.jarvis.ai.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Finds ANY installed launchable app by fuzzy name (English, Bengali, Hindi aliases). */
object AppFinder {
    data class AppInfo(val label: String, val pkg: String)

    private val aliases = mapOf(
        "whatsapp" to "com.whatsapp", "হোয়াটসঅ্যাপ" to "com.whatsapp", "व्हाट्सएप" to "com.whatsapp",
        "whatsapp business" to "com.whatsapp.w4b",
        "youtube" to "com.google.android.youtube", "ইউটিউব" to "com.google.android.youtube", "यूट्यूब" to "com.google.android.youtube",
        "facebook" to "com.facebook.katana", "ফেসবুক" to "com.facebook.katana", "फेसबुक" to "com.facebook.katana",
        "messenger" to "com.facebook.orca", "instagram" to "com.instagram.android", "ইনস্টাগ্রাম" to "com.instagram.android",
        "tiktok" to "com.zhiliaoapp.musically", "টিকটক" to "com.zhiliaoapp.musically",
        "chatgpt" to "com.openai.chatgpt", "gemini" to "com.google.android.apps.bard", "claude" to "com.anthropic.claude",
        "perplexity" to "ai.perplexity.app.android", "telegram" to "org.telegram.messenger", "snapchat" to "com.snapchat.android",
        "pubg" to "com.pubg.imobile", "bgmi" to "com.pubg.imobile", "free fire" to "com.dts.freefireth", "ফ্রি ফায়ার" to "com.dts.freefireth",
        "chrome" to "com.android.chrome", "gmail" to "com.google.android.gm", "maps" to "com.google.android.apps.maps",
        "play store" to "com.android.vending", "spotify" to "com.spotify.music", "x" to "com.twitter.android", "twitter" to "com.twitter.android",
        "camera" to "", "ক্যামেরা" to "", "gallery" to "", "গ্যালারি" to "", "settings" to "com.android.settings", "সেটিংস" to "com.android.settings",
        "phone" to "", "dialer" to "", "contacts" to "", "calculator" to "", "clock" to "", "files" to "",
    )

    fun all(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(i, 0).map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    fun find(ctx: Context, name: String): AppInfo? {
        val q = name.lowercase().trim().removePrefix("open ").trim()
        val apps = all(ctx)
        apps.firstOrNull { it.pkg.equals(q, true) }?.let { return it }
        aliases[q]?.takeIf { it.isNotEmpty() }?.let { pkg -> apps.firstOrNull { it.pkg == pkg }?.let { return it } }
        val en = when (q) { "ক্যামেরা" -> "camera"; "গ্যালারি" -> "gallery"; "সেটিংস" -> "settings"; else -> q }
        apps.firstOrNull { it.label.equals(en, true) }?.let { return it }
        apps.firstOrNull { it.label.lowercase().startsWith(en) }?.let { return it }
        apps.firstOrNull { it.label.lowercase().contains(en) }?.let { return it }
        apps.firstOrNull { it.pkg.lowercase().contains(en.replace(" ", "")) }?.let { return it }
        if (en == "gallery") apps.firstOrNull { it.label.lowercase().let { l -> "photos" in l || "gallery" in l } }?.let { return it }
        // fuzzy: smallest edit distance
        return apps.minByOrNull { lev(it.label.lowercase(), en) }?.takeIf { lev(it.label.lowercase(), en) <= maxOf(2, en.length / 3) }
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ctx.startActivity(i); return true
    }

    fun installed(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }
}
