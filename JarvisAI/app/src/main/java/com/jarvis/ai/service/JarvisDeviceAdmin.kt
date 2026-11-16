package com.jarvis.ai.service

import android.app.KeyguardManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.app.Activity

/**
 * Device Admin — screen LOCK only (v1.2, additive only).
 *
 * What Android allows: Device Admin can LOCK the screen instantly
 * (lockNow) and can wake the screen. What it CANNOT do: unlock a phone
 * locked with PIN / password / fingerprint, or set / read / remove the
 * phone's own password. That is an Android security rule for every app.
 * "Admin unlock" in this app therefore means:
 *  1. Admin locks the phone, 2. Jarvis wakes the screen, 3. the owner
 *     opens the phone with the phone's own PIN / fingerprint.
 * (v1.3.6: the JARVIS app PIN / biometric lock was removed — Jarvis now
 *  opens directly, no app lock.)
 */
class JarvisDeviceAdminReceiver : DeviceAdminReceiver()

object LockHelper {
    fun component(ctx: Context) = ComponentName(ctx, JarvisDeviceAdminReceiver::class.java)

    fun isAdmin(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(DevicePolicyManager::class.java).isAdminActive(component(ctx)) }.getOrDefault(false)

    fun enableIntent(ctx: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Jarvis needs Device Admin only to lock your screen on command (\"screen lock koro\"). It cannot unlock your phone's PIN/password — only you can, with your own PIN/fingerprint.")

    fun disableIntent(ctx: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))

    /** Lock the screen now. Returns false when Device Admin is not enabled. */
    fun lockNow(ctx: Context): Boolean = runCatching {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isAdminActive(component(ctx))) return false
        dpm.lockNow(); true
    }.getOrDefault(false)

    /** Wake the screen up (does NOT bypass the lock screen). */
    fun wakeScreen(ctx: Context) {
        runCatching {
            val pm = ctx.getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "jarvis:wake").acquire(8000)
        }
    }

    /** Ask Android to dismiss the keyguard. Only works when there is no secure lock, or after the user confirms. */
    fun tryDismissKeyguard(activity: Activity): Boolean = runCatching {
        val km = activity.getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) return true
        if (km.isKeyguardSecure) return false // secure PIN/password: only the owner can unlock
        km.requestDismissKeyguard(activity, null); true
    }.getOrDefault(false)

    fun isKeyguardSecure(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(KeyguardManager::class.java).isKeyguardSecure }.getOrDefault(false)
}
