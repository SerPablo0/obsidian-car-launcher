package com.obsidian.carlauncher.drive

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.obsidian.carlauncher.AppLauncher
import com.obsidian.carlauncher.R

/** This trip next to the last one, kept up to date while open. */
object TripDialog {

    private class Row(@StringRes val label: Int, val format: (Activity, TripComputer.Trip) -> String)

    private val rows = listOf(
        Row(R.string.trip_distance) { a, t -> TripFormat.distance(a, t.distanceM) },
        Row(R.string.trip_duration) { a, t -> TripFormat.duration(a, t.durationMs) },
        Row(R.string.trip_average) { a, t -> TripFormat.speed(a, t.averageKmh) },
        Row(R.string.trip_top_speed) { a, t -> TripFormat.speed(a, t.maxKmh.takeIf { it > 0 }) },
        Row(R.string.trip_started) { a, t -> TripFormat.startTime(a, t.startedAt) },
    )

    fun show(activity: Activity) {
        val density = activity.resources.displayMetrics.density
        val table = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }

        fun cell(row: LinearLayout, text: String, @ColorRes color: Int, sizeSp: Float, weight: Float) = TextView(activity).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(activity.getColor(color))
            row.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight))
        }

        fun addRow(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (48 * density).toInt()
            table.addView(this)
        }

        addRow().let { header ->
            cell(header, "", R.color.text_secondary, 15f, LABEL_WEIGHT)
            cell(header, activity.getString(R.string.trip_this), R.color.text_secondary, 15f, 1f)
            cell(header, activity.getString(R.string.trip_last), R.color.text_secondary, 15f, 1f)
        }
        val values = rows.map { row ->
            val line = addRow()
            cell(line, activity.getString(row.label), R.color.text_secondary, 17f, LABEL_WEIGHT)
            cell(line, "", R.color.text_primary, 20f, 1f) to cell(line, "", R.color.text_label, 20f, 1f)
        }

        fun render() {
            val current = TripComputer.current
            val last = TripComputer.previous
            rows.forEachIndexed { index, row ->
                val (now, before) = values[index]
                now.setTextIfChanged(row.format(activity, current))
                before.setTextIfChanged(last?.let { row.format(activity, it) } ?: activity.getString(R.string.trip_none))
            }
        }

        val listener: () -> Unit = { render() }
        render()
        TripComputer.addListener(listener)
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.trip_title)
            .setView(table)
            .setPositiveButton(R.string.trip_open_drive_mode) { _, _ ->
                AppLauncher.start(activity, Intent(activity, DriveActivity::class.java))
            }
            .setNegativeButton(R.string.trip_close, null)
            .setOnDismissListener { TripComputer.removeListener(listener) }
            .show()
    }

    private const val LABEL_WEIGHT = 1.3f
}
