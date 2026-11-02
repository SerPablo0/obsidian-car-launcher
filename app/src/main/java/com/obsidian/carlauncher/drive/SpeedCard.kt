package com.obsidian.carlauncher.drive

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.obsidian.carlauncher.R
import com.obsidian.carlauncher.drive.TripComputer.GpsState

/**
 * The speed at the top right of the home screen, with a line about the trip under it.
 * Before the car moves, that line sums up the last trip. A tap shows both trips in full.
 */
class SpeedCard(
    private val activity: Activity,
    private val root: View,
    /** Called while the car drives fast enough for driving mode to open by itself. */
    private val onDriving: () -> Unit,
) {

    private val value: TextView = root.findViewById(R.id.speed_value)
    private val trip: TextView = root.findViewById(R.id.speed_trip)
    private val listener: () -> Unit = { render() }

    init {
        root.setOnClickListener {
            if (TripComputer.gpsState == GpsState.NO_PERMISSION) {
                TripComputer.requestPermission(activity)
            } else {
                TripDialog.show(activity)
            }
        }
    }

    fun start() {
        TripComputer.addListener(listener)
        render()
    }

    fun stop() {
        TripComputer.removeListener(listener)
    }

    private fun render() {
        val state = TripComputer.gpsState
        if (state == GpsState.DISABLED) {
            root.visibility = View.GONE
            return
        }
        root.visibility = View.VISIBLE
        value.setTextIfChanged(TripComputer.speedKmh?.toString() ?: activity.getString(R.string.drive_speed_none))
        value.setTextColor(activity.getColor(if (TripComputer.isOverLimit) R.color.warning else R.color.text_primary))

        val current = TripComputer.current
        val last = TripComputer.previous
        val line = when {
            state == GpsState.NO_PERMISSION -> activity.getString(R.string.speed_allow_location)
            state == GpsState.OFF -> activity.getString(R.string.speed_location_off)
            current.hasMoved -> activity.getString(
                R.string.speed_this_trip,
                TripFormat.distance(activity, current.distanceM),
                TripFormat.duration(activity, current.durationMs),
            )
            last != null -> activity.getString(
                R.string.speed_last_trip,
                TripFormat.distance(activity, last.distanceM),
                TripFormat.duration(activity, last.durationMs),
            )
            state == GpsState.WAITING -> activity.getString(R.string.drive_gps_waiting)
            else -> ""
        }
        trip.setTextIfChanged(line)
        trip.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE

        if (TripComputer.shouldAutoOpenDriveMode()) onDriving()
    }
}
