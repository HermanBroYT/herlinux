package ru.herlinux

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class AppInfo(val label: String, val pkg: String, val icon: Drawable)

class AppsView(private val a: MainActivity) : LinearLayout(a) {
    private val list = ListView(a)
    private val status = TextView(a)
    private var apps: List<AppInfo> = emptyList()

    init {
        orientation = VERTICAL
        val bar = LinearLayout(a)
        bar.addView(btn(a, "➕ Установить .apk") { a.openInstallFolder() })
        bar.addView(btn(a, "⟳") { load() })
        status.setTextColor(0xFFB9A7E8.toInt())
        status.textSize = 12f
        status.setPadding(a.dp(12), 0, a.dp(12), a.dp(4))
        addView(bar)
        addView(status)
        addView(list, LayoutParams(MP, 0, 1f))
        list.setOnItemClickListener { _, _, i, _ ->
            a.packageManager.getLaunchIntentForPackage(apps[i].pkg)?.let { runCatching { a.startActivity(it) } }
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            askRemove(apps[i])
            true
        }
        load()
    }

    @Suppress("DEPRECATION")
    private fun load() {
        status.text = "Загрузка…"
        Thread {
            val pm = a.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val res = runCatching { pm.queryIntentActivities(i, 0) }.getOrDefault(emptyList())
            val out = res.filter { it.activityInfo.packageName != a.packageName }
                .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
                .sortedBy { it.label.lowercase() }
            a.runOnUiThread {
                apps = out
                list.adapter = Adapter()
                status.text = "Программ: ${out.size}. Нажмите — запустить, удерживайте — удалить."
            }
        }.start()
    }

    private fun askRemove(p: AppInfo) {
        AlertDialog.Builder(a).setTitle("Удалить «${p.label}»?")
            .setPositiveButton("Удалить") { _, _ ->
                runCatching { a.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${p.pkg}"))) }
            }
            .setNegativeButton("Отмена", null).show()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val p = apps[position]
            val row = LinearLayout(a)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
            val img = ImageView(a)
            img.setImageDrawable(p.icon)
            row.addView(img, LinearLayout.LayoutParams(a.dp(40), a.dp(40)))
            row.addView(TextView(a).apply {
                text = p.label
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(a.dp(12), 0, 0, 0)
            })
            return row
        }
    }
}
