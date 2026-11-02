package com.obsidian.carlauncher

import android.app.Activity
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes

/** The fixed row of five shortcuts under the media card. */
object Shortcuts {

    private class Shortcut(
        @IdRes val viewId: Int,
        @StringRes val label: Int,
        @DrawableRes val icon: Int,
        /** Candidate packages, in order of preference. Empty means "open the app drawer". */
        val packages: (Activity) -> List<String>,
    )

    private val shortcuts = listOf(
        Shortcut(R.id.shortcut_maps, R.string.shortcut_maps, R.drawable.ic_maps) { activity ->
            // The NWD firmware stores the user's chosen navigation app in this system setting.
            listOfNotNull(
                Settings.System.getString(activity.contentResolver, "navigation_packagename"),
                "com.google.android.apps.maps",
            )
        },
        Shortcut(R.id.shortcut_zlink, R.string.shortcut_zlink, R.drawable.ic_zlink) {
            listOf("com.zjinnova.zlink")
        },
        Shortcut(R.id.shortcut_phone, R.string.shortcut_phone, R.drawable.ic_phone) {
            listOf("com.nwd.android.phone")
        },
        Shortcut(R.id.shortcut_radio, R.string.shortcut_radio, R.drawable.ic_radio) {
            listOf("com.nwd.radio")
        },
        Shortcut(R.id.shortcut_apps, R.string.shortcut_apps, R.drawable.ic_apps) { emptyList() },
    )

    fun bind(activity: Activity, row: ViewGroup, onAllApps: () -> Unit) {
        for (shortcut in shortcuts) {
            val item = row.findViewById<View>(shortcut.viewId)
            val label = activity.getString(shortcut.label)
            item.findViewById<ImageView>(R.id.icon).setImageResource(shortcut.icon)
            item.findViewById<TextView>(R.id.label).text = label
            item.contentDescription = label
            item.setOnClickListener {
                val candidates = shortcut.packages(activity)
                if (candidates.isEmpty()) {
                    onAllApps()
                } else if (candidates.none { AppLauncher.startPackage(activity, it) }) {
                    Toast.makeText(activity, activity.getString(R.string.app_not_installed, label), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
