package com.jarvis.ai.tools

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.jarvis.ai.core.GeminiChat
import com.jarvis.ai.core.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Build an APK with Gradle + Kotlin (v1.3.6).
 *
 * Honest fact: Gradle does NOT run on a phone — no JDK, no daemon, not
 * enough RAM. The real way a phone assistant builds an APK is: write the
 * Kotlin + Gradle project, push it to GitHub, and let GitHub Actions
 * (free) build it on their servers. Jarvis then downloads the finished
 * APK to Downloads > JarvisAI. That is what this does.
 *
 * Needs: GitHub connected (token + owner/repo in Settings), and the repo
 * must be an Android Gradle project (app/build.gradle + gradlew).
 */
object ApkBuilder {
    private const val GH = "https://api.github.com"
    private const val WORKFLOW_PATH = ".github/workflows/android.yml"
    // v1.3.9: Gradle bundle (AAB) for Play Store publishing
    private const val BUNDLE_WORKFLOW_PATH = ".github/workflows/android-bundle.yml"

    private val WORKFLOW_YML = """
        name: Android APK
        on:
          workflow_dispatch:
          push:
            branches: [ main ]
        jobs:
          build:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4
              - uses: actions/setup-java@v4
                with:
                  distribution: 'temurin'
                  java-version: '17'
              - run: chmod +x gradlew
              - run: ./gradlew assembleDebug
              - uses: actions/upload-artifact@v4
                with:
                  name: app-debug
                  path: app/build/outputs/apk/debug/app-debug.apk
        """.trimIndent()

    private val BUNDLE_WORKFLOW_YML = """
        name: Android App Bundle (AAB)
        on:
          workflow_dispatch:
        jobs:
          build:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4
              - uses: actions/setup-java@v4
                with:
                  distribution: 'temurin'
                  java-version: '17'
              - run: chmod +x gradlew
              - run: ./gradlew bundleRelease
              - uses: actions/upload-artifact@v4
                with:
                  name: app-release-bundle
                  path: app/build/outputs/bundle/release/app-release.aab
        """.trimIndent()

