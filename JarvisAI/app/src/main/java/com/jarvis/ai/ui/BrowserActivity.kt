package com.jarvis.ai.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.ai.core.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * In-app browser (v1.2, additive only).
 * "Google kholo", "Google-e X search koro", "browser kholo" — Jarvis
 * opens Google here, searches, and renders the results in this screen.
 * v1.3.9: full browser-like navigation — back / forward / reload, bookmarks,
 * file downloads to Downloads, and the URL bar follows the page.
 */
class BrowserActivity : AppCompatActivity() {
    private lateinit var wv: WebView
    private lateinit var bar: EditText
    private lateinit var bookmarkRow: LinearLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#05070D")) }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(16, 16, 16, 8) }
        bar = EditText(this).apply {
            hint = "Search Google or type a URL…"; setTextColor(Color.WHITE); textSize = 14f
            setBackgroundResource(com.jarvis.ai.R.drawable.input_bg); setPadding(24, 16, 24, 16)
        }
        row.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
        val go = TextView(this).apply {
            text = " GO "; setTextColor(Color.parseColor("#22D3EE")); textSize = 15f; setPadding(20, 16, 20, 16)
            setOnClickListener { load(bar.text.toString()) }
        }
        row.addView(go)
        val chrome = TextView(this).apply {
            text = " CHROME "; setTextColor(Color.parseColor("#FF7A1A")); textSize = 13f; setPadding(12, 16, 12, 16)
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(wv.url ?: "https://www.google.com"))) }
        }
        row.addView(chrome)
        root.addView(row)
        // v1.3.9: navigation toolbar — back / forward / reload / bookmark
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(16, 0, 16, 8) }
        fun navBtn(label: String, click: () -> Unit) = TextView(this).apply {
            text = " $label "; setTextColor(Color.parseColor("#E6F1FF")); textSize = 16f; setPadding(18, 10, 18, 10)
            setBackgroundResource(com.jarvis.ai.R.drawable.chip_bg); setOnClickListener { click() }
        }
        nav.addView(navBtn("◀") { if (wv.canGoBack()) wv.goBack() else Toast.makeText(this, "Ar pichone jaoa jabe na", Toast.LENGTH_SHORT).show() })
        nav.addView(navBtn("▶") { if (wv.canGoForward()) wv.goForward() else Toast.makeText(this, "Ar samne jaoa jabe na", Toast.LENGTH_SHORT).show() })
        nav.addView(navBtn("⟳") { wv.reload() })
        nav.addView(navBtn("★ Bookmark") {
            val url = wv.url ?: return@navBtn
            val title = (wv.title ?: url).take(40)
            addBookmark(title, url)
            Toast.makeText(this, "Bookmark save holo: $title", Toast.LENGTH_SHORT).show()
        })
        nav.addView(navBtn("✕ Clear") {
            Prefs.bookmarksJson = "[]"; refreshBookmarks()
            Toast.makeText(this, "Bookmarks clear kore dilam", Toast.LENGTH_SHORT).show()
        })
        root.addView(nav)
        // bookmarks row
        bookmarkRow = LinearLayout(this)
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(bookmarkRow)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
        wv = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    url?.let { bar.setText(it) }
                }
            }
            webChromeClient = WebChromeClient()
            // v1.3.9: downloads go to the Downloads folder
            setDownloadListener { url, _, contentDisposition, mimetype, _ ->
                runCatching {
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    val req = DownloadManager.Request(Uri.parse(url))
                        .setMimeType(mimetype)
                        .setTitle(fileName)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    Toast.makeText(this@BrowserActivity, "Download shuru: $fileName", Toast.LENGTH_LONG).show()
                }.onFailure { Toast.makeText(this@BrowserActivity, "Download holo na: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
        root.addView(wv, LinearLayout.LayoutParams(-1, -1, 1f))
        setContentView(root)
        refreshBookmarks()
        val q = intent.getStringExtra("query") ?: ""
        val u = intent.getStringExtra("url") ?: ""
        when {
            u.isNotBlank() -> load(u)
            q.isNotBlank() -> load(q)
            else -> wv.loadUrl("https://www.google.com")
        }
    }

    private fun load(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
        bar.setText(url)
        wv.loadUrl(url)
    }

    private fun bookmarks(): JSONArray = runCatching { JSONArray(Prefs.bookmarksJson) }.getOrDefault(JSONArray())

    private fun addBookmark(title: String, url: String) {
        val a = bookmarks()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("url") == url) return
        a.put(JSONObject().put("title", title).put("url", url))
        Prefs.bookmarksJson = a.toString()
        refreshBookmarks()
    }

    private fun refreshBookmarks() {
        if (!::bookmarkRow.isInitialized) return
        bookmarkRow.removeAllViews()
        val a = bookmarks()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val title = o.optString("title"); val url = o.optString("url")
            bookmarkRow.addView(TextView(this).apply {
                text = "★ $title"; setTextColor(Color.parseColor("#FFD2B0")); textSize = 12f
                setPadding(20, 10, 20, 10); setBackgroundResource(com.jarvis.ai.R.drawable.chip_bg)
                setOnClickListener { load(url) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 12 })
        }
    }

    override fun onBackPressed() {
        if (wv.canGoBack()) wv.goBack() else super.onBackPressed()
    }
}
