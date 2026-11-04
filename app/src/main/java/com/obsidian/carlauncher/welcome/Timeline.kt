package com.obsidian.carlauncher.welcome

import android.animation.TimeInterpolator
import android.os.SystemClock
import android.view.Choreographer

/**
 * Runs the welcome's animations on its own clock, one frame callback for all of them.
 * The head unit ships with Android's animator duration scale at 0, which makes every
 * ValueAnimator and ViewPropertyAnimator jump straight to its end; this does not read it.
 * Main thread only.
 */
class Timeline {

    private class Track(
        val start: Long,
        val duration: Long,
        val interpolator: TimeInterpolator,
        val update: (Float) -> Unit,
    )

    private val tracks = mutableListOf<Track>()
    private val choreographer = Choreographer.getInstance()
    private var origin = -1L
    private var scheduled = false

    private val frame = Choreographer.FrameCallback {
        scheduled = false
        tick()
    }

    /** Animates from [delay] ms after now for [duration] ms; [update] gets the interpolated 0..1. */
    fun add(delay: Long, duration: Long, interpolator: TimeInterpolator, update: (Float) -> Unit) {
        if (origin < 0) origin = SystemClock.uptimeMillis()
        tracks += Track(elapsed() + delay, duration.coerceAtLeast(1), interpolator, update)
        schedule()
    }

    fun cancel() {
        tracks.clear()
        choreographer.removeFrameCallback(frame)
        scheduled = false
    }

    private fun elapsed() = SystemClock.uptimeMillis() - origin

    private fun tick() {
        val now = elapsed()
        val finished = mutableListOf<Track>()
        for (track in tracks.toList()) {
            if (now < track.start) continue
            val fraction = ((now - track.start).toFloat() / track.duration).coerceAtMost(1f)
            track.update(track.interpolator.getInterpolation(fraction))
            if (fraction >= 1f) finished += track
        }
        tracks -= finished.toSet()
        if (tracks.isNotEmpty()) schedule()
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        choreographer.postFrameCallback(frame)
    }
}
