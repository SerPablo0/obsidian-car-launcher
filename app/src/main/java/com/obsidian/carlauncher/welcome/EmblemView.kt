package com.obsidian.carlauncher.welcome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.obsidian.carlauncher.Accent
import com.obsidian.carlauncher.R
import kotlin.math.min

/**
 * The Obsidian mark (an accent ring around a white dot, as in the app icon), drawn so the
 * welcome screen can sweep the ring in and then pop the dot.
 */
class EmblemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Accent.color(context)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_primary)
    }
    private val bounds = RectF()

    /** How much of the ring is drawn, 0..1. */
    var ring: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** Size of the centre dot, 0..1 (may overshoot a little while it pops). */
    var dot: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        // Same proportions as the launcher icon: ring 44 wide with a 5 stroke, dot 16 wide.
        val stroke = size * 5 / 49
        ringPaint.strokeWidth = stroke
        val left = (width - size) / 2 + stroke / 2
        val top = (height - size) / 2 + stroke / 2
        bounds.set(left, top, left + size - stroke, top + size - stroke)
        if (ring > 0f) canvas.drawArc(bounds, -90f, 360f * ring, false, ringPaint)
        if (dot > 0f) canvas.drawCircle(width / 2f, height / 2f, size * 8 / 49 * dot, dotPaint)
    }
}
