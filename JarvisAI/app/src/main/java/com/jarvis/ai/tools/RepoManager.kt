package com.jarvis.ai.tools

import android.content.Context
import android.os.Environment
import com.jarvis.ai.core.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * v1.4.0: create a NEW GitHub repository and push files to it — including
 * unzipping a ZIP file on the phone and pushing every text file inside.
 * Needs a GitHub token (Settings > Connect). The new repo becomes the active
 * repo in Settings, so later pushes go there too.
 */
object RepoManager {

    private val BINARY_EXTS = setOf(
        "apk", "aab", "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "mp4", "mp3",
        "wav", "ogg", "webm", "pdf", "zip", "jar", "aar", "dex", "so", "bin", "dat",
        "ttf", "otf", "woff", "woff2", "eot", "sqlite", "db", "gz", "tar", "7z", "rar",
        "class", "pyc", "exe", "dll"
    )

    fun createRepo(ctx: Context, name: String, private: Boolean, description: String, cb: (String) -> Unit) {
        Thread {
            val msg = runCatching { doCreateRepo(name, private, description) }
                .getOrElse { "❌ Repo banate parlam na: ${it.message}" }
            cb(msg)
        }.start()
    }

    private fun doCreateRepo(name: String, private: Boolean, description: String): String {
        val clean = name.trim()
        if (clean.isBlank()) return "Repo-r naam bolo — jemon: \"my-website\"."
        if (!Regex("^[A-Za-z0-9._-]+$").matches(clean)) return "Repo-r naam-e sudhu letter, number, -, _ ar . cholbe — \"$clean\" hobe na."
        val token = Prefs.githubToken.trim()
        if (token.isBlank()) return "GitHub token lagbe — Settings > Connect-e GitHub connect koro (Authorize GitHub), tarpor abar bolo."
        val body = JSONObject()
            .put("name", clean)
            .put("description", description)
            .put("private", private)
            .put("auto_init", true)
        val conn = (URL("https://api.github.com/user/repos").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val resp = runCatching {
            (if (code in 200..299) conn.inputStream else conn.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        }.getOrDefault("")
        if (code == 201) {
            val obj = JSONObject(resp)
            val fullName = obj.optString("full_name")
            val htmlUrl = obj.optString("html_url")
            val branch = obj.optString("default_branch", "main").ifBlank { "main" }
            Prefs.githubRepo = fullName
            Prefs.githubBranch = branch
            return "✅ Notun repo baniye dilam: $fullName\n🔗 $htmlUrl\nEkhon theke ei repo-tei push hobe. ZIP push korte \"push zip\" bolo."
        }
        if (code == 422) return "❌ \"$clean\" naam-e repo age thekei ache (ba naam-ta neoa jabe na) — onno naam bolo."
        if (code == 401) return "❌ GitHub token-ta thik noy — Settings > Connect-e notun kore Authorize GitHub koro."
        return "❌ Repo banano jay ni (HTTP $code): ${resp.take(300)}"
    }

    fun pushZip(ctx: Context, zipName: String, folder: String, cb: (String) -> Unit) {
        Thread {
            val msg = runCatching { doPushZip(ctx, zipName.trim(), folder.trim()) }
                .getOrElse { "❌ ZIP push korte parlam na: ${it.message}" }
            cb(msg)
        }.start()
    }

    private fun doPushZip(ctx: Context, zipName: String, folder: String): String {
        if (Prefs.githubToken.trim().isBlank() || !Prefs.githubRepo.contains("/")) {
            return "GitHub token + repo lagbe — age \"create repo\" bolo, ba Settings > Connect-e GitHub connect koro."
        }
        val zip = findZip(ctx, zipName) ?: return "ZIP file pelam na: \"$zipName\" — file-ta workspace-e, Downloads-e ba Downloads > JarvisAI-te rakho, tarpor abar bolo."
        val prefix = folder.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val base = zip.nameWithoutExtension
        val outDir = File(ctx.getExternalFilesDir(null), "workspace/unzipped/$base").apply { mkdirs() }
        var pushed = 0
        var failed = 0
        var skipped = 0
        var total = 0
        val failedNames = mutableListOf<String>()
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val name = entry.name
                val isDir = entry.isDirectory || name.endsWith("/")
                val ext = name.substringAfterLast('.', "").lowercase()
                if (!isDir && !name.startsWith("__MACOSX/") && !name.contains("/.git/") && !name.startsWith(".git/")) {
                    total++
                    if (total > 100) { skipped++; entry = zin.nextEntry; continue }
                    if (entry.size > 1_000_000 || ext in BINARY_EXTS) {
                        skipped++
                    } else {
                        val bytes = zin.readBytes()
                        val text = bytes.toString(Charsets.UTF_8)
                        // also save the extracted file on the phone
                        runCatching {
                            val f = File(outDir, name)
                            f.parentFile?.mkdirs()
                            f.writeText(text)
                        }
                        val res = PublishHelper.publishFileSync(prefix + name, text)
                        if (res.startsWith("Published!")) pushed++ else { failed++; failedNames += name }
                    }
                }
                entry = zin.nextEntry
            }
        }
        val repoUrl = "https://github.com/${Prefs.githubRepo}"
        val sb = StringBuilder("📦 ZIP unzip kore GitHub-e push korlam: ${zip.name}\nRepo: $repoUrl\n✅ Pushed: $pushed file")
        if (skipped > 0) sb.append("\n⏭️ Skipped: $skipped (binary / 1MB-r boro / 100 file-r beshi)")
        if (failed > 0) sb.append("\n❌ Failed: $failed (${failedNames.take(5).joinToString(", ")})")
        sb.append("\n📁 Phone-eo unzip kore rakhlam: workspace/unzipped/$base/")
        return sb.toString()
    }

    private fun findZip(ctx: Context, zipName: String): File? {
        val name = if (zipName.endsWith(".zip", true)) zipName else "$zipName.zip"
        if (zipName.startsWith("/")) {
            val f = File(zipName)
            if (f.exists()) return f
        }
        val dirs = listOf(
            File(ctx.getExternalFilesDir(null), "workspace"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "JarvisAI"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        )
        for (d in dirs) {
            val f = File(d, name)
            if (f.exists()) return f
            // bare name match: any .zip starting with the name
            d.listFiles()?.firstOrNull { it.name.startsWith(zipName.trim(), true) && it.name.endsWith(".zip", true) }?.let { return it }
        }
        return null
    }
}
