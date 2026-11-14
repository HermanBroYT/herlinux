package ru.herlinux

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class MainActivity : Activity() {
    lateinit var root: File
    lateinit var docs: File
    lateinit var trash: File

    var themeIdx = 0
        private set
    var animOn = true
        private set
    var h24 = true
        private set

    private lateinit var rootLayout: FrameLayout
    private lateinit var wallpaper: WallpaperView
    private lateinit var windowLayer: FrameLayout
    private lateinit var startMenu: LinearLayout
    private lateinit var startBtn: TextView
    private lateinit var clock: TextView
    private lateinit var battery: TextView
    private lateinit var overlay: TextView
    private var bootScreen: BootScreen? = null
    private var booting = false
    private var startOpen = false

    private val windows = linkedMapOf<String, AppWindow>()
    private val tbButtons = linkedMapOf<String, TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private var timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val tbH: Int get() = dp(52)
    private val prefs: SharedPreferences get() = getSharedPreferences("her", MODE_PRIVATE)

    companion object {
        var booted = false
    }

    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    // ── Утилиты ──
    fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    fun dur(ms: Long): Long = if (animOn) ms else 0L
    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    fun ask(title: String, cb: (String) -> Unit) {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_TEXT
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty() && !n.contains('/')) cb(n)
            }
            .setNegativeButton("Отмена", null).show()
    }

    @Suppress("DEPRECATION")
    val extRoot: File get() = Environment.getExternalStorageDirectory()

    fun sdRoots(): List<File> = runCatching {
        getExternalFilesDirs(null).drop(1).filterNotNull()
            .map { File(it.absolutePath.substringBefore("/Android/data")) }
    }.getOrDefault(emptyList())

    private fun canon(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.path)

    fun isDriveRoot(f: File): Boolean {
        val c = canon(f)
        return (listOf(root, extRoot, trash) + sdRoots()).any { canon(it) == c }
    }

    fun pretty(f: File): String {
        val p = f.path
        return when {
            p == root.path -> "HerLinux:/"
            p.startsWith(root.path + "/") -> "HerLinux:" + p.removePrefix(root.path)
            p == extRoot.path -> "Память:/"
            p.startsWith(extRoot.path + "/") -> "Память:" + p.removePrefix(extRoot.path)
            else -> p
        }
    }

    fun ramInfo(): Pair<Long, Long> {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return Pair(mi.totalMem, mi.availMem)
    }

    fun openSystem(action: String) {
        runCatching { startActivity(Intent(action)) }.onFailure { toast("Недоступно на этом устройстве") }
    }

    fun setBrightness(v: Float) {
        val lp = window.attributes
        lp.screenBrightness = if (v < 0f) -1f else v.coerceAtLeast(0.03f)
        window.attributes = lp
    }

    fun setAnim(v: Boolean) {
        animOn = v
        prefs.edit().putBoolean("anim", v).apply()
    }

    fun set24h(v: Boolean) {
        h24 = v
        prefs.edit().putBoolean("h24", v).apply()
        timeFmt = SimpleDateFormat(if (v) "HH:mm" else "h:mm a", Locale.getDefault())
        updateStatus()
    }

    // ── Файлы, доступ, установка ──
    fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun askAllFiles() {
        AlertDialog.Builder(this).setTitle("Доступ к файлам")
            .setMessage("Чтобы открывать все папки телефона, разрешите HerLinux доступ ко всем файлам.")
            .setPositiveButton("Разрешить") { _, _ -> requestAllFiles() }
            .setNegativeButton("Отмена", null).show()
    }

    fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            if (runCatching { startActivity(i) }.isFailure) {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                10
            )
        }
    }

    fun openInstallFolder() {
        if (hasAllFiles()) {
            openExplorer(File(extRoot, "Download"))
        } else {
            toast("Сначала разрешите доступ ко всем файлам")
            askAllFiles()
        }
    }

    fun openFile(f: File) {
        when (f.extension.lowercase()) {
            "apk" -> installApk(f)
            "exe", "msi" -> exeDialog(f)
            "", "txt", "md", "log", "json", "xml", "csv", "ini", "cfg", "conf", "sh", "bat", "kt",
            "java", "py", "js", "html", "css", "yml", "yaml", "properties" -> openNotepad(f)
            else -> viewExternal(f, null)
        }
    }

    fun installApk(f: File) {
        if (!f.isFile || f.extension.lowercase() != "apk") {
            toast("Это не файл .apk")
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this).setTitle("Установка программ")
                .setMessage("Разрешите HerLinux устанавливать приложения, затем откройте файл ещё раз.")
                .setPositiveButton("Разрешить") { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                        )
                    }
                }
                .setNegativeButton("Отмена", null).show()
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        }.onFailure { toast("Не удалось запустить установщик") }
    }

    private fun exeDialog(f: File) {
        AlertDialog.Builder(this).setTitle(f.name)
            .setMessage(
                "Это программа для Windows (.exe). Android не может запустить её сам — нужен эмулятор " +
                    "(например, Winlator). HerLinux устанавливает программы Android: откройте файл .apk.\n\n" +
                    "Если эмулятор у вас установлен, файл можно открыть в нём."
            )
            .setPositiveButton("Открыть в…") { _, _ -> viewExternal(f, "application/octet-stream") }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun viewExternal(f: File, mime: String?) {
        val type = mime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { toast("Нет приложения для открытия этого файла") }
    }

    fun runningApps(): List<String> = windows.keys.toList()

    fun closeKey(key: String) {
        windows[key]?.let { closeWin(it) }
    }

    // ── Жизненный цикл ──
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val base = File(filesDir, "HerLinux")
        base.mkdirs()
        root = base.canonicalFile
        docs = File(root, "Документы")
        trash = File(root, "Корзина")
        listOf("Рабочий стол", "Документы", "Загрузки", "Корзина").forEach { File(root, it).mkdirs() }
        val hello = File(docs, "Привет.txt")
        if (!hello.exists()) hello.writeText("Добро пожаловать в HerLinux 2.1!\n")
        themeIdx = prefs.getInt("theme", 0).coerceIn(0, PALETTES.size - 1)
        animOn = prefs.getBoolean("anim", true)
        h24 = prefs.getBoolean("h24", true)
        timeFmt = SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", Locale.getDefault())
        setContentView(buildUi())
        applyTheme(themeIdx)
        if (!booted) {
            booted = true
            startBoot()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        bootScreen?.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (booting || overlay.visibility == View.VISIBLE) return
        if (startOpen) {
            hideStart()
            return
        }
        for (i in windowLayer.childCount - 1 downTo 0) {
            val w = windowLayer.getChildAt(i) as AppWindow
            if (w.visibility == View.VISIBLE && windows[w.key] === w) {
                closeWin(w)
                return
            }
        }
    }

    private fun startBoot() {
        booting = true
        val b = BootScreen(this) {
            booting = false
            bootScreen?.let { rootLayout.removeView(it) }
            bootScreen = null
        }
        bootScreen = b
        rootLayout.addView(b, FrameLayout.LayoutParams(MP, MP))
        b.start()
    }

    // ── Интерфейс ──
    private fun buildUi(): View {
        val r = FrameLayout(this)
        rootLayout = r
        r.setOnClickListener { hideStart() }
        wallpaper = WallpaperView(this)
        r.addView(wallpaper, FrameLayout.LayoutParams(MP, MP))

        val ic1 = FrameLayout.LayoutParams(WC, WC, Gravity.TOP or Gravity.START)
        ic1.setMargins(dp(12), dp(16), 0, 0)
        r.addView(desktopIcon("🖥️", "Компьютер") { openExplorer(null) }, ic1)

        val ic2 = FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.END)
        ic2.setMargins(0, 0, dp(12), tbH + dp(12))
        r.addView(desktopIcon("🗑️", "Корзина") { openExplorer(trash) }, ic2)

        windowLayer = FrameLayout(this)
        val wl = FrameLayout.LayoutParams(MP, MP)
        wl.bottomMargin = tbH
        r.addView(windowLayer, wl)

        startMenu = buildStart()
        startMenu.visibility = View.GONE
        val sw = min(dp(300), (resources.displayMetrics.widthPixels * 0.9f).toInt())
        val sl = FrameLayout.LayoutParams(sw, WC, Gravity.BOTTOM or Gravity.START)
        sl.setMargins(dp(8), 0, 0, tbH + dp(8))
        r.addView(startMenu, sl)

        r.addView(buildTaskbar(), FrameLayout.LayoutParams(MP, tbH, Gravity.BOTTOM))

        overlay = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            visibility = View.GONE
            isClickable = true
        }
        r.addView(overlay, FrameLayout.LayoutParams(MP, MP))
        return r
    }

    private fun desktopIcon(emoji: String, label: String, onClick: () -> Unit): View {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.gravity = Gravity.CENTER_HORIZONTAL
        l.setPadding(dp(8), dp(8), dp(8), dp(8))
        l.addView(TextView(this).apply {
            text = emoji
            textSize = 34f
            gravity = Gravity.CENTER
        })
        l.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        })
        l.setOnClickListener {
            hideStart()
            onClick()
        }
        return l
    }

    private fun buildTaskbar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(0xEE120A2A.toInt())
        bar.setPadding(dp(6), 0, dp(6), 0)
        bar.isClickable = true

        startBtn = TextView(this).apply {
            text = "H"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setOnClickListener { if (startOpen) hideStart() else showStart() }
        }
        bar.addView(startBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        val div = View(this)
        div.setBackgroundColor(0x33FFFFFF)
        val dl = LinearLayout.LayoutParams(dp(1), dp(28))
        dl.setMargins(dp(8), 0, dp(8), 0)
        bar.addView(div, dl)

        for ((key, label) in listOf("explorer" to "📁", "browser" to "🌐", "terminal" to ">_")) {
            val b = TextView(this).apply {
                text = label
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setOnClickListener {
                    hideStart()
                    toggle(key)
                }
            }
            tbButtons[key] = b
            val lp = LinearLayout.LayoutParams(dp(40), dp(40))
            lp.marginEnd = dp(2)
            bar.addView(b, lp)
        }

        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val vol = TextView(this).apply {
            text = "🔊"
            textSize = 16f
            gravity = Gravity.CENTER
            setOnClickListener {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI
                )
            }
        }
        bar.addView(vol, LinearLayout.LayoutParams(dp(32), dp(40)))

        val scr = TextView(this).apply {
            text = "🖥️"
            textSize = 16f
            gravity = Gravity.CENTER
            setOnClickListener { open("settings") }
        }
        bar.addView(scr, LinearLayout.LayoutParams(dp(32), dp(40)))

        battery = TextView(this).apply {
            text = "🔋"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        bar.addView(battery, LinearLayout.LayoutParams(WC, dp(40)))

        clock = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        bar.addView(clock, LinearLayout.LayoutParams(WC, dp(40)))
        return bar
    }

    private fun buildStart(): LinearLayout {
        val m = LinearLayout(this)
        m.orientation = LinearLayout.VERTICAL
        m.background = rounded(0xF2160D30.toInt(), dp(12).toFloat(), 2, 0x557C3AED)
        m.setPadding(dp(12), dp(12), dp(12), dp(12))
        m.isClickable = true

        m.addView(TextView(this).apply {
            text = "▦  Пуск"
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(dp(4), dp(4), dp(4), dp(10))
        })

        val items = LinearLayout(this)
        items.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        sv.addView(items)
        val maxH = min(dp(300), (resources.displayMetrics.heightPixels * 0.45f).toInt())
        m.addView(sv, LinearLayout.LayoutParams(MP, maxH))

        fun item(icon: String, label: String, action: () -> Unit) {
            items.addView(TextView(this).apply {
                text = "$icon   $label"
                setTextColor(Color.WHITE)
                textSize = 17f
                setPadding(dp(8), dp(11), dp(8), dp(11))
                setOnClickListener {
                    hideStart()
                    action()
                }
            })
        }
        item("⚙️", "Настройки") { open("settings") }
        item("⌨️", "Командной строки") { open("terminal") }
        item("🖥️", "Параметры экрана") { openSystem(Settings.ACTION_DISPLAY_SETTINGS) }
        item("🧩", "Программы") { open("apps") }
        item("📁", "Проводник") { open("explorer") }
        item("🌐", "Браузер") { open("browser") }
        item("📝", "Блокнот") { open("notepad") }
        item("🧮", "Калькулятор") { open("calc") }

        val line = View(this)
        line.setBackgroundColor(0x33FFFFFF)
        val ll = LinearLayout.LayoutParams(MP, dp(1))
        ll.setMargins(0, dp(8), 0, dp(8))
        m.addView(line, ll)

        fun foot(icon: String, label: String, restart: Boolean) {
            m.addView(TextView(this).apply {
                text = "$label   $icon"
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.END
                setPadding(dp(8), dp(9), dp(8), dp(9))
                setOnClickListener { power(restart) }
            })
        }
        foot("⏻", "Завершить работу", false)
        foot("⟳", "Перезапуск", true)
        return m
    }

    private fun showStart() {
        if (startOpen) return
        startOpen = true
        startMenu.animate().cancel()
        startMenu.visibility = View.VISIBLE
        if (animOn) {
            startMenu.alpha = 0f
            startMenu.translationY = dp(24).toFloat()
            startMenu.animate().alpha(1f).translationY(0f).setDuration(dur(180)).start()
        } else {
            startMenu.alpha = 1f
            startMenu.translationY = 0f
        }
    }

    private fun hideStart() {
        if (!startOpen) return
        startOpen = false
        startMenu.animate().cancel()
        if (!animOn) {
            startMenu.visibility = View.GONE
            return
        }
        startMenu.animate().alpha(0f).translationY(dp(24).toFloat()).setDuration(dur(140))
            .withEndAction { if (!startOpen) startMenu.visibility = View.GONE }.start()
    }

    private fun power(restart: Boolean) {
        hideStart()
        overlay.text = if (restart) "Перезапуск…" else "Завершение работы…"
        overlay.visibility = View.VISIBLE
        if (animOn) {
            overlay.alpha = 0f
            overlay.animate().alpha(1f).setDuration(400).start()
        }
        handler.postDelayed({
            if (restart) {
                booted = false
                recreate()
            } else {
                finishAffinity()
            }
        }, 1400)
    }

    private fun updateStatus() {
        val now = Date()
        clock.text = timeFmt.format(now) + "\n" + dateFmt.format(now)
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i != null) {
            val lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (lv >= 0 && sc > 0) battery.text = "🔋${lv * 100 / sc}%"
        }
    }

    fun applyTheme(i: Int) {
        themeIdx = i
        val p = PALETTES[i]
        prefs.edit().putInt("theme", i).apply()
        wallpaper.setColors(p.top, p.bottom, p.accent)
        startBtn.background = rounded(p.accent, dp(10).toFloat())
        windows.values.forEach { it.setAccent(p.accent) }
    }

    // ── Окна и приложения ──
    private fun refreshTaskbar() {
        for ((k, b) in tbButtons) {
            val vis = windows[k]?.visibility == View.VISIBLE
            b.background = if (vis) rounded(0x447C3AED, dp(8).toFloat()) else null
        }
    }

    private fun showWin(w: AppWindow) {
        w.animate().cancel()
        (w.tag as? Float)?.let {
            w.translationY = it
            w.tag = null
        }
        w.visibility = View.VISIBLE
        w.bringToFront()
        if (animOn) {
            w.alpha = 0f
            w.scaleX = 0.92f
            w.scaleY = 0.92f
            w.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(dur(180)).start()
        } else {
            w.alpha = 1f
            w.scaleX = 1f
            w.scaleY = 1f
        }
        refreshTaskbar()
    }

    private fun hideWin(w: AppWindow) {
        if (w.visibility != View.VISIBLE) return
        w.animate().cancel()
        if (!animOn) {
            w.visibility = View.GONE
            refreshTaskbar()
            return
        }
        val ty = w.translationY
        w.tag = ty
        w.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).translationY(ty + dp(60)).setDuration(dur(160))
            .withEndAction {
                w.visibility = View.GONE
                w.alpha = 1f
                w.scaleX = 1f
                w.scaleY = 1f
                w.translationY = ty
                w.tag = null
                refreshTaskbar()
            }.start()
    }

    private fun closeWin(w: AppWindow) {
        if (windows[w.key] !== w) return
        windows.remove(w.key)
        refreshTaskbar()
        w.animate().cancel()
        if (!animOn) {
            windowLayer.removeView(w)
            (w.content as? Browser)?.release()
            return
        }
        w.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(dur(150)).withEndAction {
            windowLayer.removeView(w)
            (w.content as? Browser)?.release()
        }.start()
    }

    fun showApp(key: String, title: String, small: Boolean = false, build: () -> View): AppWindow {
        val existing = windows[key]
        if (existing != null) {
            showWin(existing)
            return existing
        }
        val dm = resources.displayMetrics
        val w = min(dp(if (small) 300 else 560), (dm.widthPixels * 0.96f).toInt())
        val h = min(dp(if (small) 440 else 460), ((dm.heightPixels - tbH) * 0.8f).toInt())
        val win = AppWindow(
            this, key, title, build(), PALETTES[themeIdx].accent,
            { closeWin(it) },
            { f -> f.bringToFront(); hideStart() },
            { hideWin(it) }
        )
        windowLayer.addView(win, FrameLayout.LayoutParams(w, h))
        val off = (windows.size % 5) * dp(26)
        val maxX = (dm.widthPixels - w).coerceAtLeast(0)
        win.x = min(dp(8) + off, maxX).toFloat()
        win.y = (dp(16) + off).toFloat()
        windows[key] = win
        showWin(win)
        return win
    }

    private fun toggle(key: String) {
        val w = windows[key]
        if (w != null && w.visibility == View.VISIBLE) hideWin(w) else open(key)
    }

    private fun open(key: String) {
        when (key) {
            "explorer" -> openExplorer(null)
            "browser" -> showApp("browser", "Браузер") { Browser(this) }
            "terminal" -> showApp("terminal", "Командная строка") { Terminal(this) }
            "notepad" -> openNotepad(null)
            "calc" -> showApp("calc", "Калькулятор", true) { Calc(this) }
            "settings" -> showApp("settings", "Настройки") { SettingsView(this) }
            "apps" -> showApp("apps", "Программы") { AppsView(this) }
        }
    }

    fun openExplorer(dir: File?) {
        val existed = windows.containsKey("explorer")
        val w = showApp("explorer", "Проводник") { Explorer(this) }
        if (dir != null || existed) (w.content as Explorer).go(dir)
    }

    fun openNotepad(f: File?) {
        val w = showApp("notepad", "Блокнот") { Notepad(this) }
        if (f != null) (w.content as Notepad).load(f)
    }
}
