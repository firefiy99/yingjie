package com.yingjie.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 保存文件到系统"下载/萤截"目录，兼容 Android 8~15 */
object SaveUtil {

    const val SAVE_DIR_NAME = "萤截"

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "m4a", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus" -> "audio/opus"
        "ogg" -> "audio/ogg"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    /**
     * 把 src 保存到公共下载目录。
     * API 29+：MediaStore.Downloads（无需权限）；API 26-28：直接写公共目录（需 WRITE_EXTERNAL_STORAGE）。
     * 返回最终可访问的 Uri。
     */
    fun saveToDownloads(context: Context, src: File, displayName: String): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(displayName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_DIR_NAME)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                SAVE_DIR_NAME,
            )
            if (!dir.exists()) dir.mkdirs()
            val dst = File(dir, displayName)
            src.copyTo(dst, overwrite = true)
            return Uri.fromFile(dst)
        }
    }

    /** 保存文案为 txt 到下载目录 */
    fun saveCaptionTxt(context: Context, title: String, content: String): Uri? {
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(40).ifBlank { "文案" }
        val tmp = File(context.cacheDir, "caption_$safeTitle.txt")
        tmp.writeText(content, Charsets.UTF_8)
        return saveToDownloads(context, tmp, "$safeTitle.txt")
    }
}
