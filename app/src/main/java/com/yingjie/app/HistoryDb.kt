package com.yingjie.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryItem(
    val id: Long,
    val url: String,
    val title: String,
    val mode: String,
    val filePath: String,
    val caption: String,
    val createdAt: Long,
)

/** 下载/提取历史记录（SQLite） */
class HistoryDb(context: Context) : SQLiteOpenHelper(context, "yingjie_history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                mode TEXT NOT NULL,
                file_path TEXT,
                caption TEXT,
                created_at INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(url: String, title: String, mode: String, filePath: String?, caption: String?) {
        writableDatabase.insert(
            "history", null, ContentValues().apply {
                put("url", url)
                put("title", title)
                put("mode", mode)
                put("file_path", filePath)
                put("caption", caption)
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    fun all(): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        readableDatabase.query(
            "history", null, null, null, null, null, "created_at DESC", "200",
        ).use { c ->
            while (c.moveToNext()) {
                list += HistoryItem(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    url = c.getString(c.getColumnIndexOrThrow("url")) ?: "",
                    title = c.getString(c.getColumnIndexOrThrow("title")) ?: "",
                    mode = c.getString(c.getColumnIndexOrThrow("mode")) ?: "",
                    filePath = c.getString(c.getColumnIndexOrThrow("file_path")) ?: "",
                    caption = c.getString(c.getColumnIndexOrThrow("caption")) ?: "",
                    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                )
            }
        }
        return list
    }

    fun clear() {
        writableDatabase.delete("history", null, null)
    }
}
