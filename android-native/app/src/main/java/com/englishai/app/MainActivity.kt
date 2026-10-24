package com.englishai.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var bridge: NativeBridge
    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) bridge.importModel(uri) }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) bridge.startListeningGranted() else bridge.speechError("Microphone permission is required for voice practice.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webChromeClient = WebChromeClient()
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                bridge.loadModel()
                bridge.refreshVoices()
            }
        }
        bridge = NativeBridge(this, web, { pickModel.launch(arrayOf("*/*")) }, { requestMic() })
        web.addJavascriptInterface(bridge, "NativeBridge")
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
    }
    private fun requestMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) bridge.startListeningGranted()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    override fun onBackPressed() { web.evaluateJavascript("window.Native && window.Native.onBack ? window.Native.onBack() : false") { handled -> if (handled != "true") finish() } }
    override fun onDestroy() { if (::bridge.isInitialized) bridge.close(); if (::web.isInitialized) web.destroy(); super.onDestroy() }
}
