package com.obsidian.carlauncher.welcome

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import androidx.annotation.ColorRes
import com.obsidian.carlauncher.R
import com.obsidian.carlauncher.drive.TripComputer
import com.obsidian.carlauncher.drive.TripFormat
import com.obsidian.carlauncher.maintenance.Maintenance
import java.util.Calendar

/** The greeting shown for a few seconds each time the car starts. */
object Welcome {

    class Line(val text: String, @ColorRes val color: Int)

    private const val PREFS = "welcome"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_NAME = "name"

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun name(context: Context): String = prefs(context).getString(KEY_NAME, null).orEmpty()

    fun setName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_NAME, name).apply()
    }

    /** Called by [com.obsidian.carlauncher.system.AutoStart] at each car start. */
    fun onCarStart(context: Context) {
        if (isEnabled(context)) show(context)
    }

    /** Also used by the settings page's preview button. */
    fun show(context: Context) {
        val intent = Intent(context, WelcomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeCustomAnimation(context, android.R.anim.fade_in, 0)
        try {
            context.startActivity(intent, options.toBundle())
        } catch (e: RuntimeException) {
            // Never let a greeting get in the way of the car start.
        }
    }

    fun greeting(context: Context): String {
        val period = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 4..11 -> R.string.welcome_morning
            in 12..16 -> R.string.welcome_afternoon
            else -> R.string.welcome_evening
        }
        val name = name(context)
        return if (name.isBlank()) context.getString(period) else context.getString(R.string.welcome_with_name, context.getString(period), name)
    }

    /** The last trip and the state of maintenance, whichever are known. */
    fun statusLines(context: Context): List<Line> {
        val lines = mutableListOf<Line>()
        if (TripComputer.isEnabled(context)) {
            // At a car start the drive that just ended may not have become the last trip yet.
            val current = TripComputer.current
            val last = if (current.hasMoved) current else TripComputer.previous
            if (last != null) {
                lines += Line(
                    context.getString(
                        R.string.welcome_last_trip,
                        TripFormat.distance(context, last.distanceM),
                        TripFormat.duration(context, last.durationMs),
                    ),
                    R.color.text_label,
                )
            }
        }
        val urgent = Maintenance.mostUrgent(context)
        when {
            urgent != null -> lines += Line(Maintenance.reminderText(context, urgent), R.color.warning)
            Maintenance.odometerKm(context) != null -> lines += Line(context.getString(R.string.welcome_maintenance_ok), R.color.text_label)
        }
        return lines
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
