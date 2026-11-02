package com.obsidian.carlauncher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import com.obsidian.carlauncher.apps.AppEntry
import com.obsidian.carlauncher.apps.AppPicker

/**
 * The row of five shortcuts under the media card. The first four can be changed
 * with a long press; the last one always opens the app drawer.
 */
class Shortcuts(
    private val activity: Activity,
    private val row: ViewGroup,
    /** Apps already loaded by the drawer, sorted by label. */
    private val apps: () -> List<AppEntry>,
    private val onAllApps: () -> Unit,
) {

    private class Default(
        @IdRes val viewId: Int,
        @StringRes val label: Int,
        @DrawableRes val icon: Int,
        /** Candidate packages, in order of preference. */
        val packages: (Context) -> List<String>,
    )

    private val defaults = listOf(
        Default(R.id.shortcut_maps, R.string.shortcut_maps, R.drawable.ic_maps) { context ->
            // The NWD firmware stores the user's chosen navigation app in this system setting.
            listOfNotNull(
                Settings.System.getString(context.contentResolver, "navigation_packagename"),
                "com.google.android.apps.maps",
            )
        },
        Default(R.id.shortcut_zlink, R.string.shortcut_zlink, R.drawable.ic_zlink) { listOf("com.zjinnova.zlink") },
        Default(R.id.shortcut_phone, R.string.shortcut_phone, R.drawable.ic_phone) { listOf("com.nwd.android.phone") },
        Default(R.id.shortcut_radio, R.string.shortcut_radio, R.drawable.ic_radio) { listOf("com.nwd.radio") },
    )

    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bind() {
        defaults.forEachIndexed { slot, default -> bindSlot(slot, default) }

        val appsItem = row.findViewById<View>(R.id.shortcut_apps)
        val appsLabel = activity.getString(R.string.shortcut_apps)
        appsItem.findViewById<ImageView>(R.id.icon).setImageResource(R.drawable.ic_apps)
        appsItem.findViewById<TextView>(R.id.label).text = appsLabel
        appsItem.contentDescription = appsLabel
        appsItem.setOnClickListener { onAllApps() }
    }

    private fun bindSlot(slot: Int, default: Default) {
        val item = row.findViewById<View>(default.viewId)
        val icon = item.findViewById<ImageView>(R.id.icon)
        val labelView = item.findViewById<TextView>(R.id.label)
        val custom = customApp(slot)

        val label: String
        if (custom != null) {
            label = custom.label
            // Full-colour app icons are shown as they are.
            icon.imageTintList = null
            icon.setImageDrawable(custom.icon)
            item.setOnClickListener { AppLauncher.startComponent(activity, custom.component) }
        } else {
            label = activity.getString(default.label)
            icon.imageTintList = ColorStateList.valueOf(activity.getColor(R.color.text_primary))
            icon.setImageResource(default.icon)
            item.setOnClickListener {
                if (default.packages(activity).none { AppLauncher.startPackage(activity, it) }) {
                    Toast.makeText(activity, activity.getString(R.string.app_not_installed, label), Toast.LENGTH_SHORT).show()
                }
            }
        }
        labelView.text = label
        item.contentDescription = label
        item.setOnLongClickListener {
            showPicker(slot, default)
            true
        }
    }

    /** The app saved for this slot, if it is still installed. */
    private fun customApp(slot: Int): AppEntry? {
        val component = prefs.getString(key(slot), null)?.let(ComponentName::unflattenFromString) ?: return null
        return apps().firstOrNull { it.component == component }
    }

    private fun showPicker(slot: Int, default: Default) {
        val resetLabel = activity.getString(R.string.shortcut_reset, activity.getString(default.label))
        AppPicker.show(activity, R.string.shortcut_pick_title, resetLabel, apps()) { picked ->
            prefs.edit().apply {
                if (picked == null) remove(key(slot)) else putString(key(slot), picked.component.flattenToString())
            }.apply()
            bindSlot(slot, default)
        }
    }

    private companion object {
        const val PREFS = "shortcuts"
        fun key(slot: Int) = "slot_$slot"
    }
}
