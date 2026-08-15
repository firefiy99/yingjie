package com.yingjie.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : Activity() {

    private lateinit var db: HistoryDb
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private val items = mutableListOf<HistoryItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = HistoryDb(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        emptyView = TextView(this).apply {
            text = "暂无历史记录"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }

        listView = ListView(this)

        // 自定义标题栏（NoActionBar 主题）
        root.addView(
            TextView(this).apply {
                text = "历史记录"
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(8))
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        root.addView(
            emptyView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(
            listView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        root.addView(
            Button(this).apply {
                text = "清空历史"
                setOnClickListener {
                    db.clear()
                    refresh()
                    Toast.makeText(this@HistoryActivity, "已清空", Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items.clear()
        items.addAll(db.all())
        listView.adapter = HistoryAdapter()
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private inner class HistoryAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            val tv = (convertView as? TextView) ?: TextView(this@HistoryActivity).apply {
                textSize = 14f
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt))
            val modeLabel = when (item.mode) {
                "video" -> "视频"
                "audio" -> "音频"
                "text" -> "文案"
                else -> item.mode
            }
            val suffix = if (item.filePath.isNullOrBlank() && item.mode == "text") "（已复制/查看）" else ""
            tv.text = "${item.title}\n$modeLabel · $time$suffix"
            return tv
        }
    }
}
