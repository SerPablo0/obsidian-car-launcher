package com.obsidian.carlauncher.welcome

import android.animation.ArgbEvaluator
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
import android.view.animation.LinearInterpolator
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
    private val timeline = Timeline()
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
        timeline.cancel()
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
        timeline.add(0L, RING_MS, DecelerateInterpolator(1.5f)) { emblem.ring = it }
        timeline.add(RING_MS - 150, DOT_MS, OvershootInterpolator(3f)) { emblem.dot = it }

        val pulse = findViewById<View>(R.id.welcome_pulse)
        timeline.add(RING_MS, PULSE_MS, DecelerateInterpolator()) { spread(pulse, it, PULSE_SCALE) }

        val rise = 24 * resources.displayMetrics.density
        steps.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = rise
            timeline.add(TEXT_START_MS + index * TEXT_STAGGER_MS, TEXT_MS, DecelerateInterpolator()) {
                view.alpha = it
                view.translationY = rise * (1 - it)
            }
        }
    }

    /** The ring of light: grows to [scale] times its size while it fades out. */
    private fun spread(pulse: View, progress: Float, scale: Float) {
        val size = 1 + (scale - 1) * progress
        pulse.scaleX = size
        pulse.scaleY = size
        pulse.alpha = PULSE_ALPHA * (1 - progress)
    }

    /** A tap skips with a quick fade; at the end of its time the welcome plays its exit. */
    private fun leave(fast: Boolean) {
        if (leaving) return
        leaving = true
        handler.removeCallbacksAndMessages(null)
        if (fast) {
            val content = findViewById<View>(R.id.welcome_content)
            timeline.add(0L, FADE_FAST_MS, LinearInterpolator()) { content.alpha = 1 - it }
            handler.postDelayed(::close, FADE_FAST_MS)
        } else {
            playExit()
        }
    }

    private fun playExit() {
        val root = findViewById<FrameLayout>(R.id.welcome_root)
        val accent = Accent.color(this)

        // The ring opens up and dissolves while the lines leave.
        val emblem = findViewById<View>(R.id.welcome_emblem)
        timeline.add(EMBLEM_EXIT_DELAY_MS, EMBLEM_EXIT_MS, DecelerateInterpolator()) {
            val size = 1 + (EMBLEM_EXIT_SCALE - 1) * it
            emblem.scaleX = size
            emblem.scaleY = size
            emblem.alpha = 1 - it
        }
        val pulse = findViewById<View>(R.id.welcome_pulse)
        timeline.add(EMBLEM_EXIT_DELAY_MS, PULSE_EXIT_MS, DecelerateInterpolator()) { spread(pulse, it, PULSE_EXIT_SCALE) }

        val lines = textLines()
        lines.forEachIndexed { index, line ->
            val toLeft = index % 2 == 0
            val light = lightFor(root, line, accent, toLeft)
            val start = index * LINE_STAGGER_MS
            recolour(line, accent, start)
            collapse(line, light, start + RECOLOUR_MS)
            depart(root, light, toLeft, start + RECOLOUR_MS + COLLAPSE_MS)
        }
        val lastLineEnds = (lines.size - 1) * LINE_STAGGER_MS + RECOLOUR_MS + COLLAPSE_MS + DEPART_MS
        handler.postDelayed(::close, maxOf(lastLineEnds, EMBLEM_EXIT_DELAY_MS + PULSE_EXIT_MS))
    }

    /** The text takes on the accent colour; a status line's box fades away around it. */
    private fun recolour(line: TextView, accent: Int, start: Long) {
        val box = line.background?.mutate()
        val evaluator = ArgbEvaluator()
        val from = line.currentTextColor
        timeline.add(start, RECOLOUR_MS, LinearInterpolator()) {
            line.setTextColor(evaluator.evaluate(it, from, accent) as Int)
            box?.alpha = ((1 - it) * 255).toInt()
        }
    }

    /** The line of text flattens into its light. */
    private fun collapse(line: View, light: View, start: Long) {
        timeline.add(start, COLLAPSE_MS, DecelerateInterpolator()) {
            line.scaleY = 1 + (COLLAPSED_SCALE - 1) * it
            line.alpha = 1 - it
            light.alpha = it
            light.scaleX = LIGHT_START_SCALE + (1 - LIGHT_START_SCALE) * it
        }
    }

    /** The light speeds off its side of the screen, stretching into a trail behind its bright end. */
    private fun depart(root: View, light: View, toLeft: Boolean, start: Long) {
        // Far enough that even the stretched trail is past the edge.
        val distance = (root.width + light.layoutParams.width * TRAIL_SCALE) * if (toLeft) -1 else 1
        val speeding = AccelerateInterpolator(DEPART_ACCELERATION)
        timeline.add(start, DEPART_MS, speeding) {
            light.translationX = distance * it
            light.scaleX = 1 + (TRAIL_SCALE - 1) * it
        }
        timeline.add(start + DEPART_MS - TRAIL_FADE_MS, TRAIL_FADE_MS, speeding) { light.alpha = 1 - it }
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
        // Growing from the bright end makes the trail stretch out behind it.
        light.pivotX = if (toLeft) 0f else width.toFloat()
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
        const val PULSE_ALPHA = 0.9f
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
