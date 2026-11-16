package com.jarvis.ai.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/** Full screen mirroring via MediaProjection (used for live screen share with Gemini Live, and screenshots on Android < 11). */
object ScreenCapture {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    val active get() = projection != null

    /** The live MediaProjection, for the screen recorder (shares the same projection). */
    fun projection(): MediaProjection? = projection

    @SuppressLint("WrongConstant")
    fun start(ctx: Context, resultCode: Int, data: Intent) {
        stop()
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(resultCode, data) ?: return
        projection = p
        val wm = ctx.getSystemService(WindowManager::class.java)
        val dm = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        val scale = 720f / maxOf(dm.widthPixels, dm.heightPixels)
        val w = (dm.widthPixels * scale).toInt(); val h = (dm.heightPixels * scale).toInt()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stop() } }, Handler(Looper.getMainLooper()))
        display = p.createVirtualDisplay("jarvis-screen", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
    }

    fun stop() {
        runCatching { display?.release() }; runCatching { reader?.close() }; runCatching { projection?.stop() }
        display = null; reader = null; projection = null
    }

    fun latestJpeg(maxSide: Int = 1024): ByteArray? {
        val img = runCatching { reader?.acquireLatestImage() }.getOrNull() ?: return null
        return try {
            val plane = img.planes[0]
            val rowPad = plane.rowStride - plane.pixelStride * img.width
            val bmp = Bitmap.createBitmap(img.width + rowPad / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            toJpeg(Bitmap.createBitmap(bmp, 0, 0, img.width, img.height), maxSide)
        } finally { img.close() }
    }

    fun toJpeg(src: Bitmap, maxSide: Int): ByteArray {
        val s = maxSide.toFloat() / maxOf(src.width, src.height)
        val b = if (s < 1f) Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true) else src
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
    }
}
