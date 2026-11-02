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

    /** [onDone] gets null on success, or what went wrong. */
    fun setApplied(context: Context, applied: Boolean, onDone: (String?) -> Unit) {
        val source = File(context.cacheDir, ASSET)
        val wanted = if (applied) {
            context.assets.open(ASSET).use { input -> source.outputStream().use { input.copyTo(it) } }
            "'${source.absolutePath}'"
        } else {
            ORIGINAL
        }
        val commands = listOfNotNull(
            // Keep the NWD logo once; never overwrite that backup.
            if (applied) "[ -f $ORIGINAL ] || cp $LOGO $ORIGINAL" else "[ -f $ORIGINAL ] || { echo 'no saved original logo'; exit 1; }",
            // Write beside the target and rename, so a power cut never leaves half a logo.
            "cp $wanted $TEMP && mv $TEMP $LOGO && sync",
            // toybox on this firmware has md5sum but not cmp.
            "[ \"\$(md5sum < $wanted)\" = \"\$(md5sum < $LOGO)\" ] || { echo 'logo check failed'; exit 1; }",
        )
        RootShell.runAsync(context, commands) { result ->
            File(context.cacheDir, ASSET).delete()
            val error = when {
                result == null -> "system access is not available"
                !result.ok -> result.output.ifBlank { "exit code ${result.exitCode}" }
                else -> null
            }
            if (error == null) prefs(context).edit().putBoolean(KEY_APPLIED, applied).apply()
            onDone(error)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
