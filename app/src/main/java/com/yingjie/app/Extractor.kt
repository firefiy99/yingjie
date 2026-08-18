package com.yingjie.app

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import org.json.JSONObject

/** Python 进度回调接口（由 Python 线程调用，UI 更新需切回主线程） */
interface ProgressListener {
    fun onProgress(done: Long, total: Long, percent: String)
}

/**
 * 封装 Python extractor 模块调用，均在后台线程执行；任何异常转为 {"error": ...} 返回。
 *
 * 注意：Chaquopy 的 getModule() 每次都会重新执行模块顶层代码（重新 import）。
 * 重复调用时可能拿到不完整的模块对象（曾出现第二次调用报
 * AttributeError: module 'extractor' has no attribute 'download_json'）。
 * 因此这里只获取一次模块并缓存 PyObject，后续所有调用复用同一对象。
 */
object Extractor {

    @Volatile
    private var cachedModule: PyObject? = null

    @Synchronized
    private fun module(): PyObject {
        cachedModule?.let { return it }
        val m = Python.getInstance().getModule("extractor")
        cachedModule = m
        return m
    }

    private fun errJson(e: Throwable): JSONObject {
        // 输出诊断信息：Chaquopy 异常时把模块加载状态和完整堆栈打到日志
        try {
            val diag = module().callAttr("_diag", "ERR").toString()
            android.util.Log.e("YingJieExtractor", "diag=$diag err=$e")
        } catch (_: Throwable) {
        }
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