    private fun auth(token: String) = Request.Builder()
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "JarvisAI")

    private fun get(token: String, path: String): JSONObject? = runCatching {
        val req = auth(token).url("$GH$path").build()
        GeminiChat.http.newCall(req).execute().use { r ->
            if (r.code !in 200..299) return@runCatching null
            JSONObject(r.body!!.string())
        }
    }.getOrNull()

    private fun put(token: String, path: String, body: JSONObject): Boolean = runCatching {
        val req = auth(token).url("$GH$path")
            .put(body.toString().toRequestBody("application/json".toMediaType())).build()
        GeminiChat.http.newCall(req).execute().use { it.code in 200..299 }
    }.getOrDefault(false)

    private fun post(token: String, path: String, body: JSONObject): Int = runCatching {
        val req = auth(token).url("$GH$path")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        GeminiChat.http.newCall(req).execute().use { it.code }
    }.getOrDefault(-1)

    /** Create (or update) the build workflow in the repo. */
    private fun ensureWorkflow(token: String, repo: String, branch: String): Boolean {
        val existing = get(token, "/repos/$repo/contents/$WORKFLOW_PATH?ref=$branch")
        val sha = existing?.optString("sha")
        val body = JSONObject()
            .put("message", "Add Android APK build (Jarvis)")
            .put("content", Base64.encodeToString(WORKFLOW_YML.toByteArray(), Base64.NO_WRAP))
            .put("branch", branch)
        if (sha != null) body.put("sha", sha)
        return put(token, "/repos/$repo/contents/$WORKFLOW_PATH", body)
    }

    /** v1.3.9: create (or update) the AAB bundle workflow in the repo. */
    private fun ensureBundleWorkflow(token: String, repo: String, branch: String): Boolean {
        val existing = get(token, "/repos/$repo/contents/$BUNDLE_WORKFLOW_PATH?ref=$branch")
        val sha = existing?.optString("sha")
        val body = JSONObject()
            .put("message", "Add Android App Bundle build (Jarvis)")
            .put("content", Base64.encodeToString(BUNDLE_WORKFLOW_YML.toByteArray(), Base64.NO_WRAP))
            .put("branch", branch)
        if (sha != null) body.put("sha", sha)
        return put(token, "/repos/$repo/contents/$BUNDLE_WORKFLOW_PATH", body)
    }

    /** Newest workflow_dispatch run, or null. */
    private fun latestRun(token: String, repo: String): JSONObject? {
        val runs = get(token, "/repos/$repo/actions/runs?per_page=10")?.optJSONArray("workflow_runs") ?: return null
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            if (r.optString("event") == "workflow_dispatch") return r
        }
        return runs.optJSONObject(0)
    }

    /** Download the APK artifact of a completed run into Downloads > JarvisAI. Returns the file name or null. */
    private fun downloadApk(ctx: Context, token: String, repo: String, runId: Long): String? =
        downloadArtifact(ctx, token, repo, runId, ".apk", "app-debug", "application/vnd.android.package-archive")

    /** v1.3.9: download the AAB bundle artifact of a completed run. Returns the file name or null. */
    private fun downloadBundle(ctx: Context, token: String, repo: String, runId: Long): String? =
        downloadArtifact(ctx, token, repo, runId, ".aab", "app-release", "application/octet-stream")

    private fun downloadArtifact(ctx: Context, token: String, repo: String, runId: Long, ext: String, prefix: String, mime: String): String? {
        val arts = get(token, "/repos/$repo/actions/runs/$runId/artifacts")?.optJSONArray("artifacts") ?: return null
        if (arts.length() == 0) return null
        val url = arts.getJSONObject(0).optString("archive_download_url")
        val bytes = runCatching {
            val req = auth(token).url(url).build()
            GeminiChat.http.newCall(req).execute().use { r ->
                if (r.code !in 200..299) return@runCatching null
                r.body!!.bytes()
            }
        }.getOrNull() ?: return null
        // unzip, find the file with the right extension
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(ext, ignoreCase = true)) {
                    val name = "$prefix-${System.currentTimeMillis()}$ext"
                    val data = zip.readBytes()
                    saveToDownloads(ctx, name, data, mime)
                    return name
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun saveToDownloads(ctx: Context, name: String, data: ByteArray, mime: String = "application/vnd.android.package-archive") {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/JarvisAI")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "JarvisAI").apply { mkdirs() }
            File(dir, name).writeBytes(data)
        }
    }

    private fun runStatusText(r: JSONObject): String {
        val status = r.optString("status")
        val conclusion = r.optString("conclusion")
        val url = r.optString("html_url")
        return "status: $status${if (conclusion.isNotBlank() && conclusion != "null") " ($conclusion)" else ""} — $url"
    }

    /** Trigger the build and wait up to ~4 minutes; downloads the APK when it finishes. */
    fun build(ctx: Context, cb: (String) -> Unit) {
        Thread {
            val token = Prefs.githubToken.trim()
            val repo = Prefs.githubRepo.trim()
            val branch = Prefs.githubBranch.trim().ifBlank { "main" }
            if (token.isBlank() || repo.isBlank() || !repo.contains("/")) {
                cb("APK build korte GitHub token + owner/repo lagbe — Settings > Connect-e GitHub connect koro, ar Hosting / Publish-e repo bosao.")
                return@Thread
            }
            val msg = runCatching {
                if (!ensureWorkflow(token, repo, branch)) {
                    return@runCatching "❌ GitHub-e build workflow banate parlam na — repo-ta thik ache kina dekho ($repo), ar token-er 'repo' permission ache kina."
                }
                var code = post(token, "/repos/$repo/actions/workflows/android.yml/dispatches",
                    JSONObject().put("ref", branch))
                if (code !in 200..299) {
                    Thread.sleep(5000)
                    code = post(token, "/repos/$repo/actions/workflows/android.yml/dispatches",
                        JSONObject().put("ref", branch))
                }
                if (code !in 200..299) return@runCatching "❌ Build trigger hoy ni (HTTP $code) — repo-ta Android Gradle project kina dekho."
                // poll up to ~4 minutes
                repeat(16) {
                    Thread.sleep(15_000)
                    val run = latestRun(token, repo) ?: return@runCatching "Build trigger hoyeche, kintu run khuje pelam na — check_build diye dekho."
                    if (run.optString("status") == "completed") {
                        return@runCatching if (run.optString("conclusion") == "success") {
                            val name = downloadApk(ctx, token, repo, run.optLong("id"))
                            if (name != null) "✓ APK build hoyeche! Downloads > JarvisAI > $name — tap kore install koro."
                            else "✓ Build success, kintu APK download hoy ni — run-e giye artifact download koro: ${run.optString("html_url")}"
                        } else "❌ Build failed (${run.optString("conclusion")}) — ${run.optString("html_url")} — log-e dekho ki error."
                    }
                }
                val run = latestRun(token, repo)
                "Build ekhono cholche (GitHub Actions-e Gradle build 2-5 min lage). check_build bolle status dekhbe.${if (run != null) "\nRun: ${run.optString("html_url")}" else ""}"
            }.getOrElse { "❌ APK build failed: ${it.message}" }
            cb(msg)
        }.start()
    }

    /** Check the latest build; downloads the APK if it finished successfully. */
    fun check(ctx: Context, cb: (String) -> Unit) {
        Thread {
            val token = Prefs.githubToken.trim()
            val repo = Prefs.githubRepo.trim()
            if (token.isBlank() || repo.isBlank()) {
                cb("check_build korte GitHub token + repo lagbe.")
                return@Thread
            }
            val msg = runCatching {
                val run = latestRun(token, repo) ?: return@runCatching "Kono build run pelam na — age build_apk bolo."
                if (run.optString("status") != "completed") return@runCatching "Build ekhono cholche — ${runStatusText(run)}"
                if (run.optString("conclusion") == "success") {
                    val name = downloadApk(ctx, token, repo, run.optLong("id"))
                    if (name != null) "✓ APK ready! Downloads > JarvisAI > $name — tap kore install koro."
                    else "✓ Build success, kintu APK download hoy ni — ${run.optString("html_url")}"
                } else "❌ Build ${run.optString("conclusion")} — ${run.optString("html_url")}"
            }.getOrElse { "❌ check_build failed: ${it.message}" }
            cb(msg)
        }.start()
    }

    /** v1.3.9: self-correcting deployment — diagnose the last failed build from its logs, auto-fix what it can, retry once. */
    fun selfCorrect(ctx: Context, cb: (String) -> Unit) {
        Thread {
            val token = Prefs.githubToken.trim()
            val repo = Prefs.githubRepo.trim()
            val branch = Prefs.githubBranch.trim().ifBlank { "main" }
            if (token.isBlank() || repo.isBlank()) {
                cb("fix_build korte GitHub token + repo lagbe.")
                return@Thread
            }
            val msg = runCatching {
                val run = latestRun(token, repo) ?: return@runCatching "Kono build run pelam na — age build_apk bolo."
                if (run.optString("status") != "completed") return@runCatching "Build ekhono cholche — ${runStatusText(run)}"
                if (run.optString("conclusion") == "success") return@runCatching "✅ Last build success chilo — fix korar kichu nei."
                val runId = run.optLong("id")
                val jobs = get(token, "/repos/$repo/actions/runs/$runId/jobs")?.optJSONArray("jobs")
                var failedJob: JSONObject? = null
                if (jobs != null) for (i in 0 until jobs.length()) {
                    val j = jobs.getJSONObject(i)
                    if (j.optString("conclusion") == "failure") { failedJob = j; break }
                }
                val logText = runCatching {
                    val jobId = failedJob?.optLong("id") ?: return@runCatching ""
                    val req = auth(token).url("$GH/repos/$repo/actions/jobs/$jobId/logs").build()
                    val bytes = GeminiChat.http.newCall(req).execute().use { r -> if (r.code !in 200..299) null else r.body?.bytes() } ?: return@runCatching ""
                    val sb = StringBuilder()
                    ZipInputStream(bytes.inputStream()).use { zip ->
                        var e = zip.nextEntry
                        while (e != null) {
                            sb.append(zip.readBytes().toString(Charsets.UTF_8))
                            if (sb.length > 200_000) break
                            e = zip.nextEntry
                        }
                    }
                    sb.toString()
                }.getOrDefault("")
                val diagnosis = diagnoseLog(logText)
                var fixed = ""
                if ((logText.contains("android.yml") && logText.contains("not found", true)) || logText.contains("workflow was not found", true)) {
                    if (ensureWorkflow(token, repo, branch)) {
                        post(token, "/repos/$repo/actions/workflows/android.yml/dispatches", JSONObject().put("ref", branch))
                        fixed = "\n🔧 Auto-fix: workflow file-ta refresh kore build abar trigger kore dilam."
                    }
                }
                "❌ Last build failed.\n\nDiagnosis: $diagnosis$fixed\nRun: ${run.optString("html_url")}"
            }.getOrElse { "fix_build failed: ${it.message}" }
            cb(msg)
        }.start()
    }

    private fun diagnoseLog(log: String): String {
        if (log.isBlank()) return "Log ante parlam na — GitHub-e giye run-er log dekho."
        val lines = log.lines().filter { it.contains("error", true) || it.contains("FAILED", true) || it.contains("Exception", true) }.takeLast(5)
        val hint = when {
            log.contains("Could not resolve", true) || log.contains("Could not find", true) -> "Dependency download hoy ni — internet ba Maven repo problem, ba version-ta bhul."
            log.contains("SDK location not found", true) -> "Android SDK paoa jay ni — GitHub Actions-er setup-java step check koro."
            log.contains("keystore", true) || log.contains("signing", true) -> "Signing/keystore problem — release build-e signing config lagbe."
            log.contains("minSdk", true) -> "minSdk / SDK version mismatch — app/build.gradle-e SDK version check koro."
            log.contains("Out of memory", true) || log.contains("GC overhead", true) -> "Build-er memory sesh — gradle.properties-e org.gradle.jvmargs=-Xmx2g bosao."
            else -> "Gradle task fail koreche — niche last error lines gulo dekho."
        }
        return hint + if (lines.isNotEmpty()) "\n\nLast errors:\n" + lines.joinToString("\n").take(1500) else ""
    }

    /** v1.3.9: Play Store App Bundle (.aab) build via GitHub Actions. */
    fun buildBundle(ctx: Context, cb: (String) -> Unit) {
        Thread {
            val token = Prefs.githubToken.trim()
            val repo = Prefs.githubRepo.trim()
            val branch = Prefs.githubBranch.trim().ifBlank { "main" }
            if (token.isBlank() || repo.isBlank() || !repo.contains("/")) {
                cb("Bundle build korte GitHub token + owner/repo lagbe — Settings > Connect-e GitHub connect koro.")
                return@Thread
            }
            val msg = runCatching {
                if (!ensureBundleWorkflow(token, repo, branch)) {
                    return@runCatching "❌ GitHub-e bundle workflow banate parlam na — repo-ta thik ache kina dekho ($repo)."
                }
                var code = post(token, "/repos/$repo/actions/workflows/android-bundle.yml/dispatches",
                    JSONObject().put("ref", branch))
                if (code !in 200..299) {
                    Thread.sleep(5000)
                    code = post(token, "/repos/$repo/actions/workflows/android-bundle.yml/dispatches",
                        JSONObject().put("ref", branch))
                }
                if (code !in 200..299) return@runCatching "❌ Bundle build trigger hoy ni (HTTP $code) — repo-ta Android Gradle project kina dekho."
                repeat(16) {
                    Thread.sleep(15_000)
                    val run = latestRun(token, repo) ?: return@runCatching "Bundle build trigger hoyeche, kintu run khuje pelam na — check_build diye dekho."
                    if (run.optString("status") == "completed") {
                        return@runCatching if (run.optString("conclusion") == "success") {
                            val name = downloadBundle(ctx, token, repo, run.optLong("id"))
                            if (name != null) "✓ App Bundle (.aab) build hoyeche! Downloads > JarvisAI > $name — Play Store-e upload koro (AAB install kora jay na, eta Play Store-er jonno)."
                            else "✓ Build success, kintu .aab download hoy ni — run-e giye artifact download koro: ${run.optString("html_url")}"
                        } else "❌ Bundle build failed (${run.optString("conclusion")}) — ${run.optString("html_url")}"
                    }
                }
                val run = latestRun(token, repo)
                "Bundle build ekhono cholche (Gradle build 2-5 min lage). check_build bolle status dekhbe.${if (run != null) "\nRun: ${run.optString("html_url")}" else ""}"
            }.getOrElse { "❌ Bundle build failed: ${it.message}" }
            cb(msg)
        }.start()
    }
}
