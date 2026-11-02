package com.obsidian.carlauncher.system

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import com.obsidian.carlauncher.R

/**
 * NWD system apps that do nothing on this car (2007 Sportage: no camera,
 * no steering-wheel buttons, no CAN data) but still hold RAM.
 * They are disabled, never removed, so each one comes back with a single tap.
 */
object UnusedApps {

    class Entry(
        val packageName: String,
        @StringRes val title: Int,
        @StringRes val description: Int,
        /** Shown with a warning: only safe once confirmed for this car. */
        val risky: Boolean = false,
    )

    val ENTRIES = listOf(
        Entry("com.nwd.carad.service", R.string.unused_ads, R.string.unused_ads_desc),
        Entry("com.nwd.carstore.service", R.string.unused_store, R.string.unused_store_desc),
        Entry("com.nwd.ivicarModelShop", R.string.unused_model_shop, R.string.unused_model_shop_desc),
        Entry("com.nwd.guidebookskin", R.string.unused_guide, R.string.unused_guide_desc),
        Entry("com.nwd.check.appver", R.string.unused_version_check, R.string.unused_version_check_desc),
        Entry("com.nwd.whole.machine", R.string.unused_factory_test, R.string.unused_factory_test_desc),
        Entry("com.nwd.voice.analyze", R.string.unused_voice, R.string.unused_voice_desc),
        Entry("com.nwd.usb2cvbs", R.string.unused_cvbs, R.string.unused_cvbs_desc),
        Entry("com.nwd.backcar", R.string.unused_camera, R.string.unused_camera_desc),
        Entry("com.nwd.can.setting", R.string.unused_can, R.string.unused_can_desc, risky = true),
        Entry("com.nwd.can.help", R.string.unused_can_help, R.string.unused_can_desc, risky = true),
    )

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun isEnabled(context: Context, packageName: String): Boolean =
        when (context.packageManager.getApplicationEnabledSetting(packageName)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> true
        }

    fun setEnabled(context: Context, packageName: String, enabled: Boolean, onDone: (Boolean) -> Unit) {
        val command = if (enabled) "pm enable $packageName" else "pm disable-user --user 0 $packageName"
        RootShell.runAsync(context, listOf(command)) { result -> onDone(result?.ok == true) }
    }
}
