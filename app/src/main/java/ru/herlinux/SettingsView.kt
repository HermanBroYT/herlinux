package ru.herlinux

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SettingsView(private val a: MainActivity) : LinearLayout(a) {
    private val title = TextView(a)
    private val back = TextView(a)
    private val body = FrameLayout(a)
    private var current: Pair<String, () -> View>? = null
    private val purple = 0xFFB9A7E8.toInt()

    init {
        orientation = VERTICAL
        val top = LinearLayout(a)
        top.gravity = Gravity.CENTER_VERTICAL
        back.text = "←"
        back.textSize = 22f
        back.setTextColor(Color.WHITE)
        back.setPadding(a.dp(14), a.dp(6), a.dp(6), a.dp(6))
        back.visibility = View.GONE
        back.setOnClickListener { showMain(true) }
        title.text = "Настройки"
        title.textSize = 20f
        title.setTextColor(Color.WHITE)
        title.typeface = Typeface.DEFAULT_BOLD
        title.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8))
        top.addView(back)
        top.addView(title)
        addView(top)
        addView(body, LayoutParams(MP, 0, 1f))
        showMain(false)
    }

    private fun label(t: String, size: Float = 14f, color: Int = purple, bold: Boolean = false) =
        TextView(a).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, a.dp(6), 0, a.dp(4))
        }

    private fun page(build: (LinearLayout) -> Unit): View {
        val sv = ScrollView(a)
        val l = LinearLayout(a)
        l.orientation = VERTICAL
        l.setPadding(a.dp(16), a.dp(4), a.dp(16), a.dp(16))
        sv.addView(l)
        build(l)
        return sv
    }

    private fun swap(v: View, forward: Boolean, animate: Boolean) {
        body.removeAllViews()
        body.addView(v, FrameLayout.LayoutParams(MP, MP))
        if (animate && a.animOn) {
            v.alpha = 0f
            v.translationX = (if (forward) a.dp(40) else -a.dp(40)).toFloat()
            v.animate().alpha(1f).translationX(0f).setDuration(a.dur(200)).start()
        }
    }

    private fun openPage(name: String, builder: () -> View) {
        current = name to builder
        title.text = name
        back.visibility = View.VISIBLE
        swap(builder(), true, true)
    }

    private fun refresh() {
        current?.let { swap(it.second(), true, false) }
    }

    private fun showMain(animate: Boolean) {
        current = null
        title.text = "Настройки"
        back.visibility = View.GONE
        val sv = ScrollView(a)
        val l = LinearLayout(a)
        l.orientation = VERTICAL
        l.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(12))
        sv.addView(l)

        fun row(icon: String, name: String, sub: String, builder: () -> View) {
            val r = LinearLayout(a)
            r.orientation = VERTICAL
            r.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
            r.background = rounded(0x1AFFFFFF, a.dp(10).toFloat())
            r.addView(label("$icon  $name", 17f, Color.WHITE, true))
            r.addView(label(sub, 12f))
            r.setOnClickListener { openPage(name, builder) }
            val lp = LinearLayout.LayoutParams(MP, WC)
            lp.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(4))
            l.addView(r, lp)
        }
        row("📶", "Подключения", "Wi-Fi, Bluetooth, мобильная сеть") { connections() }
        row("🎨", "Оформление", "Цвета и анимации") { appearance() }
        row("🕒", "Дата и время", "Часовой пояс, формат часов") { dateTime() }
        row("🖥️", "Экран", "Разрешение, яркость") { screen() }
        row("💾", "Память", "Хранилище и ОЗУ") { memory() }
        row("ℹ️", "О программе", "HerLinux 2.1") { about() }
        swap(sv, false, animate)
    }

    // ── Подключения ──
    private fun netStatus(): String = runCatching {
        val cm = a.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "Нет подключения"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Подключено: Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Подключено: мобильная сеть"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Подключено: Ethernet"
            else -> "Подключено"
        }
    }.getOrDefault("Состояние неизвестно")

    private fun connections(): View = page { l ->
        l.addView(label("Состояние сети", 17f, Color.WHITE, true))
        l.addView(label(netStatus(), 15f))
        l.addView(btn(a, "📶 Wi-Fi") { a.openSystem(Settings.ACTION_WIFI_SETTINGS) })
        l.addView(btn(a, "🔵 Bluetooth") { a.openSystem(Settings.ACTION_BLUETOOTH_SETTINGS) })
        l.addView(btn(a, "📡 Мобильная сеть") { a.openSystem(Settings.ACTION_WIRELESS_SETTINGS) })
        l.addView(btn(a, "✈️ Режим полёта") { a.openSystem(Settings.ACTION_AIRPLANE_MODE_SETTINGS) })
    }

    // ── Оформление ──
    private fun appearance(): View = page { l ->
        l.addView(label("Цветовая схема", 17f, Color.WHITE, true))
        PALETTES.forEachIndexed { i, p ->
            l.addView(TextView(a).apply {
                text = (if (i == a.themeIdx) "✔  " else "●  ") + p.name
                setTextColor(p.accent)
                textSize = 17f
                setPadding(a.dp(8), a.dp(12), a.dp(8), a.dp(12))
                setOnClickListener {
                    a.applyTheme(i)
                    refresh()
                }
            })
        }
        l.addView(label("Анимации", 17f, Color.WHITE, true))
        l.addView(btn(a, if (a.animOn) "✔ Включены (нажмите, чтобы выключить)" else "✖ Выключены (нажмите, чтобы включить)") {
            a.setAnim(!a.animOn)
            refresh()
        })
    }

    // ── Дата и время ──
    private fun dateTime(): View = page { l ->
        val tz = TimeZone.getDefault()
        l.addView(label("Сейчас", 17f, Color.WHITE, true))
        l.addView(label(SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date()), 15f))
        l.addView(label("Часовой пояс: ${tz.displayName} (${tz.id})", 14f))
        l.addView(label("Формат часов", 17f, Color.WHITE, true))
        l.addView(btn(a, if (a.h24) "✔ 24-часовой (нажмите для 12-часового)" else "✔ 12-часовой (нажмите для 24-часового)") {
            a.set24h(!a.h24)
            refresh()
        })
        l.addView(btn(a, "🕒 Дата и время Android") { a.openSystem(Settings.ACTION_DATE_SETTINGS) })
    }

    // ── Экран ──
    @Suppress("DEPRECATION")
    private fun screen(): View = page { l ->
        val dm = a.resources.displayMetrics
        val hz = a.windowManager.defaultDisplay.refreshRate
        l.addView(label("Разрешение: ${dm.widthPixels} × ${dm.heightPixels} px", 15f))
        l.addView(label("Плотность: ${dm.densityDpi} dpi", 15f))
        l.addView(label("Частота обновления: ${hz.toInt()} Гц", 15f))
        l.addView(label("Яркость в HerLinux", 17f, Color.WHITE, true))
        val cur = a.window.attributes.screenBrightness
        val sb = SeekBar(a)
        sb.max = 100
        sb.progress = if (cur < 0f) 50 else (cur * 100).toInt()
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) a.setBrightness(p / 100f)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        l.addView(sb)
        l.addView(btn(a, "↺ Как в системе") { a.setBrightness(-1f) })
        l.addView(btn(a, "🖥️ Параметры экрана Android") { a.openSystem(Settings.ACTION_DISPLAY_SETTINGS) })
    }

    // ── Память ──
    private fun dirSize(d: File): Long = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun memory(): View = page { l ->
        val accent = PALETTES[a.themeIdx].accent
        fun bar(frac: Float) {
            val lp = LinearLayout.LayoutParams(MP, a.dp(10))
            lp.topMargin = a.dp(4)
            lp.bottomMargin = a.dp(4)
            l.addView(usageBar(a, frac, accent, a.dp(10)), lp)
        }

        l.addView(label("Внутренняя память устройства", 17f, Color.WHITE, true))
        val d = diskInfo(a.extRoot)
        if (d != null && d.first > 0) {
            val used = d.first - d.second
            bar(used.toFloat() / d.first)
            l.addView(label("Занято ${fmtSize(used)} из ${fmtSize(d.first)}\nСвободно ${fmtSize(d.second)}"))
        } else {
            l.addView(label("Нет данных"))
        }

        l.addView(label("Оперативная память (ОЗУ)", 17f, Color.WHITE, true))
        val r = a.ramInfo()
        if (r.first > 0) {
            val used = r.first - r.second
            bar(used.toFloat() / r.first)
            l.addView(label("Занято ${fmtSize(used)} из ${fmtSize(r.first)}\nСвободно ${fmtSize(r.second)}"))
        }

        l.addView(label("Диск HerLinux", 17f, Color.WHITE, true))
        l.addView(label("Файлы системы: ${fmtSize(dirSize(a.root))}\nВ корзине: ${fmtSize(dirSize(a.trash))}"))
        l.addView(btn(a, "🧹 Очистить корзину") {
            a.trash.listFiles()?.forEach { it.deleteRecursively() }
            refresh()
        })

        l.addView(label("Доступ ко всем файлам", 17f, Color.WHITE, true))
        l.addView(label(if (a.hasAllFiles()) "✔ Разрешён" else "✖ Не разрешён"))
        if (!a.hasAllFiles()) l.addView(btn(a, "🔓 Разрешить доступ") { a.requestAllFiles() })
    }

    // ── О программе ──
    private fun about(): View = page { l ->
        val ver = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull() ?: "2.1"
        l.addView(label("HerLinux $ver", 22f, Color.WHITE, true))
        l.addView(label("Рабочий стол для Android", 14f))
        l.addView(
            label(
                "Устройство: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                    "Процессор: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}", 14f
            )
        )
        l.addView(
            label(
                "Программы устанавливаются из файлов .apk. Windows-программы (.exe) на Android " +
                    "не запускаются без эмулятора.", 13f
            )
        )
    }
}
