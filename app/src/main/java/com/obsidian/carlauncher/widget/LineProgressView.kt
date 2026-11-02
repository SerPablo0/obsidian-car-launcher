package com.obsidian.carlauncher.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.obsidian.carlauncher.Accent
import com.obsidian.carlauncher.R

/** A thin rounded track with a filled portion. Cheaper than a styled ProgressBar. */
class LineProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.track)
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Accent.color(context)
        strokeCap = Paint.Cap.ROUND
    }

    /** 0..1 */
    var fraction: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val stroke = height.toFloat()
        trackPaint.strokeWidth = stroke
        fillPaint.strokeWidth = stroke
        val y = stroke / 2
        val start = paddingStart + y
        val end = width - paddingEnd - y
        canvas.drawLine(start, y, end, y, trackPaint)
        if (fraction > 0f) canvas.drawLine(start, y, start + (end - start) * fraction, y, fillPaint)
    }
}
