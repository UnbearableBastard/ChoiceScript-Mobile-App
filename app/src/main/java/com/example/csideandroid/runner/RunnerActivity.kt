package com.example.csideandroid.runner

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import com.example.csideandroid.R
import java.io.File
import java.io.FileInputStream

// Code for running the game through the app with a local WebView
class RunnerActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_runner)

        webView = findViewById(R.id.webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                // If engine asks for /assets/choicescript/web/mygame/... serve from internal /files/runner/mygame/...
                val prefix = "https://appassets.androidplatform.net/assets/choicescript/web/mygame/"
                if (url.startsWith(prefix)) {
                    val relative = url.removePrefix(prefix)
                    val fileOnDisk = File(filesDir, "runner/mygame/$relative")
                    if (fileOnDisk.exists()) {
                        val mime = guessMime(fileOnDisk.name)
                        return WebResourceResponse(mime, "utf-8", FileInputStream(fileOnDisk))
                    }
                }
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                Log.d("RunnerActivity", "[${'$'}{message.messageLevel()}] ${'$'}{message.message()} @${'$'}{message.sourceId()}:${'$'}{message.lineNumber()}")
                return super.onConsoleMessage(message)
            }
        }

        // Load engine index. It will request mygame/*, which we override above.
        webView.loadUrl("https://appassets.androidplatform.net/assets/choicescript/web/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "js" -> "application/javascript"
            "json" -> "application/json"
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    override fun onDestroy() {
        try {
            webView.removeAllViews()
            webView.destroy()
        } catch (_: Exception) { }
        super.onDestroy()
    }
}
