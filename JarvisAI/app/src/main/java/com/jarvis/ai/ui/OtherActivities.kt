package com.jarvis.ai.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.jarvis.ai.R
import com.jarvis.ai.core.Memory
import com.jarvis.ai.service.AssistantService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full conversation history (chat, voice, calls, auto-replies) with search. */
class HistoryActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#05070D")); setPadding(dp(16), dp(16), dp(16), dp(16)) }
        root.addView(TextView(this).apply { text = "HISTORY"; setTextColor(Color.parseColor("#22D3EE")); textSize = 22f; typeface = Typeface.create("monospace", Typeface.BOLD) })
        val search = EditText(this).apply {
            hint = "Search…"; setTextColor(Color.WHITE); setBackgroundResource(R.drawable.input_bg); setPadding(dp(16), dp(10), dp(16), dp(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { render(s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render("")
    }

    private fun render(q: String) {
        list.removeAllViews()
        val fmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        Memory.history().filter { q.isBlank() || it.text.contains(q, true) }.takeLast(400).reversed().forEach { m ->
            list.addView(TextView(this).apply {
                text = "${fmt.format(Date(m.time))} · ${m.mode.uppercase()} · ${if (m.role == "user") "YOU" else "JARVIS"}\n${m.text}"
                setTextColor(if (m.role == "user") Color.WHITE else Color.parseColor("#FFE9D6")); textSize = 14f; setTextIsSelectable(true)
                setBackgroundResource(if (m.role == "user") R.drawable.bubble_user else R.drawable.bubble_ai); setPadding(dp(12), dp(10), dp(12), dp(10))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }
}

/** Preview websites / web apps / games that Jarvis codes for you. */
class WebPreviewActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val f = File(intent.getStringExtra("path") ?: return finish())
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true; wv.settings.domStorageEnabled = true
        @Suppress("DEPRECATION") run { wv.settings.allowFileAccess = true; wv.settings.allowFileAccessFromFileURLs = true }
        wv.webViewClient = WebViewClient()
        setContentView(wv)
        wv.loadUrl(Uri.fromFile(f).toString())
    }
}

/** Asks Android for screen-mirroring permission, then hands it to the assistant service. */
class ScreenShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION") startActivityForResult(mpm.createScreenCaptureIntent(), 9)
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            AssistantService.send(this, AssistantService.ACTION_SCREEN, Bundle().apply { putInt("code", resultCode); putParcelable("data", data) })
        }
        finish()
    }
}

/** v1.3.1: same screen-capture permission dialog, but for VIDEO recording. */
class ScreenRecordActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION") startActivityForResult(mpm.createScreenCaptureIntent(), 10)
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            AssistantService.send(this, AssistantService.ACTION_RECORD_GRANTED, Bundle().apply {
                putInt("code", resultCode); putParcelable("data", data)
                putInt("seconds", intent.getIntExtra("seconds", 0))
            })
        }
        finish()
    }
}
