package com.obsidian.carlauncher.maintenance

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.obsidian.carlauncher.R
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** The Maintenance card on the settings page. */
class MaintenanceSection(private val activity: Activity, root: View) {

    private val odometer: TextView = root.findViewById(R.id.maintenance_odometer)
    private val list: LinearLayout = root.findViewById(R.id.maintenance_list)
    private val numbers = NumberFormat.getIntegerInstance()

    init {
        root.findViewById<View>(R.id.maintenance_update_odometer).setOnClickListener { askOdometer() }
        render()
    }

    private fun render() {
        val km = Maintenance.odometerKm(activity)
        odometer.text = if (km == null) {
            activity.getString(R.string.maintenance_odometer_unset)
        } else {
            activity.getString(R.string.maintenance_odometer, numbers.format(km))
        }

        list.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        for (status in Maintenance.statuses(activity)) {
            val row = inflater.inflate(R.layout.item_maintenance, list, false)
            val name = activity.getString(status.item.label)
            row.findViewById<TextView>(R.id.maintenance_title).text = name
            row.findViewById<TextView>(R.id.maintenance_interval).text =
                activity.getString(R.string.maintenance_every, numbers.format(status.intervalKm), status.intervalMonths)
            row.findViewById<TextView>(R.id.maintenance_status).apply {
                text = describe(status)
                setTextColor(activity.getColor(if (status.isDueSoon) R.color.warning else R.color.text_label))
            }
            row.findViewById<View>(R.id.maintenance_done).setOnClickListener {
                if (Maintenance.odometerKm(activity) == null) {
                    Toast.makeText(activity, R.string.maintenance_done_needs_odometer, Toast.LENGTH_SHORT).show()
                } else {
                    Maintenance.markDone(activity, status.item)
                    render()
                }
            }
            row.setOnClickListener { askInterval(status) }
            list.addView(row)
        }
    }

    private fun describe(status: Maintenance.Status): String {
        val nextKm = status.nextKm ?: return activity.getString(R.string.maintenance_never_done)
        val remaining = status.remainingKm
        val distance = when {
            remaining == null -> "—"
            remaining < 0 -> activity.getString(R.string.maintenance_km_over, numbers.format(-remaining))
            else -> activity.getString(R.string.maintenance_km_left, numbers.format(remaining))
        }
        val date = status.dueAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "—"
        return activity.getString(R.string.maintenance_next, numbers.format(nextKm), distance, date)
    }

    private fun askOdometer() {
        val input = numberField(Maintenance.odometerKm(activity))
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.maintenance_odometer_title)
            .setView(padded(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().toIntOrNull()?.let {
                    Maintenance.setOdometerKm(activity, it)
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askInterval(status: Maintenance.Status) {
        val km = numberField(status.intervalKm).apply { hint = activity.getString(R.string.maintenance_edit_km) }
        val months = numberField(status.intervalMonths).apply { hint = activity.getString(R.string.maintenance_edit_months) }
        val fields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(km)
            addView(months)
        }
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(activity.getString(R.string.maintenance_edit_title, activity.getString(status.item.label)))
            .setView(padded(fields))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newKm = km.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: status.intervalKm
                val newMonths = months.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: status.intervalMonths
                Maintenance.setInterval(activity, status.item, newKm, newMonths)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun numberField(value: Int?) = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        textSize = 22f
        value?.let { setText(it.toString()) }
    }

    private fun padded(view: View): View {
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        return LinearLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }
}
