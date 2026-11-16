package com.jarvis.ai.tools

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v1.4.0 — Wallpaper changer.
 * Jarvis background-e, kono UI na khule, phone-er wallpaper bodle dite pare:
 * home screen, lock screen, ba duita-i. SET_WALLPAPER normal permission
 * (install-ei granted thake), tai kono alada permission lagbe na.
 * Source: "random" (internet theke sundor photo), kono image URL, ba
 * workspace-er file (workspace/<name> ba workspace/wallpapers/<name>).
 */
object WallpaperChanger {

    fun setWallpaper(ctx: Context, source: String, screen: String, cb: (String) -> Unit) {
        Thread {
            val msg = runCatching { doSet(ctx, source, screen) }
                .getOrElse { "Wallpaper bodlate parlam na: ${it.message}" }
            cb(msg)
        }.start()
    }

    private fun doSet(ctx: Context, source: String, screen: String): String {
        val s = source.trim()
        val bmp = when {
            s.isBlank() || s.equals("random", true) ->
                downloadBitmap("https://picsum.photos/1080/1920")
                    ?: return "Random wallpaper download holo na — internet ache kina dekho."
            s.startsWith("http://") || s.startsWith("https://") ->
                downloadBitmap(s) ?: return "Oi link theke chobi download holo na."
            else ->
                loadLocalBitmap(ctx, s) ?: return "Chobi-ta pelam na — file-ta workspace-e rakho (workspace/wallpapers/ folder-eo cholbe), ba ekta image link dao."
        }
        val wm = WallpaperManager.getInstance(ctx)
        val flags = when (screen.trim().lowercase()) {
            "lock", "lockscreen", "lock screen" -> WallpaperManager.FLAG_LOCK
            "home", "homescreen", "home screen" -> WallpaperManager.FLAG_SYSTEM
            else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        runCatching { wm.setBitmap(bmp, null, true, flags) }
            .onFailure { runCatching { wm.setBitmap(bmp) }.getOrElse { throw it } }
        val where = when (flags) {
            WallpaperManager.FLAG_LOCK -> "lock screen"
            WallpaperManager.FLAG_SYSTEM -> "home screen"
            else -> "home + lock screen duita-i"
        }
        return "🖼️ Wallpaper bodle dilam — $where."
    }

    private fun loadLocalBitmap(ctx: Context, name: String): Bitmap? {
        val candidates = listOf(
            File(name),
            File(ctx.getExternalFilesDir(null), "workspace/$name"),
            File(ctx.getExternalFilesDir(null), "workspace/wallpapers/$name")
        )
        val f = candidates.firstOrNull { it.exists() && it.isFile } ?: return null
        return decodeCapped(f.absolutePath)
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "JarvisAI/1.4.0")
        }
        return try {
            conn.inputStream.use { input ->
                val bytes = input.readBytes()
                decodeCapped(bytes)
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun decodeCapped(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        val sample = sampleFor(bounds.outWidth, bounds.outHeight)
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun decodeCapped(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val sample = sampleFor(bounds.outWidth, bounds.outHeight)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** 1920px-er beshi hole downscale — boro chobi-te OOM hobe na. */
    private fun sampleFor(w: Int, h: Int): Int {
        var s = 1
        var mw = maxOf(w, h)
        while (mw > 1920) { s *= 2; mw /= 2 }
        return s
    }
}
