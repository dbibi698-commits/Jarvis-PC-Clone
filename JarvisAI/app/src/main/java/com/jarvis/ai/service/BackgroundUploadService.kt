package com.jarvis.ai.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import com.jarvis.ai.App
import com.jarvis.ai.R
import com.jarvis.ai.core.Memory
import com.jarvis.ai.core.Prefs
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Background video upload (v1.2, additive only).
 * Pick a video (Settings > Background Video Upload, or say
 * "video upload koro"), Jarvis uploads it in the background even if you
 * leave the app — a foreground service with a progress notification.
 * Destination: the upload URL saved in Settings (POST multipart, field
 * "file"). If no URL is set, Jarvis tells you to set one instead of
 * silently failing. YouTube/Facebook/Instagram uploads still go through
 * their own share screen (see ToolExecutor.share_media_to_app), because
 * those apps do not allow silent background posting.
 */
class BackgroundUploadService : Service() {
    companion object {
        const val ACTION_UPLOAD = "jarvis.UPLOAD_VIDEO"
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"

        fun startUpload(ctx: Context, uri: Uri, name: String) {
            val i = Intent(ctx, BackgroundUploadService::class.java)
                .setAction(ACTION_UPLOAD)
                .putExtra(EXTRA_URI, uri.toString()).putExtra(EXTRA_NAME, name)
            runCatching { ctx.startForegroundService(i) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_UPLOAD) return START_NOT_STICKY
        val uri = Uri.parse(intent.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "video.mp4"
        startForeground(21, note("Uploading $name… 0%"))
        Thread { upload(uri, name); stopSelf() }.start()
        return START_NOT_STICKY
    }

    private fun note(text: String): Notification =
        Notification.Builder(this, App.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_fg).setContentTitle("Jarvis — background upload")
            .setContentText(text).setOngoing(true).build()

    private fun upload(uri: Uri, name: String) {
        val url = Prefs.uploadUrl
        if (url.isBlank()) {
            Memory.add("assistant", "Background upload stopped: no upload URL set. Set it in Settings > Background Video Upload.", "upload")
            return
        }
        runCatching {
            val boundary = "----jarvis${System.currentTimeMillis()}"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connectTimeout = 30000; readTimeout = 120000
            }
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: video/mp4\r\n\r\n")
                contentResolver.openInputStream(uri)?.use { inp ->
                    val buf = ByteArray(64 * 1024); var n: Int
                    var sent = 0L
                    while (inp.read(buf).also { n = it } != -1) { out.write(buf, 0, n); sent += n }
                }
                out.writeBytes("\r\n--$boundary--\r\n"); out.flush()
            }
            val code = conn.responseCode
            val msg = "Background upload of $name finished: HTTP $code"
            Memory.add("assistant", msg, "upload")
        }.onFailure {
            Memory.add("assistant", "Background upload of $name failed: ${it.message}", "upload")
        }
    }
}
