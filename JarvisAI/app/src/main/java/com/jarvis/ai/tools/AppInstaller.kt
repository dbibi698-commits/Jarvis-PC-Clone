package com.jarvis.ai.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jarvis.ai.service.JarvisAccessibilityService
import com.jarvis.ai.ui.BrowserActivity
import java.io.File
import java.net.URLEncoder

/**
 * v1.4.0: install apps the way a human does — open the Play Store page (or an
 * .apk file) and tap "Install" on the screen with the accessibility service.
 * There is no silent-install API for a normal app (Google does not allow it;
 * only company MDM / device-owner phones can install silently), so the screen
 * tap is the honest way to do it. Needs Accessibility ON.
 */
object AppInstaller {

    private val INSTALL_LABELS = listOf("Install", "ইনস্টল করুন", "INSTALL", "Update", "আপডেট করুন")

    fun install(ctx: Context, query: String, cb: (String) -> Unit) {
        Thread {
            val msg = runCatching { doInstall(ctx, query.trim()) }
                .getOrElse { "❌ Install korte giye problem holo: ${it.message}" }
            cb(msg)
        }.start()
    }

    private fun doInstall(ctx: Context, query: String): String {
        if (query.isBlank()) return "Kon app install korbo? Naam ba package ID bolo — jemon: \"WhatsApp install koro\"."
        if (!JarvisAccessibilityService.isOn) {
            return "Accessibility OFF ache — Jarvis screen-e tap korte parbe na. Settings > Accessibility-te 'Jarvis AI' ON koro, tarpor abar bolo."
        }
        val acc = JarvisAccessibilityService.instance
            ?: return "Accessibility service pelam na — Settings > Accessibility-te 'Jarvis AI' ON koro."
        val apk = findApk(ctx, query)
        if (apk != null) return installApk(ctx, apk, acc)
        val before = installedPackages(ctx)
        val pkgRe = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$")
        if (pkgRe.matches(query)) {
            if (runCatching { ctx.packageManager.getPackageInfo(query, 0) }.isSuccess) {
                return "✅ $query age thekei install ache — khule debo? \"open $query\" bolo."
            }
            openPlayDetails(ctx, query)
            Thread.sleep(3500)
            if (!tapInstall(acc)) return "Play Store page khule diyechi, kintu 'Install' button-ta pelam na — tumi ekbar tap kore dao."
            return waitForInstall(ctx, before, query)
        }
        openPlaySearch(ctx, query)
        Thread.sleep(3500)
        if (acc.clickText(query)) Thread.sleep(3000)
        if (!tapInstall(acc)) {
            return "Play Store-e '$query' khule diyechi, kintu 'Install' button pelam na — result theke app-ta beche Install-e tap koro."
        }
        return waitForInstall(ctx, before, null)
    }

    private fun tapInstall(acc: JarvisAccessibilityService): Boolean {
        for (label in INSTALL_LABELS) {
            if (acc.clickText(label)) return true
        }
        return false
    }

    private fun waitForInstall(ctx: Context, before: Set<String>, targetPkg: String?): String {
        repeat(30) {
            Thread.sleep(2000)
            val now = installedPackages(ctx)
            if (targetPkg != null) {
                if (now.contains(targetPkg)) return "✅ Install hoye geche! $targetPkg ekhon phone-e ache — khule debo?"
            } else {
                val added = now - before
                if (added.isNotEmpty()) return "✅ Install hoye geche! ${added.first()} ekhon phone-e ache — khule debo?"
            }
        }
        return "Install-e tap kore diyechi — download/install cholche (boro app-e somoy lage). Play Store-e giye progress dekhte paro."
    }

    private fun installApk(ctx: Context, apk: File, acc: JarvisAccessibilityService): String {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { ctx.startActivity(i) }
            return "Jarvis-ke ekbar 'Install unknown apps' permission dite hobe — Settings khule diyechi, Allow kore dao, tarpor abar bolo."
        }
        val before = installedPackages(ctx)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { ctx.startActivity(i) }.onFailure {
            return "APK installer khulte parlam na: ${it.message}"
        }
        Thread.sleep(3000)
        if (!tapInstall(acc)) return "Installer khule diyechi, kintu 'Install' button-ta pelam na — tumi ekbar tap kore dao."
        return waitForInstall(ctx, before, null)
    }

    private fun findApk(ctx: Context, query: String): File? {
        val names = mutableListOf<String>()
        if (query.endsWith(".apk", true)) names += query else names += "$query.apk"
        val dirs = listOf(
            File(ctx.getExternalFilesDir(null), "workspace"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "JarvisAI"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        )
        if (query.startsWith("/")) {
            val f = File(query)
            if (f.exists() && f.name.endsWith(".apk", true)) return f
        }
        for (n in names) for (d in dirs) {
            val f = File(d, n)
            if (f.exists()) return f
        }
        return null
    }

    private fun openPlayDetails(ctx: Context, pkg: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
            setPackage("com.android.vending")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!runCatching { ctx.startActivity(market) }.isSuccess) {
            openWeb(ctx, "https://play.google.com/store/apps/details?id=$pkg&hl=en")
        }
    }

    private fun openPlaySearch(ctx: Context, query: String) {
        val q = URLEncoder.encode(query, "UTF-8")
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$q")).apply {
            setPackage("com.android.vending")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!runCatching { ctx.startActivity(market) }.isSuccess) {
            openWeb(ctx, "https://play.google.com/store/search?q=$q&c=apps&hl=en")
        }
    }

    private fun openWeb(ctx: Context, url: String) {
        val i = Intent(ctx, BrowserActivity::class.java).apply {
            putExtra("url", url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(i) }
    }

    private fun installedPackages(ctx: Context): Set<String> = runCatching {
        ctx.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
    }.getOrDefault(emptySet())
}
