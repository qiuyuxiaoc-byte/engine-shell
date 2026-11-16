package com.yg.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * 只做三件事：起引擎服务、把你的 web 页面装进 WebView、把 {port, token} 递给页面。
 * 页面 = 你自己的 UI，随便什么前端框架，`npm run build` 后整目录放进 assets/web/ 即可。
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)

        startForegroundService(Intent(this, EngineService::class.java))

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(r.url)
            }
            addJavascriptInterface(Bridge(), "Engine")
            loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
        }
        setContentView(web)
    }

    /** 页面里：const {port, token, ready} = JSON.parse(Engine.info()) */
    inner class Bridge {
        @JavascriptInterface fun info(): String =
            """{"port":${EngineService.PORT},"token":"${EngineService.token ?: ""}","ready":${EngineService.ready},"error":"${(EngineService.lastError ?: "").replace("\"", "'")}"}"""

        @JavascriptInterface fun isReady(): Boolean = EngineService.ready

        /** 引擎日志尾部，页面可以做个调试面板 */
        @JavascriptInterface fun log(): String = EngineService.tail()

        /** 重启引擎（改完 patch 后热重启用） */
        @JavascriptInterface fun restart() { EngineService.requestRestart() }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
