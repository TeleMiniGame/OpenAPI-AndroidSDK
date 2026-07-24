package com.unite.sdk

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.unite.sdk.game.GameLauncher

class WebActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loading: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        // 窗口背景置黑，避免主题白底在 WebView 首帧之前闪白
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        // 保留系统状态栏（不全屏遮挡），状态栏置黑以与页面背景统一
        window.statusBarColor = Color.BLACK
        setContentView(R.layout.activity_web)

        val url = intent.getStringExtra("URL") ?: ""
        // 只放行 http(s)：游戏 URL 由服务端下发，锁住 file:// 等本地协议，防私有文件读取
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            finish()
            return
        }
        webView = findViewById(R.id.webview)
        loading = findViewById(R.id.web_loading)
        // WebView 自身背景置黑，消除内容渲染前的白屏闪烁
        webView.setBackgroundColor(Color.BLACK)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        // 加载的是服务端下发 URL；file 协议访问在 API≤29 默认开放，显式关死
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        // HTTPS 页面默认不悄悄加载 HTTP 子资源（音视频等被动内容仍放行，兼容旧素材）
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        // 启用缓存与本地存储，加快重复加载
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        webView.webViewClient = object : WebViewClient() {
            // 首帧内容可见即隐藏 loading，缩短感知等待时间
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                loading.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loading.visibility = View.GONE
            }
        }
        loading.visibility = View.VISIBLE
        webView.loadUrl(url)
        GameLauncher.notifyGameStart()

        findViewById<ImageButton>(R.id.btn_bar_back).setOnClickListener { onBackPressed() }
        findViewById<ImageButton>(R.id.btn_bar_reload).setOnClickListener {
            it.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_once))
            loading.visibility = View.VISIBLE
            webView.reload()
        }
    }

    // 生命周期托管：离开页面即暂停 JS/音视频，防止游戏在后台继续出声、耗电
    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        // 彻底销毁 WebView，并释放 GameLauncher 持有的 listener/slot（否则每开一局泄漏一个 Activity）
        if (::webView.isInitialized) {
            webView.loadUrl("about:blank")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        GameLauncher.notifyGameClose()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
