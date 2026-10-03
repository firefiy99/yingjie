package com.yingjie.app

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class HelpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFFF5F7FA.toInt())
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(
            TextView(this).apply {
                text = "使用帮助"
                textSize = 22f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(0xFF102A43.toInt())
                setPadding(0, dp(4), 0, dp(12))
            },
        )

        root.addView(section("支持哪些平台？"))
        root.addView(body("抖音、快手、视频号（微信）、B站（哔哩哔哩）、西瓜、微博、小红书等 yt-dlp 支持的平台。\n\n" +
            "三种模式：\n🎬 视频 = 无水印视频文件（B站自动合并音视频）\n🎵 音频 = 提取音轨（m4a；视频号暂不支持）\n📝 文案 = 作品描述/简介文字"))

        root.addView(section("抖音 / 快手为什么需要 Cookie？"))
        root.addView(body("抖音和快手官方对未登录请求做了风控，必须携带 Cookie 才能解析。这是平台限制，所有同类工具都一样。\n\n" +
            "两个平台的 Cookie 是分开的，需要分别设置。"))

        root.addView(section("视频号为什么需要「元宝」Cookie？"))
        root.addView(body("微信视频号不公开视频直链，只能借助腾讯「元宝」（yuanbao.tencent.com）网页版的解析能力，因此需要你提供一个元宝登录 Cookie（登录后几天内有效）。\n\n" +
            "获取方法：\n" +
            "1. 电脑浏览器打开 yuanbao.tencent.com 并登录（用微信扫码即可）\n" +
            "2. 按 F12 → 网络 Network → 刷新页面\n" +
            "3. 点任意请求，复制「请求标头」里的整段 Cookie\n" +
            "4. 在萤截「设置 Cookie → 视频号 Cookie」里粘贴保存\n\n" +
            "也可以在 App 内点「一键登录抓取」打开元宝网页登录后自动抓取。"))

        root.addView(section("如何设置 Cookie（推荐：一键登录抓取）"))
        root.addView(body("1. 主界面点「设置Cookie」\n" +
            "2. 选择「抖音 Cookie」「快手 Cookie」或「视频号 Cookie」\n" +
            "3. 点「一键登录抓取」，App 会打开对应网页\n" +
            "4. 等网页加载完（不用登录，关掉登录弹窗即可）\n" +
            "5. 点顶部黄色「抓取Cookie」按钮\n\n" +
            "整个过程不需要登录、不需要电脑。"))

        root.addView(section("电脑手动获取 Cookie（备用方法）"))
        root.addView(body("1. 电脑浏览器打开 www.douyin.com、www.kuaishou.com 或 yuanbao.tencent.com（视频号）\n" +
            "2. 按 F12 打开开发者工具，点「网络 Network」标签\n" +
            "3. 刷新页面，点任意请求\n" +
            "4. 在「请求标头 Request Headers」里复制整段 Cookie\n" +
            "5. 粘贴到萤截对应的「抖音/快手/视频号 Cookie」输入框\n\n" +
            "提示：Cookie 可能几天后失效，失效后重新获取即可。"))

        root.addView(section("B站视频有声音吗？"))
        root.addView(body("有。B站是音视频分离（DASH）技术，萤截会自动下载视频流和音频流，然后用系统原生能力合成有声视频。下载时会有「正在合并音视频…」的提示。"))

        root.addView(section("文件保存在哪里？"))
        root.addView(body("所有文件保存在手机「文件管理 → 下载 Download → 萤截」文件夹里。"))

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun section(text: String): TextView = TextView(this).apply {
        setText(text)
        textSize = 16f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(0xFF102A43.toInt())
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun body(text: String): TextView = TextView(this).apply {
        setText(text)
        textSize = 14f
        setTextColor(0xFF486581.toInt())
        setLineSpacing(dp(2).toFloat(), 1.15f)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
