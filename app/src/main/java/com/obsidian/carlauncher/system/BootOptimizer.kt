package com.obsidian.carlauncher.system

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import com.obsidian.carlauncher.R
import java.io.File
import java.util.concurrent.Executors

/**
 * Re-applies the runtime tweaks that the kernel forgets on every boot, and
 * sets a plain wallpaper once so split-screen and recents stay cheap to draw.
 */
object BootOptimizer {

    /** Kernel settings reset at boot, so they are written again each time. */
    val SPEED_TWEAKS = listOf(
        // ksmd scans memory nonstop and costs a quarter of the A100's CPU.
        "echo 0 > /sys/kernel/mm/ksm/run",
        // deadline suits eMMC better than cfq.
        "echo deadline > /sys/block/mmcblk0/queue/scheduler",
    )

    private const val PREFS = "boot_optimizer"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_BOOT = "last_boot_id"
    private const val KEY_WALLPAPER_DONE = "wallpaper_done"

    private val worker = Executors.newSingleThreadExecutor()

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Called from the home screen's onCreate; does real work at most once per boot. */
    fun runIfNeeded(context: Context) {
        val appContext = context.applicationContext
        worker.execute {
            setPlainWallpaperOnce(appContext)
            if (!isEnabled(appContext)) return@execute
            val bootId = currentBootId() ?: return@execute
            if (prefs(appContext).getString(KEY_LAST_BOOT, null) == bootId) return@execute
            if (RootShell.runBlocking(appContext, SPEED_TWEAKS)?.ok == true) {
                prefs(appContext).edit().putString(KEY_LAST_BOOT, bootId).apply()
            }
        }
    }

    private fun setPlainWallpaperOnce(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_WALLPAPER_DONE, false)) return
        try {
            val metrics = context.resources.displayMetrics
            val bitmap = Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, Bitmap.Config.RGB_565)
            bitmap.eraseColor(context.getColor(R.color.bg))
            WallpaperManager.getInstance(context)
                .setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            bitmap.recycle()
            prefs.edit().putBoolean(KEY_WALLPAPER_DONE, true).apply()
        } catch (e: Exception) {
            // Retried on the next start.
        }
    }

    private fun currentBootId(): String? = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    } catch (e: Exception) {
        null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
