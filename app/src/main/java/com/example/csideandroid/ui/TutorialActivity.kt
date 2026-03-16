package com.example.csideandroid.ui

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.example.csideandroid.R

class TutorialActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tutorial)

        val webView: WebView = findViewById(R.id.tutorialWebView)

        webView.settings.javaScriptEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                // Allow all local asset file:// links to load inside the WebView
                if (url.startsWith("file:///android_asset/")) {
                    view.loadUrl(url)
                    return true
                }
                // Block everything else (http/https) from opening
                return true
            }
        }

        webView.loadUrl("file:///android_asset/tutorial/index.html")
    }
}