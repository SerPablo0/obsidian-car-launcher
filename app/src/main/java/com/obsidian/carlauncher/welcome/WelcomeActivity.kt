package com.obsidian.carlauncher.welcome

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
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
 *
 * It leaves like a luxury car's display: every line takes on the accent colour, collapses into a
 * thin light that is brighter at the end it is about to travel to, and the lights shoot off to
 * alternate sides as the ring dissolves. The screen then carries on with whatever was open.
 */
class WelcomeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val animators = mutableListOf<Animator>()
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
        animateIn(textLines())
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

    /** Greeting, date, status lines and tagline, top to bottom. */
    private fun textLines(): List<TextView> {
        val lines = findViewById<LinearLayout>(R.id.welcome_lines)
        return listOf<TextView>(findViewById(R.id.welcome_greeting), findViewById(R.id.welcome_date)) +
            (0 until lines.childCount).map { lines.getChildAt(it) as TextView } +
            findViewById<TextView>(R.id.welcome_tagline)
    }

    private fun animateIn(steps: List<View>) {
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

    /** A tap skips with a quick fade; at the end of its time the welcome plays its exit. */
    private fun leave(fast: Boolean) {
        if (leaving) return
        leaving = true
        handler.removeCallbacksAndMessages(null)
        if (fast) {
            findViewById<View>(R.id.welcome_content).animate().alpha(0f)
                .setStartDelay(0).setDuration(FADE_FAST_MS).withLayer()
                .withEndAction(::close)
                .start()
        } else {
            playExit()
        }
    }

    private fun playExit() {
        val root = findViewById<FrameLayout>(R.id.welcome_root)
        val accent = Accent.color(this)

        // The ring opens up and dissolves while the lines leave.
        val emblem = findViewById<View>(R.id.welcome_emblem)
        emblem.animate().scaleX(EMBLEM_EXIT_SCALE).scaleY(EMBLEM_EXIT_SCALE).alpha(0f)
            .setStartDelay(EMBLEM_EXIT_DELAY_MS).setDuration(EMBLEM_EXIT_MS).setInterpolator(DecelerateInterpolator()).withLayer().start()
        val pulse = findViewById<View>(R.id.welcome_pulse)
        pulse.scaleX = 1f
        pulse.scaleY = 1f
        pulse.alpha = 0.9f
        pulse.animate().scaleX(PULSE_EXIT_SCALE).scaleY(PULSE_EXIT_SCALE).alpha(0f)
            .setStartDelay(EMBLEM_EXIT_DELAY_MS).setDuration(PULSE_EXIT_MS).setInterpolator(DecelerateInterpolator()).withLayer().start()

        val lines = textLines()
        lines.forEachIndexed { index, line ->
            val toLeft = index % 2 == 0
            val light = lightFor(root, line, accent, toLeft)
            AnimatorSet().apply {
                playSequentially(recolour(line, accent), collapse(line, light), depart(root, light, toLeft))
                startDelay = index * LINE_STAGGER_MS
                animators += this
                start()
            }
        }
        val lastLineEnds = (lines.size - 1) * LINE_STAGGER_MS + RECOLOUR_MS + COLLAPSE_MS + DEPART_MS
        handler.postDelayed(::close, maxOf(lastLineEnds, EMBLEM_EXIT_DELAY_MS + PULSE_EXIT_MS))
    }

    /** The text takes on the accent colour; a status line's box fades away around it. */
    private fun recolour(line: TextView, accent: Int): Animator {
        val box = line.background?.mutate()
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RECOLOUR_MS
            val evaluator = ArgbEvaluator()
            val from = line.currentTextColor
            addUpdateListener {
                val progress = it.animatedValue as Float
                line.setTextColor(evaluator.evaluate(progress, from, accent) as Int)
                box?.alpha = ((1 - progress) * 255).toInt()
            }
        }
    }

    /** The line of text flattens into its light. */
    private fun collapse(line: View, light: View) = AnimatorSet().apply {
        duration = COLLAPSE_MS
        interpolator = DecelerateInterpolator()
        playTogether(
            ObjectAnimator.ofFloat(line, View.SCALE_Y, COLLAPSED_SCALE),
            ObjectAnimator.ofFloat(line, View.ALPHA, 0f),
            ObjectAnimator.ofFloat(light, View.ALPHA, 0f, 1f),
            ObjectAnimator.ofFloat(light, View.SCALE_X, LIGHT_START_SCALE, 1f),
        )
    }

    /** The light speeds off its side of the screen, stretching into a trail behind its bright end. */
    private fun depart(root: View, light: View, toLeft: Boolean): Animator {
        // Far enough that even the stretched trail is past the edge.
        val distance = root.width + light.layoutParams.width * TRAIL_SCALE
        return AnimatorSet().apply {
            interpolator = AccelerateInterpolator(DEPART_ACCELERATION)
            playTogether(
                ObjectAnimator.ofFloat(light, View.TRANSLATION_X, if (toLeft) -distance else distance).setDuration(DEPART_MS),
                ObjectAnimator.ofFloat(light, View.SCALE_X, 1f, TRAIL_SCALE).setDuration(DEPART_MS),
                ObjectAnimator.ofFloat(light, View.ALPHA, 1f, 0f).apply {
                    startDelay = DEPART_MS - TRAIL_FADE_MS
                    duration = TRAIL_FADE_MS
                },
            )
            addListener(object : AnimatorListenerAdapter() {
                // Stretching from the bright end makes the trail grow behind it.
                override fun onAnimationStart(animation: Animator) {
                    light.pivotX = if (toLeft) 0f else light.width.toFloat()
                }
            })
        }
    }

    /**
     * A thin light in the accent colour, as wide as the line's text and centred on it, with a
     * soft halo. It is brightest at the end it will travel towards.
     */
    private fun lightFor(root: FrameLayout, line: TextView, accent: Int, toLeft: Boolean): View {
        val density = resources.displayMetrics.density
        val width = textWidth(line).toInt().coerceAtLeast(1)
        val haloHeight = (HALO_DP * density).toInt()
        val coreHeight = (CORE_DP * density).toInt()
        val light = FrameLayout(this).apply {
            alpha = 0f
            addView(View(context).apply { background = streak(accent, toLeft, HALO_ALPHA, haloHeight / 2f) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(View(context).apply { background = streak(accent, toLeft, 1f, coreHeight / 2f) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, coreHeight, Gravity.CENTER_VERTICAL))
        }
        root.addView(light, FrameLayout.LayoutParams(width, haloHeight))
        val lineAt = IntArray(2).also(line::getLocationInWindow)
        val rootAt = IntArray(2).also(root::getLocationInWindow)
        light.x = lineAt[0] - rootAt[0] + line.width / 2f - width / 2f
        light.y = lineAt[1] - rootAt[1] + line.height / 2f - haloHeight / 2f
        return light
    }

    private fun streak(color: Int, toLeft: Boolean, alpha: Float, radius: Float) = GradientDrawable(
        if (toLeft) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.RIGHT_LEFT,
        intArrayOf(withAlpha(color, alpha), withAlpha(color, alpha * TAIL_ALPHA)),
    ).apply { cornerRadius = radius }

    private fun withAlpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)

    /** The widest line of the text as drawn, not the view (a status line has padding around it). */
    private fun textWidth(line: TextView): Float {
        val layout = line.layout ?: return line.width.toFloat()
        return (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }.coerceAtMost(line.width.toFloat())
    }

    private fun close() {
        if (isFinishing) return
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
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
        const val SHOW_MS = 3_700L
        const val FADE_FAST_MS = 150L

        // The exit, per line: colour, then collapse into a light, then departure.
        const val RECOLOUR_MS = 320L
        const val COLLAPSE_MS = 220L
        const val DEPART_MS = 620L
        const val LINE_STAGGER_MS = 80L
        const val COLLAPSED_SCALE = 0.08f
        const val LIGHT_START_SCALE = 0.6f
        const val TRAIL_SCALE = 2.8f
        const val TRAIL_FADE_MS = 260L
        const val DEPART_ACCELERATION = 1.7f
        const val HALO_DP = 12
        const val CORE_DP = 3
        const val HALO_ALPHA = 0.28f
        const val TAIL_ALPHA = 0.25f
        const val EMBLEM_EXIT_DELAY_MS = 300L
        const val EMBLEM_EXIT_MS = 700L
        const val EMBLEM_EXIT_SCALE = 1.35f
        const val PULSE_EXIT_MS = 900L
        const val PULSE_EXIT_SCALE = 3.2f
    }
}
