package com.obsidian.carlauncher.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.obsidian.carlauncher.Accent
import com.obsidian.carlauncher.R

/**
 * A thin rounded track with a filled portion. Cheaper than a styled ProgressBar.
 * Given [onSeek] it becomes a seek bar: a thumb appears, and a drag or a tap moves it.
 */
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
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Accent.color(context)
    }
    private val thumbRadius = 7 * resources.displayMetrics.density

    /** 0..1 */
    var fraction: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    /**
     * Thickness of the line; 0 draws it as tall as the view. A seek bar is taller than its line,
     * so a finger finds it easily in a moving car.
     */
    var lineThickness: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** Called with the chosen position when a drag or tap ends; null makes this a plain indicator. */
    var onSeek: ((Float) -> Unit)? = null
        set(value) {
            field = value
            if (value == null) isDragging = false
            invalidate()
        }

    /** While true, the owner should not move [fraction] with playback. */
    var isDragging = false
        private set

    override fun onDraw(canvas: Canvas) {
        val stroke = if (lineThickness > 0f) lineThickness else height.toFloat()
        trackPaint.strokeWidth = stroke
        fillPaint.strokeWidth = stroke
        val y = height / 2f
        val start = paddingStart + edge(stroke)
        val end = width - paddingEnd - edge(stroke)
        canvas.drawLine(start, y, end, y, trackPaint)
        val x = start + (end - start) * fraction
        if (fraction > 0f) canvas.drawLine(start, y, x, y, fillPaint)
        if (onSeek != null) canvas.drawCircle(x, y, if (isDragging) thumbRadius * 1.4f else thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val seek = onSeek ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                // A list or a swipe around it must not take the gesture over.
                parent?.requestDisallowInterceptTouchEvent(true)
                fraction = fractionAt(event.x)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (isDragging) fraction = fractionAt(event.x)
            MotionEvent.ACTION_UP -> if (isDragging) {
                isDragging = false
                fraction = fractionAt(event.x)
                invalidate()
                performClick()
                seek(fraction)
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun fractionAt(x: Float): Float {
        val stroke = if (lineThickness > 0f) lineThickness else height.toFloat()
        val start = paddingStart + edge(stroke)
        val end = width - paddingEnd - edge(stroke)
        return if (end > start) (x - start) / (end - start) else 0f
    }

    /** Room at each end for the round cap, and for the thumb on a seek bar. */
    private fun edge(stroke: Float) = if (onSeek != null) maxOf(stroke / 2, thumbRadius * 1.4f) else stroke / 2
}
