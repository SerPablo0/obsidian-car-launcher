package com.obsidian.carlauncher.drive

import android.content.Context
import android.text.TextUtils
import android.text.format.DateUtils
import android.widget.TextView
import com.obsidian.carlauncher.R
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** How trip numbers are written on every screen. */
object TripFormat {

    fun distance(context: Context, meters: Double): String {
        val km = NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(meters / 1000)
        return context.getString(R.string.trip_km, km)
    }

    fun duration(context: Context, ms: Long): String {
        val minutes = (ms / 60_000).toInt()
        return if (minutes >= 60) {
            context.getString(R.string.trip_hours_minutes, minutes / 60, minutes % 60)
        } else {
            context.getString(R.string.trip_minutes, minutes)
        }
    }

    /** Null (no average yet, no top speed yet) is shown as a dash. */
    fun speed(context: Context, kmh: Int?): String =
        if (kmh == null) context.getString(R.string.trip_none) else context.getString(R.string.trip_kmh, kmh)

    fun startTime(context: Context, at: Long): String {
        if (at <= 0) return context.getString(R.string.trip_none)
        val time = android.text.format.DateFormat.getTimeFormat(context).format(Date(at))
        return if (DateUtils.isToday(at)) time else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(at)) + " " + time
    }
}

/** These readings refresh every second; setting the same text again would still re-layout the screen. */
internal fun TextView.setTextIfChanged(value: CharSequence) {
    if (!TextUtils.equals(text, value)) text = value
}
