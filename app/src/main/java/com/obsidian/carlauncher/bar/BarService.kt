package com.obsidian.carlauncher.bar

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.obsidian.carlauncher.R

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        attachBar()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        detachBar()
        super.onDestroy()
    }

    private fun attachBar() {
        if (barView != null) return
        val windowManager = getSystemService(WindowManager::class.java)
        val view = LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_Obsidian))
            .inflate(R.layout.view_bar, null)
        bindButtons(view)

        // Reserved type first; plain accessibility overlay if this firmware refuses it.
        for ((type, mode) in listOf(TYPE_INSTRUMENT_PANEL to BarState.MODE_RESERVED, TYPE_ACCESSIBILITY_OVERLAY to BarState.MODE_OVERLAY)) {
            try {
                windowManager.addView(view, layoutParams(type))
                barView = view
                BarState.setAttached(this, mode)
                return
            } catch (e: RuntimeException) {
                // Try the next window type.
            }
        }
        BarState.setAttached(this, BarState.MODE_FAILED)
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

    private companion object {
        /** NWD firmware addition (framework WindowManager.LayoutParams, value read from framework.jar). */
        const val TYPE_INSTRUMENT_PANEL = 2407
        const val TYPE_ACCESSIBILITY_OVERLAY = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    }
}

/** Shared between the bar service and the launcher, which run in the same process. */
object BarState {
    const val MODE_RESERVED = "reserved"
    const val MODE_OVERLAY = "overlay"
    const val MODE_FAILED = "failed"

    private const val PREFS = "bar"
    private const val KEY_MODE = "mode"

    @Volatile
    var attached: Boolean = false
        private set

    fun setAttached(context: Context, mode: String) {
        attached = mode != MODE_FAILED
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply()
    }

    fun setDetached() {
        attached = false
    }

    fun lastMode(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
}
