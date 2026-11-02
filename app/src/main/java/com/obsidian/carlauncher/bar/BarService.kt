package com.obsidian.carlauncher.bar

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.obsidian.carlauncher.Accent
import com.obsidian.carlauncher.R
import com.obsidian.carlauncher.drive.TripComputer
import com.obsidian.carlauncher.media.NwdMedia
import com.obsidian.carlauncher.system.AutoStart

/**
 * Hosts the Obsidian bottom bar and performs Back / Home / Recents.
 *
 * Living inside an accessibility service gives us three things at once: the system
 * starts it at boot and keeps it alive, it may perform global navigation actions, and
 * it can add windows. The window uses NWD's custom TYPE_INSTRUMENT_PANEL so the
 * firmware reserves the bottom 140 px for it exactly like it does for the stock bar.
 */
class BarService : AccessibilityService() {

    private var barView: View? = null
    private var volumePopup: VolumePopup? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        attachBar()
        volumePopup = VolumePopup(this).also { it.start() }
        // Starts listening to the car's own media players from boot, before the home screen appears.
        NwdMedia.ensureRegistered(this)
        // Likewise the trip computer, so a drive is measured from the start whatever app is open.
        TripComputer.start(this)
        // This service lives as long as the system runs, so it is where car on/off is noticed.
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> AutoStart.onScreenOff()
                Intent.ACTION_SCREEN_ON -> AutoStart.onScreenOn(context)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        instance = null
        volumePopup?.stop()
        volumePopup = null
        detachBar()
        super.onDestroy()
    }

    /** Re-runs window placement, e.g. right after the stock bar released the reserved strip. Main thread only. */
    fun reattach() {
        detachBar()
        attachBar()
    }

    /** Removes the bar window without stopping the service. Main thread only. */
    fun detach() = detachBar()

    private fun attachBar() {
        if (barView != null) return
        val windowManager = getSystemService(WindowManager::class.java)
        val inflater = LayoutInflater.from(ContextThemeWrapper(this, Accent.current(this).theme))
        val errors = mutableListOf<String>()

        // Reserved type first; plain accessibility overlay if this firmware refuses it.
        for ((type, mode) in listOf(TYPE_INSTRUMENT_PANEL to BarState.MODE_RESERVED, TYPE_ACCESSIBILITY_OVERLAY to BarState.MODE_OVERLAY)) {
            // A fresh view per attempt: a view from a refused addView must not be reused.
            val view = inflater.inflate(R.layout.view_bar, null)
            bindButtons(view)
            try {
                windowManager.addView(view, layoutParams(type))
                barView = view
                BarState.setAttached(this, mode, errors.joinToString("\n"))
                return
            } catch (e: RuntimeException) {
                errors += "type $type: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        BarState.setAttached(this, BarState.MODE_FAILED, errors.joinToString("\n"))
    }

    private fun detachBar() {
        barView?.let { view ->
            try {
                getSystemService(WindowManager::class.java).removeView(view)
            } catch (e: RuntimeException) {
                // Already gone with the window token.
            }
        }
        barView = null
        BarState.setDetached()
    }

    private fun bindButtons(view: View) {
        view.findViewById<View>(R.id.bar_back).setOnClickListener { performGlobalAction(GLOBAL_ACTION_BACK) }
        view.findViewById<View>(R.id.bar_home).setOnClickListener { performGlobalAction(GLOBAL_ACTION_HOME) }
        view.findViewById<View>(R.id.bar_recents).apply {
            setOnClickListener { performGlobalAction(GLOBAL_ACTION_RECENTS) }
            setOnLongClickListener { performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) }
        }
    }

    private fun layoutParams(type: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        resources.getDimensionPixelSize(R.dimen.bar_height),
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        title = "ObsidianBar"
    }

    companion object {
        /** The running service, if the system has bound it; the bar lives in the launcher's process. */
        @Volatile
        var instance: BarService? = null
            private set

        /** NWD firmware addition (framework WindowManager.LayoutParams, value read from framework.jar). */
        private const val TYPE_INSTRUMENT_PANEL = 2407
        private const val TYPE_ACCESSIBILITY_OVERLAY = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    }
}

/** Shared between the bar service and the launcher, which run in the same process. */
object BarState {
    const val MODE_RESERVED = "reserved"
    const val MODE_OVERLAY = "overlay"
    const val MODE_FAILED = "failed"

    private const val PREFS = "bar"
    private const val KEY_MODE = "mode"
    private const val KEY_ERRORS = "errors"

    @Volatile
    var attached: Boolean = false
        private set

    fun setAttached(context: Context, mode: String, errors: String) {
        attached = mode != MODE_FAILED
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, mode)
            .putString(KEY_ERRORS, errors)
            .apply()
    }

    /** Why the preferred window types were refused on the last attempt, for the settings page. */
    fun lastErrors(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ERRORS, null).orEmpty()

    fun setDetached() {
        attached = false
    }

    fun lastMode(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
}
