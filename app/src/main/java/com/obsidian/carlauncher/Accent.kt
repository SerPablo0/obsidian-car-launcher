package com.obsidian.carlauncher

import android.content.Context
import android.util.TypedValue
import androidx.annotation.StringRes
import androidx.annotation.StyleRes

/** The user's accent colour, applied by choosing one of the Theme.Obsidian variants. */
enum class Accent(@StyleRes val theme: Int, @StringRes val label: Int) {
    MINT(R.style.Theme_Obsidian, R.string.accent_mint),
    BLUE(R.style.Theme_Obsidian_Blue, R.string.accent_blue),
    AMBER(R.style.Theme_Obsidian_Amber, R.string.accent_amber),
    VIOLET(R.style.Theme_Obsidian_Violet, R.string.accent_violet),
    WHITE(R.style.Theme_Obsidian_White, R.string.accent_white);

    companion object {
        private const val PREFS = "look"
        private const val KEY_ACCENT = "accent"

        fun current(context: Context): Accent =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACCENT, null)
                ?.let { saved -> values().firstOrNull { it.name == saved } } ?: MINT

        fun set(context: Context, accent: Accent) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ACCENT, accent.name).apply()
        }

        /** The accent colour of whatever theme [context] carries. */
        fun color(context: Context): Int {
            val value = TypedValue()
            context.theme.resolveAttribute(R.attr.obsAccent, value, true)
            return value.data
        }
    }
}
