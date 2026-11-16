package com.jarvis.ai.tools

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Make a video by yourself (v1.3.6) — slideshow MP4 from photos.
 * 720x1280 portrait, 30fps, H.264. Each photo shows for a few seconds with
 * an optional text overlay. No network, no API key — encoded on the phone
 * with MediaCodec + MediaMuxer. Saved to Gallery > Movies > JarvisAI.
 */
object VideoMaker {
    private const val W = 720
    private const val H = 1280
    private const val FPS = 30
    private const val BITRATE = 4_000_000

    fun make(ctx: Context, imageUris: List<Uri>, secondsPerImage: Int, overlayText: String, cb: (String) -> Unit) {
        Thread {
            val msg = runCatching {
                if (imageUris.isEmpty()) return@runCatching "Video banate photo lagbe — workspace-e photo dao ba gallery theke bolo."
                val tmp = File(ctx.cacheDir, "jarvis-video-${System.currentTimeMillis()}.mp4")
                encode(ctx, imageUris, secondsPerImage.coerceIn(1, 10), overlayText, tmp)
                val name = "JarvisVideo-${System.currentTimeMillis()}.mp4"
                saveToGallery(ctx, tmp, name)
                tmp.delete()
                "✓ Video banano hoyeche! Gallery > Movies > JarvisAI > $name (${imageUris.size} ta photo, ${secondsPerImage}s kore)"
            }.getOrElse { "❌ Video banano jayni: ${it.message}" }
            cb(msg)
        }.start()
    }

    private fun encode(ctx: Context, uris: List<Uri>, seconds: Int, text: String, out: File) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        codec.start()
        var track = -1
        var frameIndex = 0L
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat); muxer.start()
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && track >= 0 && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> if (end) return
                }
                if (!end && idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
            }
        }
        for (uri in uris) {
            val bmp = loadFitted(ctx, uri, text) ?: continue
            val nv12 = bitmapToNv12(bmp)
            bmp.recycle()
            val frames = seconds * FPS
            for (f in 0 until frames) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx < 0) continue
                val inBuf = codec.getInputBuffer(inIdx) ?: continue
                inBuf.clear()
                inBuf.put(nv12)
                codec.queueInputBuffer(inIdx, 0, nv12.size, frameIndex * 1_000_000L / FPS, 0)
                frameIndex++
                drain(false)
            }
        }
        val inIdx = codec.dequeueInputBuffer(10_000)
        if (inIdx >= 0) codec.queueInputBuffer(inIdx, 0, 0, frameIndex * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        runCatching { muxer.stop() }
        runCatching { muxer.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    /** Load the photo, center-crop to 720x1280, draw the overlay text at the bottom. */
    private fun loadFitted(ctx: Context, uri: Uri, text: String): Bitmap? {
        val src = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        val scale = maxOf(W.toFloat() / src.width, H.toFloat() / src.height)
        val scaled = Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        val x = (scaled.width - W) / 2
        val y = (scaled.height - H) / 2
        val out = Bitmap.createBitmap(scaled, x, y, W, H).copy(Bitmap.Config.ARGB_8888, true)
        if (scaled != src) scaled.recycle()
        if (src != out) src.recycle()
        if (text.isNotBlank()) {
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 54f; textAlign = Paint.Align.CENTER
                setShadowLayer(8f, 0f, 2f, Color.BLACK)
            }
            val lines = text.split("\n").take(3)
            val bgPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
            val blockH = lines.size * 72 + 24
            canvas.drawRect(0f, (H - blockH - 100).toFloat(), W.toFloat(), (H - 60).toFloat(), bgPaint)
            lines.forEachIndexed { i, line ->
                canvas.drawText(line.take(30), W / 2f, (H - blockH - 100 + 60 + i * 72).toFloat(), paint)
            }
        }
        return out
    }

    /** ARGB bitmap -> NV12 (YUV420 semi-planar) bytes. */
    private fun bitmapToNv12(bmp: Bitmap): ByteArray {
        val pixels = IntArray(W * H)
        bmp.getPixels(pixels, 0, W, 0, 0, W, H)
        val out = ByteArray(W * H * 3 / 2)
        var i = 0
        for (y in 0 until H) {
            for (x in 0 until W) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                out[i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
                i++
            }
        }
        var uv = W * H
        for (y in 0 until H step 2) {
            for (x in 0 until W step 2) {
                val p = pixels[y * W + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                out[uv++] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                out[uv++] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    private fun saveToGallery(ctx: Context, file: File, name: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/JarvisAI")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
            ctx.contentResolver.openOutputStream(uri)?.use { os -> file.inputStream().copyTo(os) }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "JarvisAI").apply { mkdirs() }
            file.copyTo(File(dir, name), overwrite = true)
        }
    }
}
