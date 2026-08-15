package com.yingjie.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/** 内置 WebView 登录页：打开抖音网页，自动抓取浏览器 Cookie 保存，免去电脑 F12 复制 */
class LoginActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var platform = "douyin"
    private var loginUrl = "https://www.douyin.com/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = intent.getStringExtra("platform") ?: "douyin"
        loginUrl = if (platform == "kuaishou") "https://www.kuaishou.com/" else "https://www.douyin.com/"
        val label = if (platform == "kuaishou") "快手" else "抖音"
        title = "登录$label 抓取 Cookie"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setFitsSystemWindows(true)
        }

        // 顶部操作栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        status = TextView(this).apply {
            text = "网页加载完成后，点右侧黄色按钮抓取"
            textSize = 13f
        }
        bar.addView(
            status,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        bar.addView(
            Button(this).apply {
                text = "抓取Cookie"
                isAllCaps = false
                setBackgroundColor(0xFFFFC53D.toInt())
                setTextColor(0xFF102A43.toInt())
                setPadding(dp(16), dp(6), dp(16), dp(6))
                setOnClickListener { grabCookie() }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        root.addView(bar)

        // 进度条
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(
            progress,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)),
        )

        // WebView
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        configureWebView(webView)
        root.addView(webView)

        setContentView(root)

        webView.loadUrl(loginUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        val settings: WebSettings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        // 使用桌面 UA，抖音网页版在桌面 UA 下功能完整
        settings.userAgentString = (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

        // 允许第三方 Cookie（抖音会种多个域名的 cookie）
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                status.text = "页面已加载，点顶部黄色按钮抓取（无需登录）"
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
    }

    private fun grabCookie() {
        val cookie = CookieManager.getInstance().getCookie(loginUrl)
        if (cookie.isNullOrBlank()) {
            Toast.makeText(this, "还没拿到 Cookie，请等页面加载完（或先登录）再点", Toast.LENGTH_LONG).show()
            return
        }
        val key = if (platform == "kuaishou") "cookie_kuaishou" else "cookie_douyin"
        getSharedPreferences("yingjie", Context.MODE_PRIVATE)
            .edit().putString(key, cookie).apply()
        status.text = "✅ 已抓取并保存 Cookie（长度 ${cookie.length}）"
        Toast.makeText(this, "Cookie 已保存，返回即可提取抖音", Toast.LENGTH_LONG).show()
        // 延迟返回，让用户看到提示
        webView.postDelayed({ finish() }, 800)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
