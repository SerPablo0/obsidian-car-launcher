package com.obsidian.carlauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView

/**
 * After a while on the home screen without a touch, covers it with a large, static clock.
 * Any touch brings the home screen back. Only runs while the home screen is in front.
 */
class IdleClock(
    private val overlay: View,
    /** Current "title · artist" line, or null when nothing is playing. */
    private val nowPlaying: () -> String?,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val showRunnable = Runnable { show() }
    private var active = false

    init {
        overlay.setOnClickListener {
            hide()
            restartTimer()
        }
    }

    /** Home screen came to the front. */
    fun resume() {
        active = true
        restartTimer()
    }

    /** Home screen left the front. */
    fun pause() {
        active = false
        handler.removeCallbacks(showRunnable)
        overlay.visibility = View.GONE
    }

    /** Any touch on the home screen. */
    fun onUserInteraction() {
        if (overlay.visibility != View.VISIBLE) restartTimer()
    }

    private fun restartTimer() {
        handler.removeCallbacks(showRunnable)
        if (active && isEnabled(overlay.context)) handler.postDelayed(showRunnable, IDLE_MS)
    }

    private fun show() {
        val track = nowPlaying()
        overlay.findViewById<View>(R.id.idle_now_playing).visibility = if (track == null) View.GONE else View.VISIBLE
        overlay.findViewById<TextView>(R.id.idle_track).text = track
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(1f).setDuration(FADE_MS).withLayer().start()
    }

    private fun hide() {
        overlay.animate().cancel()
        overlay.visibility = View.GONE
    }

    companion object {
        private const val IDLE_MS = 2 * 60_000L
        private const val FADE_MS = 400L
        private const val PREFS = "idle_clock"
        private const val KEY_ENABLED = "enabled"

        fun isEnabled(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }
}
