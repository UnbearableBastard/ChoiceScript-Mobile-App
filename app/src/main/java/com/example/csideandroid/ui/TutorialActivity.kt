package com.example.csideandroid.ui

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.example.csideandroid.R

class TutorialActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tutorial)

        val webView: WebView = findViewById(R.id.tutorialWebView)

        // Basic safe settings
        webView.settings.javaScriptEnabled = false
        webView.webViewClient = WebViewClient()

        // Load the first tutorial page from assets
        webView.loadUrl("file:///android_asset/tutorial/index.html")
    }
}