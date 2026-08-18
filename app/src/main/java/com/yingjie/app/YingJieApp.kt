package com.yingjie.app

import com.chaquo.python.android.PyApplication
import java.io.File

/** 继承 PyApplication 以自动初始化 Python 运行时（Chaquopy） */
class YingJieApp : PyApplication() {

    override fun onCreate() {
        // Chaquopy AssetFinder 缓存修复：
        // APK 内 asset 时间戳固定（1981-01-01），pm install -r 升级后 AssetFinder
        // 不会检测到 APK 变化，仍加载旧缓存中的 extractor 模块，偶发报
        // "AttributeError: module 'extractor' has no attribute 'extract_json'"。
        // 这里在 Python 初始化之前，按 lastUpdateTime 变化清除旧缓存，强制从 APK 重新解压。
        // 用 lastUpdateTime 而不是 versionCode：同版本重装（APK 内容可能不同）也会触发重建。
        try {
            val cur = try {
                packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
            } catch (_: Exception) {
                ""
            }
            val stampDir = File(filesDir, "chaquopy")
            val stamp = File(stampDir, ".asset_stamp")
            val last = if (stamp.exists()) stamp.readText() else null
            if (last != cur) {
                val af = File(stampDir, "AssetFinder")
                if (af.exists()) af.deleteRecursively()
                stampDir.mkdirs()
                stamp.writeText(cur)
            }
        } catch (_: Exception) {
        }
        super.onCreate()
    }
}