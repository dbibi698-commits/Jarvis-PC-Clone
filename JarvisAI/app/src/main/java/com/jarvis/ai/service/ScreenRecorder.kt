package com.jarvis.ai.service

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen video recording (v1.3.1).
 * Records the phone screen as an MP4 video into Gallery > Movies > JarvisAI
 * using the same MediaProjection Jarvis already uses for screenshots.
 * Mic audio is included when the microphone permission is already granted.
 * First time, Android shows its own "Start recording?" dialog — the user must
 * tap "Start now" once (no app can skip that). After that Jarvis records alone.
 */
object ScreenRecorder {
    var recording: Boolean = false
        private set
    /** Human-readable location of the last (or current) recording, e.g. "Movies/JarvisAI/screen-record-....mp4". */
    var lastVideoPath: String? = null
        private set

    private var recorder: MediaRecorder? = null
    private var recDisplay: VirtualDisplay? = null
    private var videoUri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pendingStop: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Start recording. Returns a short status message.
     * Returns "NEED_PERMISSION" when there is no MediaProjection yet — the
     * caller must ask Android for screen-capture permission first.
     */
    @Suppress("DEPRECATION")
    fun start(ctx: Context, seconds: Int = 0): String {
        if (recording) return "Already recording the screen."
        val projection = ScreenCapture.projection() ?: return "NEED_PERMISSION"
        val wm = ctx.getSystemService(WindowManager::class.java)
        val dm = DisplayMetrics(); wm.defaultDisplay.getRealMetrics(dm)
        // Cap the long side at 1080p to keep the file small.
        val scale = minOf(1f, 1080f / maxOf(dm.widthPixels, dm.heightPixels))
        val w = (dm.widthPixels * scale).toInt(); val h = (dm.heightPixels * scale).toInt()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "screen-record-$stamp.mp4"
        return try {
            val app = ctx.applicationContext
            val uri: Uri?
            val file: File?
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/JarvisAI")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                uri = app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return "Could not create the video file in Gallery."
                pfd = app.contentResolver.openFileDescriptor(uri, "w")
                    ?: return "Could not open the video file."
                videoUri = uri; file = null
                lastVideoPath = "${Environment.DIRECTORY_MOVIES}/JarvisAI/$name"
            } else {
                val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "JarvisAI").apply { mkdirs() }
                file = File(dir, name)
                uri = null; videoUri = null
                lastVideoPath = file.absolutePath
            }
            // v1.3.2: if the mic is busy (Jarvis is talking on a live voice call),
            // MediaRecorder.prepare() fails — retry video-only instead of giving up.
            val hasMic = ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            var mr: MediaRecorder? = null
            var audioUsed = false
            var lastErr: Exception? = null
            for (withAudio in listOf(hasMic, false).distinct()) {
                try {
                    mr = makeRecorder(app, w, h, pfd, file, withAudio)
                    audioUsed = withAudio
                    break
                } catch (e: Exception) {
                    lastErr = e
                    runCatching { mr?.release() }; mr = null
                }
            }
            val recorder2 = mr ?: run { cleanup(); return "Could not start recording: ${lastErr?.message}" }
            val surface = recorder2.surface
            recDisplay = projection.createVirtualDisplay("jarvis-record", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stop(app) }
            }, handler)
            recorder = recorder2 // assign before start() so cleanup() releases it if start fails
            recorder2.start()
            recording = true
            if (seconds > 0) {
                val r = Runnable { stop(app) }
                pendingStop = r
                handler.postDelayed(r, seconds * 1000L)
            }
            "Recording started" + if (seconds > 0) " for $seconds seconds" else " (say \"stop recording\" to stop)" +
                if (!audioUsed) " — video only (mic was busy while talking)" else "" 
        } catch (e: Exception) {
            cleanup()
            "Could not start recording: ${e.message}"
        }
    }

    /** Build + prepare a MediaRecorder (not started yet). Throws when the mic/video is busy. */
    private fun makeRecorder(app: Context, w: Int, h: Int, pfd: ParcelFileDescriptor?, file: File?, withAudio: Boolean): MediaRecorder {
        val mr = MediaRecorder()
        if (withAudio) {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44100)
            mr.setAudioEncodingBitRate(128000)
        }
        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mr.setVideoSize(w, h)
        mr.setVideoFrameRate(30)
        mr.setVideoEncodingBitRate(6_000_000)
        if (pfd != null) mr.setOutputFile(pfd.fileDescriptor) else mr.setOutputFile(file!!.absolutePath)
        mr.prepare()
        return mr
    }

    /** Stop recording and finalise the video. Returns the saved location, or null. */
    fun stop(ctx: Context): String? {
        if (!recording) return lastVideoPath
        pendingStop?.let { handler.removeCallbacks(it) }; pendingStop = null
        val app = ctx.applicationContext
        runCatching { recorder?.stop() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        runCatching { recDisplay?.release() }
        recorder = null; recDisplay = null; recording = false
        runCatching { pfd?.close() }; pfd = null
        if (Build.VERSION.SDK_INT >= 29 && videoUri != null) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            runCatching { app.contentResolver.update(videoUri!!, values, null, null) }
            videoUri = null
        }
        return lastVideoPath
    }

    private fun cleanup() {
        runCatching { recorder?.release() }; recorder = null
        runCatching { recDisplay?.release() }; recDisplay = null
        runCatching { pfd?.close() }; pfd = null
        recording = false
    }
}
