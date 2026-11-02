package com.obsidian.carlauncher.bar

import android.content.Context
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.obsidian.carlauncher.Accent
import com.obsidian.carlauncher.R
import com.obsidian.carlauncher.system.RootShell
import com.obsidian.carlauncher.widget.LineProgressView

/**
 * Obsidian replacement for the stock volume popup.
 *
 * The knob goes to the MCU; NWD's kernel service then writes the level to
 * Settings.System "mcu_system_volume" (0..mcu_max_volume). Watching that value is
 * all we need: the volume itself keeps working exactly as before.
 */
class VolumePopup(private val context: Context) {

    private val resolver = context.contentResolver
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null

    private val hideRunnable = Runnable { hide() }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = onVolumeChanged()
    }

    fun start() {
        resolver.registerContentObserver(Settings.System.getUriFor(KEY_VOLUME), false, observer)
    }

    fun stop() {
        resolver.unregisterContentObserver(observer)
        handler.removeCallbacks(hideRunnable)
        removeView()
    }

    private fun onVolumeChanged() {
        if (!isEnabled(context)) return
        val level = Settings.System.getInt(resolver, KEY_VOLUME, -1)
        if (level < 0) return
        val max = Settings.System.getInt(resolver, KEY_MAX_VOLUME, DEFAULT_MAX_VOLUME).coerceAtLeast(1)
        show(level, max)
    }

    private fun show(level: Int, max: Int) {
        val popup = view ?: addView() ?: return
        popup.findViewById<TextView>(R.id.volume_level).text = level.toString()
        popup.findViewById<LineProgressView>(R.id.volume_progress).fraction = level.toFloat() / max
        popup.findViewById<ImageView>(R.id.volume_icon)
            .setImageResource(if (level == 0) R.drawable.ic_volume_off else R.drawable.ic_volume)

        popup.animate().cancel()
        popup.animate().alpha(1f).setDuration(FADE_MS).start()
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, VISIBLE_MS)
    }

    private fun hide() {
        val popup = view ?: return
        popup.animate().alpha(0f).setDuration(FADE_MS).withEndAction { removeView() }.start()
    }

    private fun addView(): View? {
        val popup = LayoutInflater.from(ContextThemeWrapper(context, Accent.current(context).theme))
            .inflate(R.layout.view_volume, null)
        popup.alpha = 0f
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = context.resources.getDimensionPixelSize(R.dimen.volume_popup_top)
            title = "ObsidianVolume"
        }
        return try {
            windowManager.addView(popup, params)
            view = popup
            popup
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun removeView() {
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (e: RuntimeException) {
                // Already removed with the service.
            }
        }
        view = null
    }

    companion object {
        private const val KEY_VOLUME = "mcu_system_volume"
        private const val KEY_MAX_VOLUME = "mcu_max_volume"
        private const val DEFAULT_MAX_VOLUME = 40
        private const val VISIBLE_MS = 2_000L
        private const val FADE_MS = 150L

        /** The stock popup lives in the NWD launcher's FloatBar service ("com.nwd.volumeview.VolumeService"). */
        private const val STOCK_POPUP = "com.android.launcher/com.launcher.FloatBar"

        private const val PREFS = "volume_popup"
        private const val KEY_ENABLED = "enabled"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        /** Swaps the stock popup for ours (or back). The stock one returns once something restarts its service. */
        fun setEnabled(context: Context, enabled: Boolean, onDone: (Boolean) -> Unit) {
            // Tolerates a firmware without the stock popup: ours still works, nothing to switch off.
            val commands = if (enabled) {
                listOf("pm disable $STOCK_POPUP 2>/dev/null", "am stopservice -n $STOCK_POPUP 2>/dev/null", "true")
            } else {
                listOf("pm enable $STOCK_POPUP 2>/dev/null", "true")
            }
            RootShell.runAsync(context, commands) { result ->
                val ok = result?.ok == true
                if (ok) {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
                }
                onDone(ok)
            }
        }
    }
}
