package com.yingjie.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView

/** 首次启动免责声明：须滑到底部并同意后才能进入主界面 */
class DisclaimerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_disclaimer)

        // 毛玻璃背景
        FrostedGlass.blur(findViewById(R.id.glass_backdrop))

        findViewById<TextView>(R.id.disclaimer_text).text = DISCLAIMER

        val agree = findViewById<Button>(R.id.btn_agree)
        val scroll = findViewById<ScrollView>(R.id.disclaimer_scroll)

        // 强制滑到底部后才能同意：初始禁用（灰色），滚到底恢复金色可点
        agree.isEnabled = false
        agree.backgroundTintList = ColorStateList.valueOf(0xFFB0BEC5.toInt())
        scroll.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            val sv = v as ScrollView
            val child = sv.getChildAt(0)
            if (child != null && child.bottom - (sv.height + scrollY) <= 4) {
                enableAgree(agree)
            }
        }
        // 若内容不足一屏（通常不会），直接允许
        scroll.post {
            val child = scroll.getChildAt(0)
            if (child != null && child.height <= scroll.height) enableAgree(agree)
        }

        agree.setOnClickListener {
            getSharedPreferences("yingjie", Context.MODE_PRIVATE)
                .edit().putBoolean("disclaimer_accepted", true).apply()
            val toMain = Intent(this, MainActivity::class.java)
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { toMain.putExtra(Intent.EXTRA_TEXT, it) }
            startActivity(toMain)
            finish()
        }

        // 拒绝：直接退出软件
        findViewById<Button>(R.id.btn_disagree).setOnClickListener { finishAffinity() }
    }

    private fun enableAgree(agree: Button) {
        if (!agree.isEnabled) {
            agree.isEnabled = true
            agree.backgroundTintList = ColorStateList.valueOf(0xFFFFC53D.toInt())
        }
    }

    override fun onBackPressed() {
        // 未同意前不可跳过，返回键等同退出
        finishAffinity()
    }

    companion object {
        private val DISCLAIMER = listOf(
            "欢迎使用「萤截」。使用前请仔细阅读并充分理解以下内容，点击「同意并继续」即表示您已阅读、理解并同意本声明全部条款。",
            "一、合法用途\n本应用是一款技术工具，仅用于提取您本人拥有合法权利、或已获得权利人明确授权的内容，供个人学习、研究、欣赏等合法目的使用。严禁将本应用用于任何违法违规用途。",
            "二、知识产权\n1. 您处理的内容可能受《中华人民共和国著作权法》及相关法律法规保护，您应确保对相关内容拥有合法权利或已获授权。\n2. 未经权利人许可，不得擅自下载、复制、传播、修改或商业性使用相关内容，不得侵犯他人著作权、商标权、肖像权、隐私权等合法权益。\n3. 本应用不存储、不上传、不传播任何用户处理的内容，仅在您的设备本地运行。",
            "三、平台规范\n处理抖音、快手、哔哩哔哩（B站）等平台的内容时，您应同时遵守各平台的用户协议、社区规范及服务条款。因违反平台规则导致的账号受限、内容下架等后果，由您自行承担。",
            "四、法律法规\n您应遵守《中华人民共和国著作权法》《中华人民共和国网络安全法》《中华人民共和国数据安全法》《中华人民共和国个人信息保护法》及《网络信息内容生态治理规定》等法律法规，不得利用本应用从事侵犯他人合法权益、危害网络安全、传播违法有害信息等行为。",
            "五、责任承担\n因您违反法律法规、平台规则或本声明约定使用本应用所产生的一切法律责任与后果，由您自行承担，开发者不承担任何责任。",
            "六、权利通知\n如您是权利人，认为相关内容侵犯您的合法权益，请及时联系我们，我们将依法配合处理。",
        ).joinToString("\n\n")
    }
}
