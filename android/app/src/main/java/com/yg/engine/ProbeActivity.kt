package com.yg.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 引擎自检：等引擎就绪后，打开引擎自带的 Web 界面（带 token），
 * 用来验证「agent 真的能跑命令/改文件」。这个界面不是产品 UI，只做验收。
 */
class ProbeActivity : Activity() {

    private lateinit var web: WebView
    private val h = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            webViewClient = WebViewClient()
        }
        setContentView(web)
        web.loadData("<h3 style='font:16px sans-serif;padding:24px'>等待引擎就绪…</h3>", "text/html", "utf-8")

        val tick = object : Runnable {
            override fun run() {
                val url = EngineService.authUrl
                if (EngineService.ready && !url.isNullOrBlank()) {
                    web.loadUrl(url)
                } else {
                    h.postDelayed(this, 1500)
                }
            }
        }
        h.post(tick)
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); web.destroy(); super.onDestroy() }
}
