package com.jarvis.ai.tools

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.jarvis.ai.service.JarvisAccessibilityService

/**
 * v1.4.0: Control Panel access — Jarvis controls the phone's Quick Settings
 * (the Control Center in the screenshot) the way a human does.
 *
 * How each toggle works (Android is strict here — no app can silently flip
 * system switches): Wi-Fi / Bluetooth / Airplane / Location / Mobile data /
 * Hotspot / Dark mode / Eye Protection / Battery Saver are tapped ON THE
 * PANEL ITSELF (open panel -> tap the tile -> done, using precise clicks).
 * Torch is direct (CameraManager). Do Not Disturb is direct once Notification
 * Policy Access is granted. Brightness / auto-rotate need "Modify system
 * settings" granted once. Volume is direct (AudioManager).
 */
object ControlPanel {

    @Volatile private var torchOn = false

    fun openPanel(acc: JarvisAccessibilityService?): String {
        if (acc == null) return needAcc()
        val ok = acc.global("quick_settings")
        return if (ok) "🎛️ Control panel khule dilam — Wi-Fi, Bluetooth, torch, DND, brightness, volume — ja bolo, ami toggle kore debo."
        else "Control panel khulte parlam na — Accessibility ON ache kina dekho."
    }

    /**
     * v1.4.0: wake_screen — screen-ta jaliye dey (lock screen dekhabe).
     * Sotyi kotha: kono app PIN/password diye lock screen khulte pare na —
     * eta Android-er security. Screen on korle Extend Unlock (Smart Lock)
     * on thakle phone age thekei unlocked thakbe (basha / trusted device).
     */
    fun wakeScreen(ctx: Context): String {
        return runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "jarvis:wake"
            )
            wl.acquire(10_000)
            "🔆 Screen on kore dilam — lock screen dekhte parbe. (PIN/password shudhu tumi dite parbe; Extend Unlock on thakle phone age thekei unlocked thakbe.)"
        }.getOrElse { "Screen on korte parlam na: ${it.message}" }
    }

    fun toggle(ctx: Context, acc: JarvisAccessibilityService?, name: String, level: Int, cb: (String) -> Unit) {
        Thread {
            val msg = runCatching { doToggle(ctx, acc, name, level) }.getOrElse { "Control panel-e '$name' korte parlam na: ${it.message}" }
            cb(msg)
        }.start()
    }

    private fun needAcc() = "Accessibility service OFF ache — Control panel control korte Settings > Accessibility-e 'Jarvis AI' ON koro."

    /** Opens the Quick Settings panel, waits for it, taps the first matching tile label. */
    private fun tapTile(acc: JarvisAccessibilityService?, vararg labels: String): Boolean {
        if (acc == null) return false
        acc.global("quick_settings")
        Thread.sleep(800)
        for (label in labels) {
            if (acc.clickText(label)) return true
        }
        return false
    }

    private fun openWriteSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun doToggle(ctx: Context, acc: JarvisAccessibilityService?, name: String, level: Int): String {
        val n = name.trim().lowercase()
        return when {
            n.contains("wifi") || n.contains("wi-fi") -> {
                if (acc == null) return needAcc()
                if (tapTile(acc, "Wi-Fi")) "✅ Wi-Fi toggle kore dilam (control panel-er tile-e tap kore). Panel-ta khola ache — dekhte paro."
                else "Wi-Fi tile-ta pelam na — control panel-e Wi-Fi tile-ta ache kina dekho, ba panel-ta manually khule abar bolo."
            }
            n.contains("bluetooth") -> {
                if (acc == null) return needAcc()
                if (tapTile(acc, "Bluetooth")) "✅ Bluetooth toggle kore dilam."
                else "Bluetooth tile-ta pelam na."
            }
            n.contains("airplane") || n.contains("aeroplane") || n.contains("flight") -> {
                if (acc == null) return needAcc()
                if (tapTile(acc, "Aeroplane mode", "Airplane mode", "Flight mode")) "✅ Aeroplane mode toggle kore dilam."
                else "Aeroplane tile-ta pelam na."
            }
            n.contains("location") || n.contains("gps") -> {
                if (acc == null) return needAcc()
                if (tapTile(acc, "Location")) "✅ Location toggle kore dilam."
                else "Location tile-ta pelam na."
            }
            n.contains("data") || n.contains("mobile") -> {
                if (acc == null) return needAcc()
                if (tapTile(acc, "Mobile data", "Data")) "✅ Mobile data toggle kore dilam."
                else "Mobile data tile-ta pelam na."
            }
            n.contains("hotspot") -> {
                if (acc == null) return needAcc()
                if (tapTile(acc, "Personal hotspot", "Hotspot")) "✅ Hotspot toggle kore dilam."
                else "Hotspot tile-ta pelam na."
            }
            n.contains("dark") -> {
                if (acc == null) return needAcc()
                if (tapTile(acc, "Dark mode")) "✅ Dark mode toggle kore dilam."
                else "Dark mode tile-ta pelam na."
            }
            n.contains("eye") -> {
                if (acc == null) return needAcc()
                if (tapTile(acc, "Eye Protection")) "✅ Eye Protection toggle kore dilam."
                else "Eye Protection tile-ta pelam na."
            }
            n.contains("battery") && n.contains("saver") -> {
                if (acc == null) return needAcc()
                if (tapTile(acc, "Battery Saver")) "✅ Battery Saver toggle kore dilam."
                else "Battery Saver tile-ta pelam na."
            }
            n.contains("flash") || n.contains("torch") || n.contains("টর্চ") -> {
                val wantOn = when {
                    n.contains("on") || n.contains("জ্বালাও") || n.contains("jalao") -> true
                    n.contains("off") || n.contains("নিভাও") || n.contains("nivao") -> false
                    else -> !torchOn
                }
                val cm = ctx.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull() ?: return "Torch pelam na — ei phone-e flashlight nei ba camera permission lagbe."
                runCatching { cm.setTorchMode(id, wantOn) }.getOrElse { return "Torch ${if (wantOn) "on" else "off"} korte parlam na: ${it.message}" }
                torchOn = wantOn
                if (wantOn) "🔦 Torch on kore dilam." else "🔦 Torch off kore dilam."
            }
            n.contains("disturb") || n.contains("dnd") || n.contains("silent") -> {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    return "Do Not Disturb bodlate Notification access lagbe — settings khule dilam, Jarvis-ke allow koro, tarpor abar bolo."
                }
                val toNone = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE
                nm.setInterruptionFilter(
                    if (toNone) NotificationManager.INTERRUPTION_FILTER_NONE
                    else NotificationManager.INTERRUPTION_FILTER_ALL
                )
                if (toNone) "🔕 Do Not Disturb ON kore dilam — ar kono notification asbe na."
                else "🔔 Do Not Disturb OFF kore dilam — notification abar asbe."
            }
            n.contains("bright") -> {
                if (!Settings.System.canWrite(ctx)) {
                    openWriteSettings(ctx)
                    return "Brightness bodlate 'Modify system settings' permission lagbe — settings khule dilam, Jarvis-ke allow koro, tarpor abar bolo (ekbari lagbe)."
                }
                val pct = if (level in 1..100) level else 70
                val cr = ctx.contentResolver
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, pct * 255 / 100)
                "🔆 Brightness $pct% kore dilam."
            }
            n.contains("rotat") -> {
                if (!Settings.System.canWrite(ctx)) {
                    openWriteSettings(ctx)
                    return "Auto-rotate bodlate 'Modify system settings' permission lagbe — settings khule dilam, Jarvis-ke allow koro, tarpor abar bolo."
                }
                val cr = ctx.contentResolver
                val cur = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)
                val next = if (cur == 1) 0 else 1
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, next)
                if (next == 1) "🔄 Auto-rotate ON kore dilam." else "🔄 Auto-rotate OFF kore dilam."
            }
            n.contains("volume") || n.contains("sound") || n.contains("ভলিউম") -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                if (level in 0..100) {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, level * max / 100, 0)
                    "🔊 Volume $level% kore dilam."
                } else if (n.contains("up") || n.contains("barao") || n.contains("বাড়াও")) {
                    am.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "🔊 Volume baralam."
                } else {
                    am.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "🔉 Volume komalam."
                }
            }
            else -> {
                if (acc == null) return needAcc()
                if (tapTile(acc, name.trim())) "✅ '$name' toggle kore dilam (control panel-e tap kore)."
                else "Control panel-e '$name' bole kono tile pelam na. Ja ache: Wi-Fi, Bluetooth, Aeroplane mode, Location, Mobile data, Hotspot, Dark mode, Eye Protection, Battery Saver, Torch, Do Not Disturb, Brightness, Auto-rotate, Volume."
            }
        }
    }
}
