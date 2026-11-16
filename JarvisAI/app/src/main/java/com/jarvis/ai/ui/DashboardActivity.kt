package com.jarvis.ai.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.ai.core.AdaptiveLearning
import com.jarvis.ai.core.IntegrityChain
import com.jarvis.ai.core.Prefs
import com.jarvis.ai.core.ResourceManager
import com.jarvis.ai.tools.Diagnostics
import com.jarvis.ai.tools.Scheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.3.9 — Advanced Dashboard: live system performance + usage monitoring.
 * Battery, memory, storage, CPU, network, uptime, current mood, top tools,
 * predicted next actions, schedules, integrity chain, encryption and safe
 * mode — refreshed every 5 seconds while open.
 */
class DashboardActivity : AppCompatActivity() {
    private lateinit var body: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 5000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#05070D"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "📊 JARVIS DASHBOARD"; setTextColor(Color.parseColor("#22D3EE")); textSize = 20f
            typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.CENTER
        })
        body = TextView(this).apply {
            setTextColor(Color.parseColor("#E6F1FF")); textSize = 14f
            typeface = Typeface.MONOSPACE; setPadding(0, dp(12), 0, dp(12))
        }
        val scroll = ScrollView(this).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "⟲ Refresh    ✕ Close"; setTextColor(Color.parseColor("#FFD2B0")); textSize = 15f
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(6))
            setOnClickListener { refresh() }
        })
        root.addView(TextView(this).apply {
            text = "Close"; setTextColor(Color.parseColor("#7D8BA6")); textSize = 14f
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { finish() }
        })
        setContentView(root)
        refresh()
    }

    override fun onResume() { super.onResume(); handler.postDelayed(refresher, 5000) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresher) }

    private fun refresh() {
        Thread {
            val s = ResourceManager.status(this)
            val text = buildString {
                appendLine("Updated: " + SimpleDateFormat("HH:mm:ss", Locale.ENGLISH).format(Date()))
                appendLine()
                appendLine("── PERFORMANCE ──")
                appendLine("Battery: ${s.battery}% ${if (s.charging) "(charging)" else ""}")
                appendLine("Memory: ${s.memAvailMB}MB free / ${s.memTotalMB}MB${if (s.lowMemory) " ⚠️ LOW" else ""}")
                appendLine("Storage: ${s.storageFreeMB}MB free / ${s.storageTotalMB}MB")
                appendLine("CPU cores: ${Runtime.getRuntime().availableProcessors()}")
                appendLine()
                appendLine("── DEVICE ──")
                append(Diagnostics.report(this@DashboardActivity).substringAfter("🔍 Device diagnostics (no root):\n"))
                appendLine("── USAGE & BEHAVIOUR ──")
                appendLine("Mood now: ${Prefs.lastMood.ifBlank { "—" }}")
                appendLine("Interaction style: ${AdaptiveLearning.dominantStyle()}")
                appendLine("Top tools: ${AdaptiveLearning.topTools(5).joinToString(", ").ifBlank { "—" }}")
                AdaptiveLearning.proactiveHint()?.let { appendLine(it) }
                appendLine()
                appendLine("── SCHEDULES ──")
                appendLine(Scheduler.listAll())
                appendLine()
                appendLine("── SECURITY ──")
                appendLine("Encryption: ${if (Prefs.encryptLocal) "ON (AES-256-GCM)" else "OFF"}")
                appendLine("Integrity chain: ${IntegrityChain.count()} records")
                appendLine("Safe mode: ${if (Prefs.safeMode) "ON ⚠️" else "off"} (crashes: ${Prefs.crashCount})")
                appendLine("Model: ${Prefs.chatModel} / live ${Prefs.liveModel}")
            }
            runOnUiThread { body.text = text }
        }.start()
    }
}
