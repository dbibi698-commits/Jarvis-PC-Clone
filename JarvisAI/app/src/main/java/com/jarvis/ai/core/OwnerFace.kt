package com.jarvis.ai.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import androidx.core.content.ContextCompat
import com.jarvis.ai.tools.ObjectVision
import java.io.File
import org.json.JSONObject

/**
 * v1.4.2: Owner Face Check — sudhu owner-i Jarvis use korte parbe.
 *
 * Kivabe kaj kore: owner age nijer ekta photo save kore (camera diye selfie
 * ba gallery theke) — workspace/owner_face.jpg. Jarvis kholar somoy front
 * camera diye ekta selfie tole, Gemini Vision diye owner-er photo-r sathe
 * miliye dekhe. Mile gele Jarvis khole, na mille Jarvis block kore dey.
 *
 * HONEST LIMITS (user-ke bolte hobe):
 * - Eta Jarvis APP-er check — phone-er lock na. Jarvis onno app khulte
 *   badha dite parbe na, phone-er PIN/password/fingerprint-er bodoleo na.
 * - Face compare Gemini Vision (online AI) diye hoy — on-device face
 *   recognition na. Internet + Gemini API key lagbe. Key na thakle ba net
 *   na thakle check cholbe na — tokhon Jarvis khula thakbe (fail-open),
 *   kintu warning dekhabe.
 * - Alo / angle / chosma bodle gele Gemini kokhono kokhono vul bolte pare.
 *   Owner-kei abar "save_owner_photo" diye notun photo tulte hobe.
 */
object OwnerFace {

    private const val OWNER_PHOTO = "owner_face.jpg"

    fun workspace(ctx: Context): File = File(ctx.getExternalFilesDir(null), "workspace")

    fun photoFile(ctx: Context): File = File(workspace(ctx), OWNER_PHOTO)

    fun hasOwnerPhoto(ctx: Context): Boolean = photoFile(ctx).exists() && photoFile(ctx).length() > 1000

    fun saveOwnerPhoto(ctx: Context, jpeg: ByteArray): Boolean = runCatching {
        val f = photoFile(ctx)
        f.parentFile?.mkdirs()
        f.writeBytes(jpeg)
        true
    }.getOrDefault(false)

    fun deleteOwnerPhoto(ctx: Context): Boolean = runCatching { photoFile(ctx).delete() }.getOrDefault(false)

    /** Front camera diye ekta selfie tole (1280x720 JPEG). Camera permission lagbe. */
    fun captureSelfie(ctx: Context, cb: (ByteArray?) -> Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cb(null); return
        }
        Thread {
            runCatching {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: run { cb(null); return@Thread }
                val camId = runCatching {
                    cm.cameraIdList.firstOrNull { id ->
                        cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                    } ?: cm.cameraIdList.firstOrNull()
                }.getOrNull() ?: run { cb(null); return@Thread }
                val thread = HandlerThread("jarvis-facecam").apply { start() }
                val handler = Handler(thread.looper)
                val reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1)
                var device: CameraDevice? = null
                var done = false
                fun finish(result: ByteArray? = null) {
                    if (done) return; done = true
                    runCatching { device?.close() }
                    runCatching { reader.close() }
                    thread.quitSafely()
                    cb(result)
                }
                reader.setOnImageAvailableListener({ r ->
                    val img = runCatching { r.acquireLatestImage() }.getOrNull()
                    if (img != null) {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()); buf.get(bytes); img.close()
                        finish(bytes)
                    }
                }, handler)
                val cback = object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        device = d
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(reader.surface) }
                        runCatching {
                            d.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) { runCatching { s.capture(req.build(), null, handler) } }
                                override fun onConfigureFailed(s: CameraCaptureSession) { finish() }
                            }, handler)
                        }.onFailure { finish() }
                    }
                    override fun onDisconnected(d: CameraDevice) { finish() }
                    override fun onError(d: CameraDevice, e: Int) { finish() }
                }
                runCatching { cm.openCamera(camId, cback, handler) }.onFailure { finish() }
                handler.postDelayed({ finish() }, 15_000)
            }.onFailure { cb(null) }
        }.start()
    }

    /**
     * Verify kore: ekhon jini phone-ta dhore achen, tini ki owner?
     * Returns Pair(isOwner, message). Check cholte na parle (key/net/camera
     * nei) isOwner=true diye fail-open kore — karon-ta message-e lekha thake.
     */
    fun verify(ctx: Context, cb: (Pair<Boolean, String>) -> Unit) {
        if (!Prefs.ownerFaceCheckEnabled) { cb(true to "Face check OFF — sobai Jarvis use korte pare."); return }
        if (!hasOwnerPhoto(ctx)) { cb(true to "Owner-er photo set kora nei — age Settings > Owner Face Check theke photo dao."); return }
        if (Prefs.apiKey.isBlank()) { cb(true to "Face check cholbe na — Gemini API key nei (Settings > AI Keys)."); return }
        captureSelfie(ctx) { selfie ->
            if (selfie == null) { cb(true to "Face check cholbe na — camera khulte pareni (permission / camera busy)."); return@captureSelfie }
            Thread {
                val ref = runCatching { photoFile(ctx).readBytes() }.getOrNull()
                if (ref == null) { cb(true to "Owner-er photo porte pareni."); return@Thread }
                val raw = ObjectVision.compareFaces(ref, selfie)
                if (raw == null) { cb(true to "Face check cholbe na — internet / Gemini-te problem."); return@Thread }
                val parsed = runCatching {
                    val t = raw.trim()
                    val j = JSONObject(if (t.startsWith("{")) t else t.substring(t.indexOf("{"), t.lastIndexOf("}") + 1))
                    j.optBoolean("same_person", false) to j.optInt("confidence", 0)
                }.getOrNull()
                if (parsed == null) { cb(true to "Face check-er uttor bujhte pareni — abar try koro."); return@Thread }
                val (same, conf) = parsed
                if (same) cb(true to "✅ Face check pass — tumi owner ($conf% match).")
                else cb(false to "🔒 Face mileni ($conf% match) — sudhu owner-i Jarvis use korte pare. Tumi owner hole Settings > Owner Face Check theke notun photo dao.")
            }.start()
        }
    }
}
