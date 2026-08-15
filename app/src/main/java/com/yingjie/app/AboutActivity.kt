package com.yingjie.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/** 关于页：创作者标识、版本号、联系方式 */
class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // 毛玻璃背景
        FrostedGlass.blur(findViewById(R.id.glass_backdrop))

        // 版本号
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
        findViewById<TextView>(R.id.about_version).text = "版本 v$version"

        // QQ：点击复制
        findViewById<TextView>(R.id.about_qq).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("qq", "3010009516"))
            Toast.makeText(this, "QQ 号已复制", Toast.LENGTH_SHORT).show()
        }

        // GitHub：点击用浏览器打开
        findViewById<TextView>(R.id.about_github).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/firefiy99")))
        }

        findViewById<Button>(R.id.btn_close).setOnClickListener { finish() }
    }
}
