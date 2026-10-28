package com.yuanfeng.webshell.factory.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.yuanfeng.webshell.factory.R
import com.yuanfeng.webshell.factory.data.AppRepository
import com.yuanfeng.webshell.factory.gen.AppBuilder
import com.yuanfeng.webshell.factory.gen.IconRenderer

/** 首页：已生成的 App 列表 + 「新建」按钮 */
class MainActivity : AppCompatActivity() {

    private lateinit var repo: AppRepository
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(R.id.root).applySystemBarsPadding()

        repo = AppRepository(this)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        findViewById<ExtendedFloatingActionButton>(R.id.fab_new).setOnClickListener {
            startActivity(Intent(this, EditActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh() // 从编辑页返回、或在系统里卸载了某个 App 后，都需要刷新状态
    }

    private fun refresh() {
        list.removeAllViews()
        val apps = repo.all().sortedBy { it.name.lowercase() }
        empty.isVisible = apps.isEmpty()
        val iconPx = (48 * resources.displayMetrics.density).toInt()
        for (app in apps) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_app, list, false)
            val icon = if (app.letterIcon) null else repo.loadIcon(app.id)
            row.findViewById<ImageView>(R.id.icon).setImageBitmap(
                IconRenderer.preview(IconRenderer.layers(icon, app.name, app.themeColor), iconPx)
            )
            row.findViewById<TextView>(R.id.name).text = app.name
            row.findViewById<TextView>(R.id.url).text = app.url
            val installed = AppBuilder.installedVersionCode(this, app.packageName) != null
            row.findViewById<TextView>(R.id.status).setText(
                when {
                    !installed -> R.string.status_not_installed
                    app.installedHash != app.contentHash() -> R.string.status_outdated
                    else -> R.string.status_installed
                }
            )
            row.setOnClickListener {
                startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_ID, app.id))
            }
            list.addView(row)
        }
    }
}
