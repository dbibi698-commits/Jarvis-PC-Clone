package com.jarvis.ai.tools

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.jarvis.ai.core.Memory
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.3.9 — Direct memory export and backup: the user's whole Jarvis memory
 * (long-term facts + full chat history + non-secret settings) exported as ONE
 * JSON file into Downloads > JarvisAI, so they can keep it, share it, or
 * restore it on another phone. No cloud needed. API keys / tokens are never
 * included in the export.
 */
object MemoryExport {
    fun export(ctx: Context): String {
        return runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ENGLISH).format(Date())
            val out = JSONObject()
                .put("exported_at", stamp)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("app", "Jarvis AI")
                .put("memory", JSONObject(Memory.exportMemoryJson()))
                .put("history", JSONObject(Memory.exportHistoryJson()))
                .put("settings", JSONObject(SyncManager.settingsJson()))
            val name = "jarvis-memory-export-$stamp.json"
            val data = out.toString(2).toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/JarvisAI")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
            } else {
                val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "JarvisAI").apply { mkdirs() }
                java.io.File(dir, name).writeBytes(data)
            }
            "💾 Memory backup export hoyeche: Downloads > JarvisAI > $name\n(${Memory.facts().size} ta memory fact + ${Memory.history().size} ta chat message + settings). API key/token kokhono export hoy na."
        }.getOrElse { "Export holo na: ${it.message}" }
    }
}
