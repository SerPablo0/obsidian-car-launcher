package com.obsidian.carlauncher.welcome

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.obsidian.carlauncher.Accent
import com.obsidian.carlauncher.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short welcome at each car start: the Obsidian ring sweeps in, the dot pops, a ring of
 * light spreads out, then the greeting, the date and the car's status fade in one by one.
 * Afterwards it fades away and the screen carries on with whatever was open.
 */
class WelcomeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val animators = mutableListOf<ValueAnimator>()
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Accent.current(this).theme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        findViewById<TextView>(R.id.welcome_greeting).text = Welcome.greeting(this)
        findViewById<TextView>(R.id.welcome_date).text =
            SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).format(Date())
        val lines = findViewById<LinearLayout>(R.id.welcome_lines)
        for (line in Welcome.statusLines(this)) lines.addView(chip(line, lines))

        findViewById<View>(R.id.welcome_root).setOnClickListener { leave(fast = true) }
        animateIn(lines)
        handler.postDelayed({ leave(fast = false) }, SHOW_MS)
    }

    // Covered by another app or the screen went dark (the engine crank): never come back to it later.
    override fun onStop() {
        super.onStop()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        animators.forEach { it.cancel() }
        super.onDestroy()
    }

    private fun chip(line: Welcome.Line, parent: LinearLayout): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = line.text
            textSize = 18f
            setTextColor(getColor(line.color))
            setBackgroundResource(R.drawable.bg_tile)
            setPadding((24 * density).toInt(), (12 * density).toInt(), (24 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (parent.childCount > 0) topMargin = (12 * density).toInt()
            }
        }
    }

    private fun animateIn(lines: LinearLayout) {
        val emblem = findViewById<EmblemView>(R.id.welcome_emblem)
        animate(0L, RING_MS, DecelerateInterpolator(1.5f)) { emblem.ring = it }
        animate(RING_MS - 150, DOT_MS, OvershootInterpolator(3f)) { emblem.dot = it }

        val pulse = findViewById<View>(R.id.welcome_pulse)
        pulse.scaleX = 1f
        pulse.scaleY = 1f
        pulse.alpha = 0.9f
        pulse.animate().scaleX(PULSE_SCALE).scaleY(PULSE_SCALE).alpha(0f)
            .setStartDelay(RING_MS).setDuration(PULSE_MS).setInterpolator(DecelerateInterpolator()).withLayer().start()

        val rise = 24 * resources.displayMetrics.density
        val steps = listOf<View>(findViewById(R.id.welcome_greeting), findViewById(R.id.welcome_date)) +
            (0 until lines.childCount).map(lines::getChildAt) + findViewById<View>(R.id.welcome_tagline)
        steps.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = rise
            view.animate().alpha(1f).translationY(0f)
                .setStartDelay(TEXT_START_MS + index * TEXT_STAGGER_MS).setDuration(TEXT_MS)
                .setInterpolator(DecelerateInterpolator()).withLayer().start()
        }
    }

    private fun animate(delay: Long, duration: Long, interpolator: android.animation.TimeInterpolator, update: (Float) -> Unit) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { update(it.animatedValue as Float) }
            animators += this
            start()
        }
    }

    private fun leave(fast: Boolean) {
        if (leaving) return
        leaving = true
        handler.removeCallbacksAndMessages(null)
        findViewById<View>(R.id.welcome_content).animate().alpha(0f)
            .setStartDelay(0).setDuration(if (fast) FADE_FAST_MS else FADE_MS).withLayer()
            .withEndAction {
                finish()
                overridePendingTransition(0, android.R.anim.fade_out)
            }
            .start()
    }

    private companion object {
        const val DATE_PATTERN = "EEEE, d MMMM"
        const val RING_MS = 900L
        const val DOT_MS = 450L
        const val PULSE_MS = 1_300L
        const val PULSE_SCALE = 2.6f
        const val TEXT_START_MS = 700L
        const val TEXT_STAGGER_MS = 160L
        const val TEXT_MS = 500L
        const val SHOW_MS = 4_300L
        const val FADE_MS = 450L
        const val FADE_FAST_MS = 150L
    }
}
