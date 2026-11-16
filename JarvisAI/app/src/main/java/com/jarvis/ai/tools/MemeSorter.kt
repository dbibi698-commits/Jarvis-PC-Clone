package com.jarvis.ai.tools

import android.content.Context
import android.os.Environment
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.4.0: meme folder auto-sort — scans a folder and sorts memes into
 * subfolders by type (images / gifs / videos) and month (2026-11), and moves
 * exact duplicates into a Duplicates folder (never deletes). Default folder
 * is workspace/memes; "downloads" / "pictures" / "pictures/memes" or any
 * absolute path also works. Moving files in shared folders needs
 * "All files access" (MANAGE_EXTERNAL_STORAGE) — otherwise the tool says so.
 */
object MemeSorter {

    private val monthFmt = SimpleDateFormat("yyyy-MM", Locale.ENGLISH)

    fun resolveFolder(ctx: Context, name: String): File {
        val ws = File(ctx.getExternalFilesDir(null), "workspace")
        return when (name.trim().lowercase()) {
            "", "memes" -> File(ws, "memes")
            "downloads" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            "pictures" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            "pictures/memes", "memes/pictures" -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Memes")
            "dcim" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            else -> {
                val f = File(name.trim())
                if (f.isAbsolute) f else File(ws, name.trim())
            }
        }
    }

    private fun typeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "webp", "bmp", "heic" -> "images"
        "gif" -> "gifs"
        "mp4", "mov", "mkv", "webm", "3gp" -> "videos"
        else -> null
    }

    private fun md5(f: File): String = runCatching {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().buffered(8192).use { ins ->
            val buf = ByteArray(8192)
            var n: Int
            while (ins.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull() ?: "size:${f.length()}:${f.name}"

    private fun uniqueTarget(dir: File, name: String): File {
        var t = File(dir, name)
        if (!t.exists()) return t
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (t.exists()) {
            t = File(dir, "$base($i).$ext")
            i++
        }
        return t
    }

    private fun moveFile(src: File, dst: File): Boolean {
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return true
        return runCatching {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }.getOrDefault(false)
    }

    fun sort(ctx: Context, folderArg: String, dryRun: Boolean, cb: (String) -> Unit) {
        Thread {
            val msg = runCatching { doSort(ctx, folderArg, dryRun) }.getOrElse { "Meme sort korte parlam na: ${it.message}" }
            cb(msg)
        }.start()
    }

    private fun doSort(ctx: Context, folderArg: String, dryRun: Boolean): String {
        val dir = resolveFolder(ctx, folderArg)
        if (!dir.exists() || !dir.isDirectory) {
            return "Folder pelam na: ${dir.absolutePath} — age oi folder-e kichu meme rakho, ba onno folder-er naam dao (memes / downloads / pictures)."
        }
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) return "Folder khali: ${dir.absolutePath}"
        val seen = mutableMapOf<String, File>()
        var moved = 0; var dupes = 0; var skipped = 0; var failed = 0
        val examples = mutableListOf<String>()
        for (f in files.sortedBy { it.name.lowercase() }) {
            val type = typeOf(f.name)
            if (type == null) { skipped++; continue }
            val key = if (f.length() < 50L * 1024 * 1024) md5(f) else "big:${f.length()}:${f.name}"
            val first = seen.putIfAbsent(key, f)
            if (first != null) {
                // exact duplicate of an earlier file
                val dst = uniqueTarget(File(dir, "Duplicates"), f.name)
                if (dryRun) { dupes++; continue }
                if (moveFile(f, dst)) { dupes++; if (examples.size < 5) examples += "🔁 duplicate → Duplicates/${dst.name}" }
                else failed++
                continue
            }
            val month = monthFmt.format(Date(f.lastModified()))
            val dst = uniqueTarget(File(File(dir, type), month), f.name)
            if (dryRun) { moved++; continue }
            if (moveFile(f, dst)) { moved++; if (examples.size < 5) examples += "• ${f.name} → $type/$month/" }
            else failed++
        }
        val sb = StringBuilder()
        sb.append(if (dryRun) "🔍 Dry run — kichu sorano hoy ni:\n" else "✅ Meme folder sort kore dilam:\n")
        sb.append("📁 ${dir.absolutePath}\n")
        sb.append("• Sorted: $moved ta (images/gifs/videos → month-wise folder)\n")
        sb.append("• Duplicates: $dupes ta → Duplicates/ folder-e\n")
        sb.append("• Skipped (meme na): $skipped ta\n")
        if (failed > 0) {
            sb.append("• Failed: $failed ta — shared folder hole Settings > Apps > Special app access > All files access-e Jarvis-ke allow koro, tarpor abar bolo.\n")
        }
        if (examples.isNotEmpty()) sb.append("\n${examples.joinToString("\n")}")
        return sb.toString()
    }
}
