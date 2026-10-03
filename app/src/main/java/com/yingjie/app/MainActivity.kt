package com.yingjie.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var caption: TextView
    private lateinit var captionActions: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var modeGroup: RadioGroup
    private lateinit var extractBtn: Button

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 首次进入：须先同意免责声明才能继续使用
        if (!getSharedPreferences("yingjie", Context.MODE_PRIVATE).getBoolean("disclaimer_accepted", false)) {
            val toDisclaimer = Intent(this, DisclaimerActivity::class.java)
            intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { toDisclaimer.putExtra(Intent.EXTRA_TEXT, it) }
            startActivity(toDisclaimer)
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        // 背景层毛玻璃（Android 12+ 真模糊，只模糊背景不模糊文字）
        FrostedGlass.blur(findViewById(R.id.glass_backdrop))

        input = findViewById(R.id.input)
        status = findViewById(R.id.status)
        caption = findViewById(R.id.caption)
        captionActions = findViewById(R.id.caption_actions)
        progress = findViewById(R.id.progress)
        modeGroup = findViewById(R.id.mode_group)
        extractBtn = findViewById(R.id.btn_extract)

        extractBtn.setOnClickListener { runExtract() }
        findViewById<Button>(R.id.btn_history).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<Button>(R.id.btn_copy).setOnClickListener { copyCaption() }
        findViewById<Button>(R.id.btn_save_txt).setOnClickListener { saveCaptionTxt() }
        findViewById<Button>(R.id.btn_cookie).setOnClickListener { showCookieDialog() }
        findViewById<Button>(R.id.btn_help).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        findViewById<TextView>(R.id.btn_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // 从其他 App 分享进来的链接自动填入
        val shared = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!shared.isNullOrBlank()) {
            input.setText(shared.trim())
            status.text = "已接收分享链接，点击「提取」开始"
        }

        // 后台验证 Python + yt-dlp 内核
        Extractor.test { v ->
            runOnUiThread {
                if (!busy) status.text = "yt-dlp 内核 v$v 已就绪，粘贴链接即可提取"
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!shared.isNullOrBlank()) {
            input.setText(shared.trim())
        }
    }

    private fun selectedMode(): String = when (modeGroup.checkedRadioButtonId) {
        R.id.mode_audio -> "audio"
        R.id.mode_text -> "text"
        else -> "video"
    }

    private fun cookie(platform: String): String? {
        val key = when (platform) {
            "kuaishou" -> "cookie_kuaishou"
            "sph" -> "cookie_sph"
            else -> "cookie_douyin"
        }
        val c = getSharedPreferences("yingjie", Context.MODE_PRIVATE).getString(key, "")
        return c?.takeIf { it.isNotBlank() }
    }

    private fun detectPlatform(text: String): String? {
        val t = text.lowercase()
        return when {
            t.contains("kuaishou") -> "kuaishou"
            t.contains("weixin.qq.com/sph") || t.contains("channels.weixin.qq.com") || t.contains("finder.video.qq.com") -> "sph"
            t.contains("douyin") || t.contains("iesdouyin") -> "douyin"
            else -> null
        }
    }

    private fun cookieFor(text: String): String? {
        val p = detectPlatform(text) ?: return null
        return cookie(p)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        extractBtn.isEnabled = !value
        progress.visibility = if (value) android.view.View.VISIBLE else android.view.View.GONE
        if (!value) progress.progress = 0
    }

    private fun runExtract() {
        if (busy) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "请先粘贴链接或分享口令", Toast.LENGTH_SHORT).show()
            return
        }
        // Android 8/9 需要存储权限才能写公共下载目录
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
            return
        }
        caption.visibility = android.view.View.GONE
        captionActions.visibility = android.view.View.GONE

        when (selectedMode()) {
            "text" -> extractText(text)
            else -> downloadMedia(text, selectedMode())
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                runExtract()
            } else {
                Toast.makeText(this, "需要存储权限才能保存文件", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 文案模式：解析并显示文案 */
    private fun extractText(text: String) {
        setBusy(true)
        status.text = "正在解析文案…"
        Extractor.extractJson(text, cookieFor(text)) { json ->
            runOnUiThread {
                setBusy(false)
                handleResult(json, mode = "text")
            }
        }
    }

    /** 视频/音频模式：下载到私有缓存，再转存到公共下载目录 */
    private fun downloadMedia(text: String, mode: String) {
        setBusy(true)
        val modeLabel = if (mode == "audio") "音频" else "视频"
        status.text = "正在解析并下载${modeLabel}…"
        val outDir = File(cacheDir, "python-out").apply { mkdirs() }
        progress.max = 100
        Extractor.download(text, mode, outDir.absolutePath, cookieFor(text), null, object : ProgressListener {
            override fun onProgress(done: Long, total: Long, percent: String) {
                runOnUiThread {
                    if (total > 0) {
                        val p = ((done * 100) / total).toInt().coerceIn(0, 100)
                        progress.progress = p
                        status.text = "下载中 $percent（${fmtSize(done)}/${fmtSize(total)}）"
                    } else if (done > 0) {
                        status.text = "下载中 ${fmtSize(done)}…"
                    } else {
                        status.text = "准备下载…"
                    }
                }
            }
        }) { json ->
            runOnUiThread {
                setBusy(false)
                handleResult(json, mode = mode)
            }
        }
    }

    private fun handleResult(json: JSONObject, mode: String) {
        if (json.has("error")) {
            status.text = "提取失败：${json.getString("error")}"
            Toast.makeText(this, json.getString("error"), Toast.LENGTH_LONG).show()
            return
        }

        val title = json.optString("title", "未命名")
        val url = input.text.toString().trim()

        if (mode == "text") {
            val desc = json.optString("description", "")
            if (desc.isBlank()) {
                status.text = "该视频没有可提取的文案"
                db().add(url, title, "text", null, "")
                return
            }
            caption.text = desc
            caption.visibility = android.view.View.VISIBLE
            captionActions.visibility = android.view.View.VISIBLE
            status.text = "文案提取成功（$title）"
            db().add(url, title, "text", null, desc)
        } else {
            val videoPath = json.optString("video_path", "")
            val audioPath = json.optString("audio_path", "")
            val path = json.optString("path", "")
            val ext = json.optString("ext", "mp4")

            val src: File?
            if (videoPath.isNotEmpty() && audioPath.isNotEmpty()) {
                // B站等 DASH：用 MediaMuxer 合并音视频
                val outFile = File(cacheDir, "muxed_${System.currentTimeMillis()}.mp4")
                status.text = "正在合并音视频…"
                val ok = MuxerHelper.merge(videoPath, audioPath, outFile.absolutePath)
                File(videoPath).delete()
                File(audioPath).delete()
                src = if (ok && outFile.exists()) outFile else null
                if (src == null) {
                    status.text = "合并失败，请重试"
                    return
                }
            } else if (path.isNotEmpty()) {
                src = File(path)
            } else {
                src = null
            }

            if (src == null || !src.exists()) {
                status.text = "提取失败：文件不存在"
                return
            }
            try {
                val displayName = if (mode == "audio") src.name else "${title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)}.mp4"
                val uri: Uri? = SaveUtil.saveToDownloads(this, src, displayName)
                if (uri != null) {
                    val modeLabel = if (mode == "audio") "音频" else "视频"
                    status.text = "✅ ${modeLabel}已保存：下载/萤截/$displayName"
                    Toast.makeText(this, "${modeLabel}已保存到 下载/萤截/", Toast.LENGTH_LONG).show()
                    db().add(url, title, mode, uri.toString(), null)
                    src.delete()
                } else {
                    status.text = "保存失败：无法写入下载目录"
                }
            } catch (e: Exception) {
                status.text = "保存失败：${e.message}"
                Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun db() = HistoryDb(this)

    /** 判断是否为登录后的元宝 Cookie（hy_user/hy_token 或 uin/skey 等登录凭证，而不是只有设备指纹） */
    private fun hasSphLoginMarker(cookie: String): Boolean {
        val markers = arrayOf("hy_user=", "hy_token=", "uin=", "skey=", "p_skey=", "pt_key=", "sessionid", "session_id", "access_token", "auth_token", "refresh_token")
        return markers.any { cookie.contains(it, ignoreCase = true) }
    }

    private fun copyCaption() {
        if (caption.text.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("caption", caption.text))
        Toast.makeText(this, "文案已复制", Toast.LENGTH_SHORT).show()
    }

    private fun saveCaptionTxt() {
        if (caption.text.isBlank()) return
        try {
            val uri = SaveUtil.saveCaptionTxt(this, "萤截文案", caption.text.toString())
            if (uri != null) {
                Toast.makeText(this, "已保存到 下载/萤截/", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCookieDialog() {
        val options = arrayOf("抖音 Cookie", "快手 Cookie", "视频号 Cookie（元宝）")
        AlertDialog.Builder(this)
            .setTitle("设置 Cookie")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCookieInput("douyin")
                    1 -> showCookieInput("kuaishou")
                    2 -> showCookieInput("sph")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCookieInput(platform: String) {
        val label = when (platform) {
            "kuaishou" -> "快手"
            "sph" -> "视频号"
            else -> "抖音"
        }
        val hintText = when (platform) {
            "sph" -> "粘贴元宝网页版 Cookie（在电脑浏览器登录 yuanbao.tencent.com 后复制，形如 uin=...; qqmusic_uin=...; ...）"
            "kuaishou" -> "粘贴快手网页版的 Cookie（kuaishou.server.web_st=...; ...）"
            else -> "粘贴抖音网页版的 Cookie（ttwid=...; ...）"
        }
        val message = when (platform) {
            "sph" -> "视频号解析需要「元宝」（yuanbao.tencent.com）网页版登录 Cookie：\n\n1. 在电脑浏览器打开 yuanbao.tencent.com 并登录\n2. 按 F12 → Network → 刷新页面 → 复制任意请求的 Cookie 请求头\n3. 粘贴到下面保存即可（元宝 Cookie 失效后重新抓一次）\n\n也可以点「一键登录抓取」在 App 内打开元宝网页，登录后自动抓取。"
            "kuaishou" -> "可以点「一键登录抓取」在 App 内打开快手网页自动获取，也可以手动粘贴 Cookie。"
            else -> "可以点「一键登录抓取」在 App 内打开抖音网页自动获取，也可以手动粘贴 Cookie。"
        }
        val edit = EditText(this).apply {
            hint = hintText
            setText(cookie(platform) ?: "")
            minLines = 3
            maxLines = 8
            setPadding(32, 16, 32, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("设置 $label Cookie")
            .setMessage(message)
            .setView(edit)
            .setPositiveButton("保存") { _, _ ->
                val c = edit.text.toString().trim()
                val key = when (platform) {
                    "kuaishou" -> "cookie_kuaishou"
                    "sph" -> "cookie_sph"
                    else -> "cookie_douyin"
                }
                getSharedPreferences("yingjie", Context.MODE_PRIVATE)
                    .edit().putString(key, c).apply()
                Toast.makeText(this, if (c.isBlank()) "已清空 Cookie" else "$label Cookie 已保存", Toast.LENGTH_SHORT).show()
                // 视频号：检查是否为登录后的元宝 Cookie（防 401 踩坑）
                if (platform == "sph" && c.isNotBlank() && !hasSphLoginMarker(c)) {
                    AlertDialog.Builder(this)
                        .setTitle("Cookie 可能未登录")
                        .setMessage("这个 Cookie 里没有登录凭证（只有设备指纹），元宝接口会返回 401 提取失败。\n\n请在电脑浏览器打开 yuanbao.tencent.com，用微信扫码登录成功后，再重新复制整段 Cookie 粘贴保存。")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
            .setNeutralButton("一键登录抓取") { _, _ ->
                startActivity(Intent(this, LoginActivity::class.java).putExtra("platform", platform))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fmtSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return String.format("%.1f %s", v, units[i])
    }
}
