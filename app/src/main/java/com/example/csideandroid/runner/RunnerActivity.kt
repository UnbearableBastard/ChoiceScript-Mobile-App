package com.example.csideandroid.runner

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import com.example.csideandroid.R
import java.io.File
import java.io.FileInputStream

/**
 * Runs the local ChoiceScript game in a WebView.
 * Uses WebView.saveState / restoreState so rotation does NOT reset the game.
 */
class RunnerActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_runner)

        webView = findViewById(R.id.webView)

        // WebView settings for the engine
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false

        // Serve assets from /android_asset/ over https
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()

                // First, try the compiled game files in filesDir/runner/mygame
                val prefix =
                    "https://appassets.androidplatform.net/assets/choicescript/web/mygame/"
                if (url.startsWith(prefix)) {
                    val relative = url.removePrefix(prefix)
                    val fileOnDisk = File(filesDir, "runner/mygame/$relative")
                    if (fileOnDisk.exists()) {
                        val mime = guessMime(fileOnDisk.name)
                        return WebResourceResponse(
                            mime,
                            "utf-8",
                            FileInputStream(fileOnDisk)
                        )
                    }
                }

                // Otherwise, fall back to the normal asset loader
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(
                    "RunnerActivity",
                    "[${message.message()} @${message.sourceId()}:${message.lineNumber()}]"
                )
                return super.onConsoleMessage(message)
            }
        }

        // System back: go back in WebView history first, then finish
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (this@RunnerActivity::webView.isInitialized && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        if (savedInstanceState != null) {
            // Restore current page + history after rotation
            webView.restoreState(savedInstanceState)
        } else {
            // First launch — clear game state if "Reset on Launch" is enabled
            val prefs = getSharedPreferences("editor_prefs", MODE_PRIVATE)
            val resetOnLaunch = prefs.getBoolean("runner_reset_on_launch", true)
            if (resetOnLaunch) {
                WebStorage.getInstance().deleteAllData()
                webView.clearCache(true)
            }
            webView.loadUrl(
                "https://appassets.androidplatform.net/assets/choicescript/web/mygame/index.html"
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Save WebView state so it survives configuration changes
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    // Simple MIME type guesser for our local files.
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
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }
    }

    override fun onDestroy() {
        try {
            if (this::webView.isInitialized) {
                webView.removeAllViews()
                webView.destroy()
            }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
