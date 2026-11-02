package com.obsidian.carlauncher.drive

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.obsidian.carlauncher.R

/** The Speedometer card on the settings page. */
class SpeedometerSection(private val activity: Activity, root: View) {

    private val permission: TextView = root.findViewById(R.id.speedometer_permission)
    private val limitValue: TextView = root.findViewById(R.id.speed_limit_value)

    init {
        root.findViewById<Switch>(R.id.speedometer_enabled).apply {
            isChecked = TripComputer.isEnabled(activity)
            setOnCheckedChangeListener { _, checked ->
                TripComputer.setEnabled(activity, checked)
                if (checked && !TripComputer.hasPermission(activity)) TripComputer.requestPermission(activity)
                render()
            }
        }
        permission.setOnClickListener { TripComputer.requestPermission(activity) }
        root.findViewById<View>(R.id.speed_limit_row).setOnClickListener { askLimit() }
        root.findViewById<Switch>(R.id.speed_limit_beep).apply {
            isChecked = TripComputer.isBeepEnabled(activity)
            setOnCheckedChangeListener { _, checked -> TripComputer.setBeepEnabled(activity, checked) }
        }
        root.findViewById<Switch>(R.id.drive_auto).apply {
            isChecked = TripComputer.isAutoDriveEnabled(activity)
            setOnCheckedChangeListener { _, checked -> TripComputer.setAutoDriveEnabled(activity, checked) }
        }
        root.findViewById<TextView>(R.id.drive_auto_desc).text =
            activity.getString(R.string.settings_drive_auto_desc, TripComputer.AUTO_DRIVE_KMH)
        render()
    }

    /** Also called when the settings page returns and after a permission answer. */
    fun render() {
        val missing = TripComputer.isEnabled(activity) && !TripComputer.hasPermission(activity)
        permission.visibility = if (missing) View.VISIBLE else View.GONE
        val limit = TripComputer.speedLimit(activity)
        limitValue.text = if (limit > 0) {
            activity.getString(R.string.settings_speed_limit_value, limit)
        } else {
            activity.getString(R.string.settings_speed_limit_off)
        }
    }

    private fun askLimit() {
        val limit = TripComputer.speedLimit(activity)
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 22f
            hint = activity.getString(R.string.settings_speed_limit_hint)
            if (limit > 0) setText(limit.toString())
        }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val padded = LinearLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.settings_speed_limit_title)
            .setView(padded)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val kmh = input.text.toString().toIntOrNull() ?: 0
                TripComputer.setSpeedLimit(activity, kmh.coerceIn(0, MAX_LIMIT_KMH))
                render()
            }
            .setNeutralButton(R.string.settings_speed_limit_turn_off) { _, _ ->
                TripComputer.setSpeedLimit(activity, 0)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object {
        const val MAX_LIMIT_KMH = 240
    }
}
