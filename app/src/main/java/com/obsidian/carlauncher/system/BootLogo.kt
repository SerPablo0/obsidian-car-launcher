package com.obsidian.carlauncher.system

import android.content.Context
import java.io.File

/**
 * Swaps the bootloader splash (/data/bootloader/bootlogo.bmp, a 1024x768 32-bit BMP on
 * the vfat "bootloader" partition) with the Obsidian one, keeping the NWD original next to it.
 * The asset is made by tools/make_bootlogo.py in the same format as the original.
 */
object BootLogo {

    private const val DIR = "/data/bootloader"
    private const val LOGO = "$DIR/bootlogo.bmp"
    private const val ORIGINAL = "$DIR/bootlogo_nwd.bmp"
    private const val TEMP = "$DIR/.bootlogo_new.bmp"
    private const val ASSET = "bootlogo.bmp"

    private const val PREFS = "boot_logo"
    private const val KEY_APPLIED = "applied"

    /** The app cannot read /data/bootloader itself, so it remembers what it last wrote. */
    fun isApplied(context: Context) = prefs(context).getBoolean(KEY_APPLIED, false)

    fun setApplied(context: Context, applied: Boolean, onDone: (Boolean) -> Unit) {
        val commands = if (applied) {
            val source = File(context.cacheDir, ASSET)
            context.assets.open(ASSET).use { input -> source.outputStream().use { input.copyTo(it) } }
            listOf(
                // Keep the NWD logo once; never overwrite that backup.
                "[ -f $ORIGINAL ] || cp $LOGO $ORIGINAL",
                // Write beside the target and rename, so a power cut never leaves half a logo.
                "cp '${source.absolutePath}' $TEMP && mv $TEMP $LOGO && sync",
                "cmp '${source.absolutePath}' $LOGO",
            )
        } else {
            listOf("[ -f $ORIGINAL ] && cp $ORIGINAL $TEMP && mv $TEMP $LOGO && sync", "cmp $ORIGINAL $LOGO")
        }
        RootShell.runAsync(context, commands) { result ->
            val ok = result?.ok == true
            if (ok) prefs(context).edit().putBoolean(KEY_APPLIED, applied).apply()
            File(context.cacheDir, ASSET).delete()
            onDone(ok)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
