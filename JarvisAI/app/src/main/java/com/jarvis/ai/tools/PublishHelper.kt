package com.jarvis.ai.tools

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.jarvis.ai.core.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.Base64
import org.json.JSONObject

/**
 * Hosting / Publish via command (v1.2, additive only).
 *
 * Two honest ways, both from a Jarvis command or Settings:
 *  1. "publish <file>" — uploads a workspace file to your GitHub repo
 *     (Settings > GitHub token + owner/repo) and gives you the GitHub
 *     Pages link. Needs a GitHub token + Pages enabled on the repo.
 *  2. "host website" — starts a tiny web server ON THIS PHONE serving
 *     your workspace files on your Wi-Fi (http://<phone-ip>:8080).
 *     Anyone on the same Wi-Fi can open it. Stop it from Settings.
 */
object PublishHelper {
    fun publishFile(ctx: Context, relPath: String, content: String, cb: (String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val msg = publishFileSync(relPath, content)
            main.post { cb(msg) }
        }.start()
    }

    /** Synchronous single-file push (blocking — call on a background thread). Returns the result message. Used by RepoManager for multi-file pushes. */
    fun publishFileSync(relPath: String, content: String): String {
        val token = Prefs.githubToken
        val repo = Prefs.githubRepo // "owner/repo"
        if (token.isBlank() || !repo.contains("/")) {
            return "Publish korte GitHub token ar repo lagbe. Settings > Hosting / Publish-e token ar owner/repo (jemon: rahim/my-site) bosao, tarpor abar bolo \"publish $relPath\"."
        }
        return runCatching {
            val (owner, name) = repo.trim().split("/", limit = 2)
            val api = "https://api.github.com/repos/$owner/$name/contents/$relPath"
            // existing file? need its sha to update
            var sha: String? = null
            runCatching {
                val g = (URL("$api?ref=${Prefs.githubBranch}").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (g.responseCode == 200) sha = JSONObject(g.inputStream.readBytes().toString(Charsets.UTF_8)).optString("sha").takeIf { it.isNotBlank() }
            }
            val body = JSONObject().put("message", "Publish via Jarvis: $relPath")
                .put("content", Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
                .put("branch", Prefs.githubBranch)
            if (sha != null) body.put("sha", sha)
            val put = (URL(api).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"; doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json")
            }
            put.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = put.responseCode
            if (code in 200..201) "Published! GitHub Pages link (Pages on thakle): https://$owner.github.io/$name/$relPath"
            else "Publish failed: HTTP $code — ${put.errorStream?.readBytes()?.toString(Charsets.UTF_8)?.take(300)}"
        }.getOrElse { "Publish failed: ${it.message}" }
    }
}

/** Tiny on-phone web server for "host website" — serves the Jarvis workspace on Wi-Fi. */
object HostServer {
    @Volatile private var server: ServerSocket? = null
    @Volatile var running: Boolean = false; private set
    @Volatile var url: String = ""; private set

    fun start(ctx: Context, workspace: File): String {
        if (running) return url
        val port = Prefs.hostingPort
        Thread {
            runCatching {
                val s = ServerSocket(port); server = s; running = true
                val ip = runCatching {
                    @Suppress("DEPRECATION")
                    val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
                    val a = wm.connectionInfo.ipAddress
                    "${a and 0xff}.${a shr 8 and 0xff}.${a shr 16 and 0xff}.${a shr 24 and 0xff}"
                }.getOrDefault("localhost")
                url = "http://$ip:$port/"
                while (running) {
                    val sock = runCatching { s.accept() }.getOrNull() ?: break
                    Thread {
                        runCatching {
                            val req = sock.getInputStream().bufferedReader().readLine() ?: ""
                            val path = req.split(" ").getOrNull(1)?.trimStart('/')?.takeIf { it.isNotBlank() } ?: "index.html"
                            val f = File(workspace, path).canonicalFile
                            val out = sock.getOutputStream()
                            if (!f.canonicalPath.startsWith(workspace.canonicalPath) || !f.isFile) {
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\n\r\nNot found".toByteArray())
                            } else {
                                val bytes = f.readBytes()
                                val type = when (f.extension.lowercase()) { "html" -> "text/html"; "css" -> "text/css"; "js" -> "text/javascript"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> "application/octet-stream" }
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                                out.write(bytes)
                            }
                            out.flush(); sock.close()
                        }
                    }.start()
                }
            }
        }.start()
        Thread.sleep(400)
        return url.ifBlank { "http://localhost:$port/ (Wi-Fi IP pawa jayni — same Wi-Fi-e phone-er IP diye kholo)" }
    }

    fun stop() { running = false; runCatching { server?.close() }; server = null; url = "" }
}
