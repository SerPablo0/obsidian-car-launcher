package com.obsidian.carlauncher.bar

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.obsidian.carlauncher.system.RootShell
import com.obsidian.carlauncher.system.UnusedApps

/** Turns the Obsidian bar on and off and swaps it with the stock NWD bar safely. */
object BarControl {

    const val NWD_BAR_PACKAGE = "com.nwd.statusbarbottom"

    private fun ourService(context: Context) = ComponentName(context, BarService::class.java)

    private fun enabledServices(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    fun isServiceEnabled(context: Context): Boolean {
        val ours = ourService(context)
        return enabledServices(context).any { ComponentName.unflattenFromString(it) == ours }
    }

    fun setServiceEnabled(context: Context, enabled: Boolean, onDone: (Boolean) -> Unit) {
        val ours = ourService(context)
        val others = enabledServices(context).filter { ComponentName.unflattenFromString(it) != ours }
        val services = if (enabled) others + ours.flattenToString() else others
        val commands = buildList {
            if (enabled) add("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            if (services.isEmpty()) {
                add("settings delete secure enabled_accessibility_services")
            } else {
                add("settings put secure enabled_accessibility_services '${services.joinToString(":")}'")
            }
            add("settings put secure accessibility_enabled ${if (services.isEmpty()) 0 else 1}")
        }
        // The stock bar must never stay hidden without ours.
        if (!enabled && isNwdBarHidden(context)) setNwdBarHidden(context, hidden = false) {}
        RootShell.runAsync(context, commands) { onDone(it?.ok == true) }
    }

    fun isNwdBarHidden(context: Context): Boolean =
        UnusedApps.isInstalled(context, NWD_BAR_PACKAGE) && !UnusedApps.isEnabled(context, NWD_BAR_PACKAGE)

    /**
     * The firmware allows a single window of the reserved type, so the two bars swap places:
     * hiding the stock bar frees the strip and ours moves in; if ours cannot take it,
     * the stock bar is restored and [onDone] gets false.
     */
    fun setNwdBarHidden(context: Context, hidden: Boolean, onDone: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        if (hidden) {
            RootShell.runAsync(appContext, listOf("pm disable-user --user 0 $NWD_BAR_PACKAGE")) { result ->
                if (result?.ok != true) return@runAsync onDone(false)
                // Give the window manager a moment to drop the stock bar's window.
                mainHandler.postDelayed({
                    BarService.instance?.reattach()
                    if (isReservedBarShowing(appContext)) {
                        onDone(true)
                    } else {
                        setNwdBarHidden(appContext, hidden = false) { onDone(false) }
                    }
                }, SWAP_SETTLE_MS)
            }
        } else {
            // Leave the strip free first, or the stock bar cannot add its window.
            BarService.instance?.detach()
            val commands = listOf(
                "pm enable $NWD_BAR_PACKAGE",
                // A persistent app is not restarted by enable alone; its own receiver brings the bar back.
                "am broadcast -a com.nwd.statusbarbottom.start -n '$NWD_BAR_PACKAGE/.FloatBar\$FloatBarReceiver'",
            )
            RootShell.runAsync(appContext, commands) { result ->
                // Ours comes back as an overlay once the stock bar holds the strip again.
                mainHandler.postDelayed({
                    BarService.instance?.reattach()
                    onDone(result?.ok == true)
                }, STOCK_BAR_START_MS)
            }
        }
    }

    /** Safety net, run from the home screen: brings the stock bar back if ours is not showing. */
    fun guard(context: Context) {
        if (isNwdBarHidden(context) && !isReservedBarShowing(context)) setNwdBarHidden(context, hidden = false) {}
    }

    /** Only a bar in the reserved strip can replace the stock one; an overlay would cover app content. */
    fun isReservedBarShowing(context: Context): Boolean =
        BarState.attached && BarState.lastMode(context) == BarState.MODE_RESERVED

    /** True when the firmware accepted our window type and only refused it because the stock bar holds the strip. */
    fun isStripHeldByStockBar(context: Context): Boolean =
        BarState.lastMode(context) == BarState.MODE_OVERLAY && BarState.lastErrors(context).contains(STRIP_TAKEN)

    private val mainHandler = Handler(Looper.getMainLooper())
    private const val SWAP_SETTLE_MS = 700L
    private const val STOCK_BAR_START_MS = 3_000L
    private const val STRIP_TAKEN = "already exists"
}
