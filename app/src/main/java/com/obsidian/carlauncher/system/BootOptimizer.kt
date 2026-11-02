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

    /**
     * How the four A53 cores pick their speed. Measured on the head unit (All apps scrolling):
     * stock schedutil 10.8% janky frames, performance 2.7% — schedutil ramps up too late on touch.
     */
    enum class CpuMode(val governor: String, val minFreqKhz: Int) {
        /** Firmware default: 408 MHz floor. */
        BALANCED("schedutil", 408_000),
        /** Never drops below 1.2 GHz, so touch starts at speed; still scales up to 1.46 GHz. */
        RESPONSIVE("schedutil", 1_200_000),
        /** Always 1.46 GHz. */
        MAXIMUM("performance", 408_000),
    }

    /** Kernel settings reset at boot, so they are written again each time. */
    fun speedTweaks(context: Context): List<String> {
        val mode = cpuMode(context)
        return listOf(
            // ksmd scans memory nonstop and costs a quarter of the A100's CPU.
            "echo 0 > /sys/kernel/mm/ksm/run",
            // deadline suits eMMC better than cfq.
            "echo deadline > /sys/block/mmcblk0/queue/scheduler",
            "for p in /sys/devices/system/cpu/cpu[0-3]/cpufreq; do " +
                "echo ${mode.governor} > \$p/scaling_governor; echo ${mode.minFreqKhz} > \$p/scaling_min_freq; done",
            // adbd once stopped listening on 5555 and every root action failed until a restart;
            // with the persistent property it always comes back on the port.
            "[ \"\$(getprop persist.adb.tcp.port)\" = 5555 ] || setprop persist.adb.tcp.port 5555",
        )
    }

    fun cpuMode(context: Context): CpuMode =
        prefs(context).getString(KEY_CPU_MODE, null)?.let { saved -> CpuMode.values().firstOrNull { it.name == saved } }
            ?: CpuMode.BALANCED

    fun setCpuMode(context: Context, mode: CpuMode) {
        prefs(context).edit().putString(KEY_CPU_MODE, mode.name).apply()
    }

    private const val PREFS = "boot_optimizer"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CPU_MODE = "cpu_mode"
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
            if (RootShell.runBlocking(appContext, speedTweaks(appContext))?.ok == true) {
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

    /** CPU temperature in °C, or null if the thermal zone is not readable. */
    fun cpuTemperature(): Int? = try {
        File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toInt() / 1000
    } catch (e: Exception) {
        null
    }

    private fun currentBootId(): String? = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    } catch (e: Exception) {
        null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
