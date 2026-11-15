package ru.herlinux

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

class BootScreen(private val a: MainActivity, private val onDone: () -> Unit) : FrameLayout(a) {
    private val h = Handler(Looper.getMainLooper())
    private val boot = FrameLayout(a)
    private val center = LinearLayout(a)
    private val logo = logoView(a, a.dp(96))
    private val title = TextView(a)
    private val barBox = LinearLayout(a)
    private val loading = TextView(a)
    private val fill = View(a)
    private val welcomeBox = LinearLayout(a)
    private var loader: ValueAnimator? = null
    private val pulses = ArrayList<Animator>()
    private val word = "HerLinux"

    init {
        isClickable = true
        val p = PALETTES[a.themeIdx]

        // Экран «Добро пожаловать» (под чёрным слоем)
        val wp = WallpaperView(a)
        wp.showLogo = false
        wp.setColors(p.top, p.bottom, p.accent)
        addView(wp, LayoutParams(MP, MP))
        welcomeBox.orientation = LinearLayout.VERTICAL
        welcomeBox.gravity = Gravity.CENTER_HORIZONTAL
        welcomeBox.addView(logoView(a, a.dp(84)))
        welcomeBox.addView(TextView(a).apply {
            text = "Добро пожаловать"
            setTextColor(Color.WHITE)
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(a.dp(16), a.dp(18), a.dp(16), a.dp(6))
        })
        welcomeBox.addView(TextView(a).apply {
            text = "HerLinux 2.1"
            setTextColor(0xFFD9C8FF.toInt())
            textSize = 16f
        })
        welcomeBox.alpha = 0f
        addView(welcomeBox, LayoutParams(WC, WC, Gravity.CENTER))

        // Чёрный слой запуска
        boot.setBackgroundColor(Color.BLACK)
        center.orientation = LinearLayout.VERTICAL
        center.gravity = Gravity.CENTER_HORIZONTAL
        center.addView(logo)
        title.text = word
        title.setTextColor(Color.WHITE)
        title.textSize = 36f
        title.typeface = Typeface.DEFAULT_BOLD
        title.letterSpacing = 0.08f
        title.setPadding(0, a.dp(18), 0, 0)
        center.addView(title)
        center.alpha = 0f
        boot.addView(center, LayoutParams(WC, WC, Gravity.CENTER))

        barBox.orientation = LinearLayout.VERTICAL
        barBox.gravity = Gravity.CENTER_HORIZONTAL
        barBox.alpha = 0f
        loading.setTextColor(0xFFB9A7E8.toInt())
        loading.textSize = 13f
        barBox.addView(loading)
        val track = FrameLayout(a)
        track.background = rounded(0x33FFFFFF, a.dp(4).toFloat())
        fill.background = rounded(p.accent, a.dp(4).toFloat())
        fill.pivotX = 0f
        fill.scaleX = 0f
        track.addView(fill, LayoutParams(MP, MP))
        val tw = min(a.dp(320), (a.resources.displayMetrics.widthPixels * 0.7f).toInt())
        val tl = LinearLayout.LayoutParams(tw, a.dp(6))
        tl.topMargin = a.dp(10)
        barBox.addView(track, tl)
        val bl = LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        bl.bottomMargin = a.dp(72)
        boot.addView(barBox, bl)
        addView(boot, LayoutParams(MP, MP))
    }

    fun start() {
        h.postDelayed({ center.animate().alpha(1f).setDuration(800).start() }, 1000)
        h.postDelayed({ typeTitle() }, 2800)
    }

    private fun typeTitle() {
        title.text = ""
        var i = 0
        val r = object : Runnable {
            override fun run() {
                i++
                title.text = word.substring(0, i)
                if (i < word.length) {
                    h.postDelayed(this, 150)
                } else {
                    pulse()
                    beginLoading()
                }
            }
        }
        h.post(r)
    }

    private fun pulse() {
        val t = ObjectAnimator.ofFloat(title, "alpha", 1f, 0.55f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        val l = ObjectAnimator.ofPropertyValuesHolder(
            logo,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.1f)
        ).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        pulses.add(t)
        pulses.add(l)
    }

    private fun beginLoading() {
        val total = (10..25).random() * 1000L
        barBox.animate().alpha(1f).setDuration(500).start()
        val v = ValueAnimator.ofFloat(0f, 1f)
        v.duration = total
        v.interpolator = LinearInterpolator()
        v.addUpdateListener {
            val f = it.animatedValue as Float
            fill.scaleX = f
            loading.text = "Загрузка…  ${(f * 100).toInt()}%"
        }
        v.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                finishBoot()
            }
        })
        loader = v
        v.start()
    }

    private fun finishBoot() {
        pulses.forEach { it.cancel() }
        pulses.clear()
        center.animate().alpha(0f).setDuration(600).start()
        barBox.animate().alpha(0f).setDuration(600).withEndAction {
            h.postDelayed({ showWelcome() }, 800)
        }.start()
    }

    private fun showWelcome() {
        boot.animate().alpha(0f).setDuration(700).withEndAction { boot.visibility = View.GONE }.start()
        welcomeBox.scaleX = 0.92f
        welcomeBox.scaleY = 0.92f
        welcomeBox.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(900).setStartDelay(300).start()
        h.postDelayed({
            animate().alpha(0f).setDuration(700).withEndAction { onDone() }.start()
        }, 3700)
    }

    fun cancel() {
        h.removeCallbacksAndMessages(null)
        loader?.removeAllListeners()
        loader?.cancel()
        pulses.forEach { it.cancel() }
    }
}
