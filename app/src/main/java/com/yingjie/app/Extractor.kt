package com.yingjie.app

import com.chaquo.python.Python
import org.json.JSONObject

/** Python 进度回调接口（由 Python 线程调用，UI 更新需切回主线程） */
interface ProgressListener {
    fun onProgress(done: Long, total: Long, percent: String)
}

/** 封装 Python extractor 模块调用，均在后台线程执行；任何异常转为 {"error": ...} 返回 */
object Extractor {

    private fun module() = Python.getInstance().getModule("extractor")

    private fun errJson(e: Throwable): JSONObject {
        val msg = e.cause?.message ?: e.message ?: "未知错误"
        return JSONObject().put("error", msg)
    }

    fun test(onDone: (String) -> Unit) {
        Thread {
            try {
                onDone(module().callAttr("test").toString())
            } catch (e: Throwable) {
                onDone("ERROR: ${e.message}")
            }
        }.start()
    }

    fun extractJson(text: String, cookie: String?, onDone: (JSONObject) -> Unit) {
        Thread {
            try {
                val py = module()
                val result = if (cookie.isNullOrBlank()) {
                    py.callAttr("extract_json", text).toString()
                } else {
                    py.callAttr("extract_json", text, cookie).toString()
                }
                onDone(JSONObject(result))
            } catch (e: Throwable) {
                onDone(errJson(e))
            }
        }.start()
    }

    fun download(
        text: String,
        mode: String,
        outDir: String,
        cookie: String?,
        ffmpegPath: String?,
        listener: ProgressListener?,
        onDone: (JSONObject) -> Unit,
    ) {
        Thread {
            try {
                val py = module()
                val result = py.callAttr("download_json", text, mode, outDir, cookie, ffmpegPath, listener).toString()
                onDone(JSONObject(result))
            } catch (e: Throwable) {
                onDone(errJson(e))
            }
        }.start()
    }
}
