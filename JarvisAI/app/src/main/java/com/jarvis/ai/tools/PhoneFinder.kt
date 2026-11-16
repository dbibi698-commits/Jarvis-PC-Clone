package com.jarvis.ai.tools

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * v1.4.0: smart phone locator — "Hey Jarvis, phone kothay?" bolle Jarvis
 * jore alarm bajabe + vibrate korbe + torch jalabe/nivabe, jate phone-ta
 * khuje paoa jay. Alarm stream silent mode-eo baje (beshirbhag phone-e).
 * "stop" bolle ba stop_ringing tool-e thambe.
 */
object PhoneFinder {

    @Volatile private var ringing = false
    private var player: MediaPlayer? = null

    fun ring(ctx: Context, seconds: Int, cb: (String) -> Unit) {
        if (ringing) return cb("Phone already bajche! Thambe bolle \"stop\" bolo.")
        val secs = seconds.coerceIn(5, 120)
        Thread {
            runCatching {
                ringing = true
                val am = ctx.getSystemService(AudioManager::class.java)
                val oldVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val mp = MediaPlayer().apply {
                    setDataSource(ctx, uri)
                    setAudioStreamType(AudioManager.STREAM_ALARM)
                    isLooping = true
                    prepare()
                    start()
                }
                player = mp
                val vib = ctx.getSystemService(Vibrator::class.java)
                val cm = ctx.getSystemService(CameraManager::class.java)
                val camId = runCatching { cm.cameraIdList.firstOrNull() }.getOrNull()
                val end = System.currentTimeMillis() + secs * 1000L
                var torchOn = false
                while (ringing && System.currentTimeMillis() < end) {
                    runCatching { vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1)) }
                    if (camId != null) {
                        runCatching { torchOn = !torchOn; cm.setTorchMode(camId, torchOn) }
                    }
                    Thread.sleep(600)
                }
                stopInternal(ctx, am, oldVol)
            }.onFailure {
                ringing = false
                runCatching { player?.stop(); player?.release() }
                player = null
            }
            cb("📱 Phone bajano sesh — pelam to?")
        }.start()
    }

    fun stop(ctx: Context): String {
        if (!ringing) return "Phone bajche na ekhon."
        ringing = false
        val am = ctx.getSystemService(AudioManager::class.java)
        runCatching { player?.stop(); player?.release() }
        player = null
        runCatching {
            val cm = ctx.getSystemService(CameraManager::class.java)
            cm.cameraIdList.firstOrNull()?.let { cm.setTorchMode(it, false) }
        }
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamVolume(AudioManager.STREAM_ALARM), 0)
        return "✅ Ringing thamiye dilam."
    }

    private fun stopInternal(ctx: Context, am: AudioManager, oldVol: Int) {
        ringing = false
        runCatching { player?.stop(); player?.release() }
        player = null
        runCatching {
            val cm = ctx.getSystemService(CameraManager::class.java)
            cm.cameraIdList.firstOrNull()?.let { cm.setTorchMode(it, false) }
        }
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, oldVol, 0) }
    }
}
